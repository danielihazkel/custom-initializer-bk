package com.menora.initializr.admin.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.menora.initializr.db.entity.*;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.Type;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the catalog's change log ({@code admin_catalog_change}): every catalog row created,
 * changed or deleted, through any code path that goes through Hibernate — the admin screens, an
 * import, the catalog sync, the template reset, the start-up syncs — with who and why
 * ({@link CatalogChangeContext}) and the row before and after as JSON. A Hibernate listener on
 * the session factory, so a change cannot be made without its record; written with JDBC on the
 * change's own connection, so the record commits and rolls back with it.
 *
 * <p>Bulk JPQL and native SQL bypass Hibernate's events: catalog code must not change catalog rows
 * that way (the import and the default-dependencies edit delete row by row for that reason).
 */
@Component
public class CatalogHistoryRecorder implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    /** The catalog: what admins edit and the release ships. User data (saved models, the audit) is not in it. */
    public static final Set<Class<?>> TRACKED = Set.of(
            BuildCustomizationEntity.class, ColorPaletteEntity.class, DepartmentEntity.class,
            DependencyCompatibilityEntity.class, DependencyEntryEntity.class, DependencyGroupEntity.class,
            DependencySubOptionEntity.class, EntityTemplateFileEntity.class, EntityTemplateSetDefaultDepEntity.class,
            EntityTemplateSetEntity.class, FileContributionEntity.class, FullstackExampleEntity.class,
            ModuleDependencyMappingEntity.class, ModuleTemplateEntity.class, SchemaSourceEntity.class,
            StarterTemplateDepEntity.class, StarterTemplateEntity.class, VersionDefinitionEntity.class);

    /** The properties that name a row, in the order they are tried for its label. */
    private static final List<String> LABEL_PROPERTIES = List.of(
            "depId", "setKey", "targetPath", "pathTemplate", "optionId", "moduleId", "exampleId", "departmentId", "name", "label",
            "value", "version", "dependencyId", "mavenArtifactId");

    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String INSERT = "INSERT INTO admin_catalog_change (changed_at, actor, source, request_id, "
            + "entity_type, table_name, row_id, action, label, before_json, after_json, changed_fields) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final EntityManagerFactory entityManagerFactory;

    public CatalogHistoryRecorder(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImpl.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        if (!tracked(event.getEntity())) return;
        Map<String, Object> after = snapshot(event.getPersister(), event.getId(), event.getState(), event.getSession());
        write(event.getSession(), event.getPersister(), event.getId(), CatalogChangeEntity.Action.CREATE, null, after, null);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (!tracked(event.getEntity())) return;
        EntityPersister persister = event.getPersister();
        Map<String, Object> after = snapshot(persister, event.getId(), event.getState(), event.getSession());
        Map<String, Object> before = event.getOldState() == null ? null
                : snapshot(persister, event.getId(), event.getOldState(), event.getSession());
        List<String> changed = new ArrayList<>();
        if (before != null) {
            for (var e : after.entrySet()) {
                if (!java.util.Objects.equals(e.getValue(), before.get(e.getKey()))) changed.add(e.getKey());
            }
            if (changed.isEmpty()) return;
        }
        write(event.getSession(), persister, event.getId(), CatalogChangeEntity.Action.UPDATE, before, after,
                changed.isEmpty() ? null : String.join(",", changed));
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        if (!tracked(event.getEntity())) return;
        Map<String, Object> before = snapshot(event.getPersister(), event.getId(), event.getDeletedState(), event.getSession());
        write(event.getSession(), event.getPersister(), event.getId(), CatalogChangeEntity.Action.DELETE, before, null, null);
    }

    private static boolean tracked(Object entity) {
        return entity != null && TRACKED.contains(entity.getClass()) && !CatalogChangeContext.current().suppressed();
    }

    /**
     * A row as JSON-ready values by property name: its id first, then each column property —
     * a reference to another row as that row's id, a collection left out.
     */
    static Map<String, Object> snapshot(EntityPersister persister, Object id, Object[] state, SharedSessionContractImplementor session) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(persister.getIdentifierPropertyName(), id);
        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        for (int i = 0; i < names.length; i++) {
            Type type = types[i];
            if (type.isCollectionType()) continue;
            Object value = state == null ? null : state[i];
            if (type.isEntityType() && value != null) value = idOf(value, session);
            if (value instanceof Enum<?> e) value = e.name();
            values.put(names[i], value);
        }
        // Through JSON text and back, so a snapshot compares equal to one read back from the log
        // (an in-memory conversion keeps a Long a Long; text read back makes a small one an Integer).
        try {
            return JSON.readValue(JSON.writeValueAsString(values), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A catalog row could not be written as JSON", e);
        }
    }

    private static Object idOf(Object entity, SharedSessionContractImplementor session) {
        if (entity instanceof HibernateProxy proxy) return proxy.getHibernateLazyInitializer().getInternalIdentifier();
        return session.getEntityPersister(null, entity).getIdentifier(entity, session);
    }

    static String labelOf(Map<String, Object> row) {
        if (row == null) return null;
        for (String property : LABEL_PROPERTIES) {
            Object value = row.get(property);
            if (value != null && !String.valueOf(value).isBlank()) {
                String text = String.valueOf(value);
                return text.length() > 255 ? text.substring(0, 255) : text;
            }
        }
        return null;
    }

    /**
     * Records a row put back with SQL (a deleted row restored with its own id, which Hibernate
     * cannot insert): the one change in the catalog not seen by the listener.
     */
    static void recordInsert(org.hibernate.Session session, EntityPersister persister, Object id, Map<String, Object> after) {
        write(session, persister, id, CatalogChangeEntity.Action.CREATE, null, after, null);
    }

    private static void write(org.hibernate.Session session, EntityPersister persister, Object id,
                              CatalogChangeEntity.Action action, Map<String, Object> before, Map<String, Object> after,
                              String changedFields) {
        CatalogChangeContext.State context = CatalogChangeContext.current();
        String requestId = context.requestId() != null ? context.requestId() : java.util.UUID.randomUUID().toString();
        String table = persister instanceof AbstractEntityPersister p ? p.getTableName() : persister.getEntityName();
        String label = labelOf(after != null ? after : before);
        String beforeJson = json(before);
        String afterJson = json(after);
        String fields = changedFields != null && changedFields.length() > 1024 ? changedFields.substring(0, 1024) : changedFields;
        session.doWork(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setTimestamp(1, Timestamp.from(Instant.now()));
                insert.setString(2, truncate(context.actor(), 255));
                insert.setString(3, truncate(context.source(), 32));
                insert.setString(4, requestId);
                insert.setString(5, persister.getMappedClass().getSimpleName());
                insert.setString(6, truncate(table, 64));
                insert.setString(7, truncate(String.valueOf(id), 64));
                insert.setString(8, action.name());
                insert.setString(9, label);
                insert.setString(10, beforeJson);
                insert.setString(11, afterJson);
                insert.setString(12, fields);
                insert.executeUpdate();
            }
        });
    }

    private static String json(Map<String, Object> row) {
        if (row == null) return null;
        try {
            return JSON.writeValueAsString(row);
        } catch (Exception e) {
            throw new IllegalStateException("A catalog change could not be written as JSON", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
