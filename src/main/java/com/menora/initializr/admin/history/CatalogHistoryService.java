package com.menora.initializr.admin.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.menora.initializr.config.DatabaseInitializrMetadataProvider;
import com.menora.initializr.db.entity.CatalogChangeEntity;
import com.menora.initializr.db.repository.CatalogChangeRepository;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.EntityType;
import org.hibernate.type.Type;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reading the catalog's change log and putting rows back: a change undone (the row as it was
 * before it), a row taken to the version a change made, or every change of one request undone
 * together (an import, a catalog-sync apply, a template reset). What a restore does is itself
 * recorded, as source "restore", by whoever asked.
 *
 * <p>Undo checks first that the row is still as the change left it; when it is not — somebody
 * changed it since — it refuses ({@link ConflictException}) unless forced.
 */
@Service
public class CatalogHistoryService {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    /** The row moved on since the change: undoing would overwrite somebody's later work. */
    public static class ConflictException extends RuntimeException {
        private final List<String> rows;

        public ConflictException(List<String> rows) {
            super(rows.size() == 1
                    ? rows.get(0) + ": undo anyway to overwrite that"
                    : rows.size() + " rows have changed since (" + rows.get(0) + ", …): undo anyway to overwrite that");
            this.rows = List.copyOf(rows);
        }

        public List<String> rows() {
            return rows;
        }
    }

    @PersistenceContext
    private EntityManager em;

    private final CatalogChangeRepository repo;
    private final InitializrMetadataProvider metadataProvider;

    public CatalogHistoryService(CatalogChangeRepository repo, InitializrMetadataProvider metadataProvider) {
        this.repo = repo;
        this.metadataProvider = metadataProvider;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    public record Filter(String entityType, String rowId, String actor, String source, String requestId,
                         Instant from, Instant to, String q) {}

    public Page<CatalogChangeEntity> changes(Filter f, int page, int size) {
        Specification<CatalogChangeEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (present(f.entityType())) where.add(cb.equal(root.get("entityType"), f.entityType().trim()));
            if (present(f.rowId())) where.add(cb.equal(root.get("rowId"), f.rowId().trim()));
            if (present(f.actor())) where.add(cb.equal(root.get("actor"), f.actor().trim()));
            if (present(f.source())) where.add(cb.equal(root.get("source"), f.source().trim()));
            if (present(f.requestId())) where.add(cb.equal(root.get("requestId"), f.requestId().trim()));
            if (f.from() != null) where.add(cb.greaterThanOrEqualTo(root.get("changedAt"), f.from()));
            if (f.to() != null) where.add(cb.lessThan(root.get("changedAt"), f.to()));
            if (present(f.q())) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("label")), like, '\\'),
                        cb.like(cb.lower(root.get("actor")), like, '\\'),
                        cb.like(cb.lower(root.get("changedFields")), like, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)),
                Sort.by(Sort.Direction.DESC, "id")));
    }

    public record RequestSummary(String requestId, Instant at, String actor, String source, long changes) {}

    public Page<RequestSummary> requests(int page, int size) {
        return repo.requests(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))))
                .map(row -> new RequestSummary((String) row[0], (Instant) row[1], (String) row[2], (String) row[3],
                        ((Number) row[4]).longValue()));
    }

    public CatalogChangeEntity change(long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("No catalog change " + id));
    }

    /** What changed in a change, field by field. */
    public static Map<String, Object> parse(String json) {
        if (json == null) return null;
        try {
            return CatalogHistoryRecorder.JSON.readValue(json, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable catalog change", e);
        }
    }

    // ── Restoring ───────────────────────────────────────────────────────────

    /** Undoes one change: the row back as it was before it (a created row removed, a deleted one re-created). */
    @Transactional
    public void undo(long changeId, boolean force) {
        CatalogChangeEntity change = change(changeId);
        List<String> conflicts = new ArrayList<>();
        undo(change, force, conflicts);
        if (!conflicts.isEmpty()) throw new ConflictException(conflicts);
        refresh();
    }

    /** Takes the row to the version a change made (deleted, if the change deleted it). */
    @Transactional
    public void restoreVersion(long changeId) {
        CatalogChangeEntity change = change(changeId);
        put(change.getEntityType(), change.getRowId(), parse(change.getAfterJson()));
        refresh();
    }

    /** Undoes every change of one request, the last first, all or none. */
    @Transactional
    public int undoRequest(String requestId, boolean force) {
        List<CatalogChangeEntity> changes = repo.findByRequestIdOrderByIdDesc(requestId);
        if (changes.isEmpty()) throw new IllegalArgumentException("No catalog changes in request " + requestId);
        List<String> conflicts = new ArrayList<>();
        for (CatalogChangeEntity change : changes) undo(change, force, conflicts);
        if (!conflicts.isEmpty()) throw new ConflictException(conflicts);
        refresh();
        return changes.size();
    }

    private void undo(CatalogChangeEntity change, boolean force, List<String> conflicts) {
        if (change.getAction() == CatalogChangeEntity.Action.UPDATE && change.getBeforeJson() == null) {
            throw new IllegalArgumentException("Change " + change.getId() + " was recorded without the row's earlier values: "
                    + "it cannot be undone");
        }
        Map<String, Object> current = currentSnapshot(change.getEntityType(), change.getRowId());
        Map<String, Object> left = parse(change.getAfterJson());
        if (!force && !Objects.equals(current, left)) {
            conflicts.add(change.getEntityType() + " " + (change.getLabel() != null ? "'" + change.getLabel() + "'" : "#" + change.getRowId())
                    + " (" + differences(left, current) + ")");
            return;
        }
        if (!conflicts.isEmpty()) return;
        put(change.getEntityType(), change.getRowId(), parse(change.getBeforeJson()));
    }

    /** What differs between how a change left a row and how it stands: field names, or "deleted" / "re-created". */
    static String differences(Map<String, Object> left, Map<String, Object> current) {
        if (current == null) return "deleted since";
        if (left == null) return "created since";
        List<String> fields = new ArrayList<>();
        for (String key : current.keySet()) {
            if (!Objects.equals(current.get(key), left.get(key))) fields.add(key);
        }
        return fields.isEmpty() ? "changed since" : String.join(", ", fields) + " changed since";
    }

    /** The row as it stands, in the change log's form; null when there is none. */
    private Map<String, Object> currentSnapshot(String entityType, String rowId) {
        Class<?> type = typeOf(entityType);
        EntityPersister persister = persister(type);
        Object id = idOf(persister, rowId);
        // A row referred to by one already loaded is a lazy proxy here, whose fields read empty.
        Object entity = org.hibernate.Hibernate.unproxy(em.find(type, id));
        if (entity == null) return null;
        Object[] state = persister.getValues(entity);
        return CatalogHistoryRecorder.snapshot(persister, id, state, em.unwrap(SharedSessionContractImplementor.class));
    }

    /** Makes the row {@code target}: removed when null, updated in place, or re-inserted with its id. */
    private void put(String entityType, String rowId, Map<String, Object> target) {
        Class<?> type = typeOf(entityType);
        EntityPersister persister = persister(type);
        Object id = idOf(persister, rowId);
        // A row referred to by one already loaded is a lazy proxy here, whose fields read empty.
        Object entity = org.hibernate.Hibernate.unproxy(em.find(type, id));
        if (target == null) {
            if (entity != null) em.remove(entity);
            return;
        }
        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        if (entity != null) {
            Object[] values = persister.getValues(entity);
            for (int i = 0; i < names.length; i++) {
                if (types[i].isCollectionType() || !target.containsKey(names[i])) continue;
                values[i] = toProperty(types[i], target.get(names[i]));
            }
            persister.setValues(entity, values);
            return;
        }
        reinsert(persister, id, target);
    }

    /**
     * A deleted row back with its own id, so whatever referred to it (a dependency entry's group,
     * a template's dependencies) finds it again — which Hibernate's identity insert cannot do.
     */
    private void reinsert(EntityPersister persister, Object id, Map<String, Object> target) {
        AbstractEntityPersister p = (AbstractEntityPersister) persister;
        em.flush();
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        columns.add(p.getIdentifierColumnNames()[0]);
        values.add(id);
        String[] names = p.getPropertyNames();
        Type[] types = p.getPropertyTypes();
        for (int i = 0; i < names.length; i++) {
            if (types[i].isCollectionType()) continue;
            String[] cols = p.getPropertyColumnNames(i);
            if (cols.length != 1) continue;
            columns.add(cols[0]);
            values.add(toColumn(types[i], target.get(names[i])));
        }
        String sql = "INSERT INTO " + p.getTableName() + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
        Session session = em.unwrap(Session.class);
        session.doWork(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.size(); i++) insert.setObject(i + 1, values.get(i));
                insert.executeUpdate();
            }
        });
        CatalogHistoryRecorder.recordInsert(session, persister, id, target);
    }

    /** A logged value as the entity holds it: a referenced row's reference, an enum constant, a typed value. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object toProperty(Type type, Object raw) {
        if (raw == null) return null;
        if (type.isEntityType()) {
            Class<?> target = ((EntityType) type).getReturnedClass();
            EntityPersister referenced = persister(target);
            return em.getReference(target, idOf(referenced, String.valueOf(raw)));
        }
        Class<?> javaType = type.getReturnedClass();
        if (javaType.isEnum()) return Enum.valueOf((Class<Enum>) javaType, String.valueOf(raw));
        return CatalogHistoryRecorder.JSON.convertValue(raw, javaType);
    }

    /** A logged value as its column takes it: a reference as the referenced id, an enum by name. */
    private Object toColumn(Type type, Object raw) {
        if (raw == null) return null;
        if (type.isEntityType()) {
            EntityPersister referenced = persister(((EntityType) type).getReturnedClass());
            return idOf(referenced, String.valueOf(raw));
        }
        Class<?> javaType = type.getReturnedClass();
        if (javaType.isEnum()) return String.valueOf(raw);
        Object value = CatalogHistoryRecorder.JSON.convertValue(raw, javaType);
        if (value instanceof Instant instant) return Timestamp.from(instant);
        return value;
    }

    private static Class<?> typeOf(String entityType) {
        for (Class<?> type : CatalogHistoryRecorder.TRACKED) {
            if (type.getSimpleName().equals(entityType)) return type;
        }
        throw new IllegalArgumentException("Not a catalog table: " + entityType);
    }

    private EntityPersister persister(Class<?> type) {
        return em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel().getEntityDescriptor(type);
    }

    private static Object idOf(EntityPersister persister, String rowId) {
        Class<?> idType = persister.getIdentifierType().getReturnedClass();
        if (idType == Long.class || idType == long.class) return Long.valueOf(rowId);
        if (idType == Integer.class || idType == int.class) return Integer.valueOf(rowId);
        return rowId;
    }

    /** The generators read the catalog through the metadata cache: rebuilt after a restore, as after an admin edit. */
    private void refresh() {
        em.flush();
        if (metadataProvider instanceof DatabaseInitializrMetadataProvider provider) provider.refresh();
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
