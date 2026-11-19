package com.menora.initializr.fullstack.schema;

import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.EntityScaffoldContext;
import com.menora.initializr.fullstack.FeatureContext;
import com.menora.initializr.fullstack.FieldDefinition;
import com.menora.initializr.fullstack.FieldType;
import com.menora.initializr.fullstack.RelationDefinition;
import com.menora.initializr.fullstack.SchemaNames;
import com.menora.initializr.fullstack.schema.Schema.Column;
import com.menora.initializr.fullstack.schema.Schema.ColumnType;
import com.menora.initializr.fullstack.schema.Schema.ForeignKey;
import com.menora.initializr.fullstack.schema.Schema.Index;
import com.menora.initializr.fullstack.schema.Schema.Table;
import com.menora.initializr.fullstack.schema.Schema.Unique;
import com.menora.initializr.sql.SqlNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The schema the generated JPA mappings of a fullstack model expect.
 *
 * <p>It reads the model the way {@code Entity.java.mustache} is rendered from it: the table and
 * column names come from the same functions ({@link EntityScaffoldContext#tableOf},
 * {@code columnOf}, {@code joinColumnOf}, {@code joinTableOf}), and whether an entity carries the
 * audit columns, a version or the soft-delete flag from the per-entity context the template
 * itself is gated on. A change to how an entity is mapped belongs in both places — the smoke
 * build starts a generated backend with {@code ddl-auto: validate} over this schema, which is
 * what fails when they drift.
 *
 * <p>Beyond what Hibernate would create it adds an index on every foreign key (PostgreSQL,
 * Oracle, SQL Server and DB2 do not make one) and on a workflow's status and assignee, which the
 * inbox filters by.
 */
public final class SchemaBuilder {

    private SchemaBuilder() {}

    /**
     * The version of what the generator itself adds to a schema — its own tables ({@code app_*})
     * and the indexes it puts beside an entity's columns — that this release writes. A project's
     * model file says which version its baseline was written with ({@code generator.schemaVersion}),
     * so an update's migration can bring that project's tables up to this one:
     * <ul>
     *   <li>1 — before the Attachments round (2026-11): {@code app_attachment} without
     *       {@code created_by}, its {@code content} NOT NULL, no index on {@code created_at};</li>
     *   <li>2 — the Attachments round;</li>
     *   <li>3 — Round 13: an index on every FILE/IMAGE column ({@code ixa_…}: the read check
     *       counts rows by them), and {@code app_attachment.thumbnail}.</li>
     * </ul>
     * A model file without one is from before versions were written: 1 or 2.
     */
    public static final int CURRENT_VERSION = 3;

    /**
     * @param projectContext the backend render context (it says whether the project has the
     *                       attachment, saved-view and workflow-move tables, and resolves each
     *                       entity's options)
     */
    public static Schema build(Map<String, Object> projectContext, List<EntityDefinition> entities) {
        Map<String, EntityDefinition> byName = new LinkedHashMap<>();
        for (EntityDefinition e : entities) byName.put(e.name(), e);

        List<Table> tables = new ArrayList<>();
        List<Table> joinTables = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            if (entity.isView()) continue;
            Map<String, Object> ctx = EntityScaffoldContext.buildEntityContext(projectContext, entity);
            tables.add(tableOf(entity, ctx, byName));
            for (RelationDefinition collection : entity.collections()) {
                joinTables.add(joinTableOf(entity, collection, byName));
            }
            for (FieldDefinition field : entity.fields()) {
                if (field.multiple()) joinTables.add(filesTableOf(entity, field));
            }
        }
        tables.addAll(joinTables);
        if (Boolean.TRUE.equals(projectContext.get("hasAttachments"))) tables.add(attachments());
        if (Boolean.TRUE.equals(projectContext.get("hasSavedViews"))) tables.add(savedViews());
        if (Boolean.TRUE.equals(projectContext.get("hasWorkflowMoves"))) tables.add(workflowMoves());
        if (Boolean.TRUE.equals(projectContext.get("hasHistory"))) tables.add(changeLog());
        if (Boolean.TRUE.equals(projectContext.get("hasOutbox"))) tables.add(outbox());
        return new Schema(tables);
    }

    private static Table tableOf(EntityDefinition entity, Map<String, Object> ctx, Map<String, EntityDefinition> byName) {
        String schema = name(SchemaNames.schemaOf(entity));
        String table = name(SchemaNames.tableOf(entity));
        List<Column> columns = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        List<ForeignKey> foreignKeys = new ArrayList<>();
        List<Unique> uniques = new ArrayList<>();
        List<Index> indexes = new ArrayList<>();

        for (FieldDefinition f : entity.fields()) {
            // Several files: a table of their own (filesTableOf), no column here.
            if (f.multiple()) continue;
            String column = name(SchemaNames.columnOf(f));
            ColumnType type = typeOf(f.type());
            Integer length = type == ColumnType.VARCHAR ? (f.length() != null ? f.length() : 255) : null;
            boolean identity = f.primaryKey() && f.generated() && !f.type().isUuid();
            columns.add(new Column(column, type, length, !f.primaryKey() && !f.required(), identity, false));
            if (f.primaryKey()) primaryKey.add(column);
            else if (f.unique()) uniques.add(new Unique(constraint("uk", table, column), List.of(column)));
            // Who may read a file is asked by counting the rows that hold it (Attachments.ownersOf).
            if (f.type().isAttachment()) indexes.add(new Index(constraint(ATTACHMENT_INDEX, table, column), List.of(column)));
        }
        // Unique keys over several fields and links, in the order the model gives them.
        for (EntityDefinition.UniqueKey key : entity.uniqueKeys()) {
            List<String> keyColumns = new ArrayList<>();
            for (String member : key.fields()) keyColumns.add(name(FeatureContext.uniqueKeyColumnOf(entity, member)));
            uniques.add(new Unique(uniqueKeyName(table, keyColumns), keyColumns));
        }
        for (RelationDefinition rel : entity.relations()) {
            EntityDefinition target = byName.get(rel.targetEntity());
            if (target == null) continue;
            FieldDefinition targetKey = keyOf(target);
            String column = name(SchemaNames.joinColumnOf(rel));
            columns.add(referencing(column, targetKey, !rel.required()));
            foreignKeys.add(new ForeignKey(constraint("fk", table, column), List.of(column),
                    name(SchemaNames.schemaOf(target)), name(SchemaNames.tableOf(target)),
                    List.of(name(SchemaNames.columnOf(targetKey)))));
            indexes.add(new Index(constraint("ix", table, column), List.of(column)));
        }
        if (Boolean.TRUE.equals(ctx.get("auditApplicable"))) {
            columns.add(Column.of("created_at", ColumnType.INSTANT, true));
            columns.add(Column.of("updated_at", ColumnType.INSTANT, true));
            columns.add(Column.varchar("created_by", 255, true));
            columns.add(Column.varchar("updated_by", 255, true));
        }
        if (Boolean.TRUE.equals(ctx.get("lockingApplicable"))) {
            columns.add(Column.of("version", ColumnType.BIGINT, true));
        }
        if (Boolean.TRUE.equals(ctx.get("softDeleteApplicable"))) {
            columns.add(new Column("deleted", ColumnType.BOOLEAN, null, false, false, true));
        }
        // The inbox asks for a state and for whose the row is.
        if (entity.workflow() != null) {
            for (String fieldName : new String[] {entity.workflow().statusField(), entity.workflow().assigneeField()}) {
                FieldDefinition field = fieldName == null ? null : entity.fields().stream()
                        .filter(f -> f.name().equals(fieldName)).findFirst().orElse(null);
                if (field == null) continue;
                String column = name(SchemaNames.columnOf(field));
                indexes.add(new Index(constraint("ix", table, column), List.of(column)));
            }
        }
        return new Table(schema, table, columns, primaryKey, foreignKeys, uniques, indexes);
    }

    /** The join table of a MANY_TO_MANY: a column per side, both the key, a foreign key each. */
    private static Table joinTableOf(EntityDefinition owner, RelationDefinition collection,
                                     Map<String, EntityDefinition> byName) {
        EntityDefinition target = byName.get(collection.targetEntity());
        String table = name(SchemaNames.joinTableOf(owner, collection));
        String ownerColumn = name(SchemaNames.ownerJoinColumnOf(owner, collection));
        String targetColumn = name(SchemaNames.inverseJoinColumnOf(collection));
        FieldDefinition ownerKey = keyOf(owner);
        FieldDefinition targetKey = keyOf(target);
        List<Column> columns = List.of(referencing(ownerColumn, ownerKey, false), referencing(targetColumn, targetKey, false));
        List<ForeignKey> foreignKeys = List.of(
                new ForeignKey(constraint("fk", table, ownerColumn), List.of(ownerColumn),
                        name(SchemaNames.schemaOf(owner)), name(SchemaNames.tableOf(owner)),
                        List.of(name(SchemaNames.columnOf(ownerKey)))),
                new ForeignKey(constraint("fk", table, targetColumn), List.of(targetColumn),
                        name(SchemaNames.schemaOf(target)), name(SchemaNames.tableOf(target)),
                        List.of(name(SchemaNames.columnOf(targetKey)))));
        // The key starts with the owner's column; the other side needs an index of its own.
        return new Table(null, table, columns, List.of(ownerColumn, targetColumn), foreignKeys, List.of(),
                List.of(new Index(constraint("ix", table, targetColumn), List.of(targetColumn))));
    }

    /**
     * The table of a field that holds several files ({@code attachment/AttachedFile}, the entity's
     * {@code @ElementCollection}): the owner's key, the file's place in the list (the key with it),
     * the file's id — indexed, the read check counts rows by it — and its name.
     */
    private static Table filesTableOf(EntityDefinition owner, FieldDefinition field) {
        String table = name(SchemaNames.filesTableOf(owner, field));
        FieldDefinition ownerKey = keyOf(owner);
        List<Column> columns = List.of(
                referencing("owner_id", ownerKey, false),
                Column.of("file_order", ColumnType.INTEGER, false),
                Column.of("attachment_id", ColumnType.UUID, false),
                Column.varchar("file_name", 255, true));
        return new Table(name(SchemaNames.schemaOf(owner)), table, columns, List.of("owner_id", "file_order"),
                List.of(new ForeignKey(constraint("fk", table, "owner_id"), List.of("owner_id"),
                        name(SchemaNames.schemaOf(owner)), name(SchemaNames.tableOf(owner)),
                        List.of(name(SchemaNames.columnOf(ownerKey))))),
                List.of(),
                List.of(new Index(constraint(ATTACHMENT_INDEX, table, "attachment_id"), List.of("attachment_id"))));
    }

    /** A column holding another row's key: the key's type, never numbered by the database. */
    private static Column referencing(String column, FieldDefinition key, boolean nullable) {
        ColumnType type = typeOf(key.type());
        Integer length = type == ColumnType.VARCHAR ? (key.length() != null ? key.length() : 255) : null;
        return new Column(column, type, length, nullable, false, false);
    }

    /** The key a relation points at: a relation's target has exactly one (the validator's rule). */
    private static FieldDefinition keyOf(EntityDefinition entity) {
        return entity.fields().stream().filter(FieldDefinition::primaryKey).findFirst().orElseThrow();
    }

    /** The column a field type is stored in — exhaustive, so a new type cannot be forgotten. */
    static ColumnType typeOf(FieldType type) {
        return switch (type) {
            case STRING, ENUM -> ColumnType.VARCHAR;
            case TEXT, MARKDOWN -> ColumnType.TEXT;
            case LONG -> ColumnType.BIGINT;
            case INTEGER -> ColumnType.INTEGER;
            case BOOLEAN -> ColumnType.BOOLEAN;
            case LOCAL_DATE -> ColumnType.DATE;
            case LOCAL_DATE_TIME -> ColumnType.TIMESTAMP;
            case LOCAL_TIME -> ColumnType.TIME;
            case INSTANT -> ColumnType.INSTANT;
            case BIG_DECIMAL -> ColumnType.DECIMAL;
            case MONEY -> ColumnType.MONEY;
            case UUID, FILE, IMAGE -> ColumnType.UUID;
        };
    }

    /** The prefix of the index on a FILE/IMAGE column (version 3). */
    static final String ATTACHMENT_INDEX = "ixa";

    /**
     * {@code schema} — built by this release — as an older version of the generator wrote it: the
     * generator's own tables of that version, and none of the indexes it added since. What an
     * update's migration compares a project's schema from. {@code null} (a model file from before
     * versions were written) is read as the oldest.
     */
    public static Schema asOf(Schema schema, Integer version) {
        int v = version == null ? 1 : version;
        if (v >= CURRENT_VERSION) return schema;
        List<Table> tables = new ArrayList<>();
        for (Table t : schema.tables()) {
            if (t.name().equals(ATTACHMENTS) && t.schema() == null) {
                tables.add(attachments(v));
                continue;
            }
            List<Index> indexes = v >= 3 ? t.indexes()
                    : t.indexes().stream().filter(i -> !i.name().startsWith(ATTACHMENT_INDEX + "_")).toList();
            tables.add(new Table(t.schema(), t.name(), t.columns(), t.primaryKey(), t.foreignKeys(), t.uniques(), indexes));
        }
        return new Schema(tables);
    }

    /** The generator's own tables: their names begin so (attachments, views, moves, history, outbox). */
    public static boolean isGeneratorTable(String ref) {
        return ref != null && ref.startsWith("app_");
    }

    // ── The generator's own tables (attachment/, views/, workflow/ in the backend set) ──

    private static final String ATTACHMENTS = "app_attachment";

    private static Table attachments() {
        return attachments(CURRENT_VERSION);
    }

    private static Table attachments(int version) {
        List<Column> columns = new ArrayList<>(List.of(
                Column.of("id", ColumnType.UUID, false),
                Column.varchar("file_name", 255, false),
                Column.varchar("content_type", 150, false),
                Column.of("size_bytes", ColumnType.BIGINT, false),
                Column.of("created_at", ColumnType.INSTANT, false)));
        // Who uploaded it (version 2: a file on no record yet is its uploader's).
        if (version >= 2) columns.add(Column.varchar("created_by", 255, true));
        // Only the database storage fills it; a folder or an object store keeps the bytes instead
        // (version 2 — before, the database held every file).
        columns.add(Column.of("content", ColumnType.BLOB, version >= 2));
        // A picture's small version, made on its first request (version 3, Thumbnails).
        if (version >= 3) columns.add(Column.of("thumbnail", ColumnType.BLOB, true));
        return new Table(null, ATTACHMENTS, columns,
                List.of("id"), List.of(), List.of(),
                // The clean-up looks for old uploads (Attachments.removeUnattached; version 2).
                version >= 2 ? List.of(new Index("ix_app_attachment_created", List.of("created_at"))) : List.of());
    }

    private static Table savedViews() {
        return new Table(null, "app_saved_view", List.of(
                new Column("id", ColumnType.BIGINT, null, false, true, false),
                Column.varchar("list_name", 100, false),
                Column.varchar("owner_id", 200, false),
                Column.varchar("view_name", 80, false),
                Column.of("shared_view", ColumnType.BOOLEAN, false),
                Column.of("default_view", ColumnType.BOOLEAN, false),
                Column.varchar("view_state", 4000, false),
                Column.of("updated_at", ColumnType.INSTANT, false)),
                List.of("id"), List.of(),
                List.of(new Unique("uk_app_saved_view_name", List.of("list_name", "owner_id", "view_name"))),
                List.of());
    }

    /** The history option's change log ({@code history/ChangeLogEntry}). */
    private static Table changeLog() {
        return new Table(null, "app_change_log", List.of(
                new Column("id", ColumnType.BIGINT, null, false, true, false),
                Column.varchar("entity_name", 100, false),
                Column.varchar("row_id", 100, false),
                Column.varchar("action", 16, false),
                Column.of("changed_at", ColumnType.INSTANT, false),
                Column.varchar("changed_by", 255, false),
                Column.varchar("changed_fields", 2000, true),
                Column.of("before_json", ColumnType.TEXT, true),
                Column.of("after_json", ColumnType.TEXT, true)),
                List.of("id"), List.of(), List.of(),
                List.of(new Index("ix_app_change_log_row", List.of("entity_name", "row_id"))));
    }

    /** What the events option and the transition e-mails send, until sent ({@code events/OutboxMessage}). */
    private static Table outbox() {
        return new Table(null, "app_outbox", List.of(
                new Column("id", ColumnType.BIGINT, null, false, true, false),
                Column.varchar("event_id", 36, false),
                Column.varchar("event_type", 150, false),
                Column.varchar("entity_name", 100, false),
                Column.varchar("row_id", 100, false),
                Column.varchar("channel", 120, false),
                Column.of("payload", ColumnType.TEXT, false),
                Column.varchar("status", 16, false),
                Column.of("attempts", ColumnType.INTEGER, false),
                Column.of("created_at", ColumnType.INSTANT, false),
                Column.of("next_attempt_at", ColumnType.INSTANT, false),
                Column.of("locked_until", ColumnType.INSTANT, true),
                Column.of("sent_at", ColumnType.INSTANT, true),
                Column.varchar("last_error", 2000, true)),
                List.of("id"), List.of(), List.of(),
                List.of(new Index("ix_app_outbox_due", List.of("status", "next_attempt_at"))));
    }

    private static Table workflowMoves() {
        return new Table(null, "app_workflow_move", List.of(
                new Column("id", ColumnType.BIGINT, null, false, true, false),
                Column.varchar("entity_name", 100, false),
                Column.varchar("row_id", 100, false),
                Column.varchar("from_state", 100, true),
                Column.varchar("to_state", 100, false),
                Column.varchar("transition_name", 40, true),
                Column.varchar("actor", 200, true),
                Column.of("moved_at", ColumnType.INSTANT, false),
                Column.of("seconds_in_from", ColumnType.BIGINT, true)),
                List.of("id"), List.of(), List.of(),
                List.of(new Index("ix_app_workflow_move_row", List.of("entity_name", "row_id"))));
    }

    // ── Names ────────────────────────────────────────────────────────────────

    /**
     * A name as SQL: as it is when the database may fold it, in double quotes — which every one
     * of the five databases reads — when the model spells exact case in any of the three styles
     * Hibernate takes ({@code "x"}, {@code `x`}, {@code [x]}).
     */
    public static String name(String physical) {
        if (physical == null) return null;
        return SqlNames.isQuoted(physical) ? "\"" + SqlNames.unquote(physical) + "\"" : physical;
    }

    /**
     * {@code <prefix>_<table>_<column>} in lower case, cut to what the strictest of the five
     * databases takes for a name (PostgreSQL: 63 bytes) with a hash of the whole in place of the
     * cut-off end, so two long names stay two names.
     */
    static String constraint(String prefix, String table, String column) {
        String raw = (prefix + "_" + plain(table) + "_" + plain(column)).toLowerCase(Locale.ROOT);
        if (raw.length() <= 60) return raw;
        return raw.substring(0, 51) + "_" + String.format("%08x", raw.hashCode());
    }

    /**
     * A unique key's constraint: {@code uk_<table>_<column>_<column>…} by the same rule. The
     * generated entity's {@code @UniqueConstraint} and its API's 409 name it the same way.
     */
    public static String uniqueKeyName(String table, List<String> columns) {
        List<String> plain = new ArrayList<>();
        for (String column : columns) plain.add(plain(column));
        return constraint("uk", table, String.join("_", plain));
    }

    /** {@code <prefix>_<table>}, by the same rule. */
    static String constraint(String prefix, String table) {
        String raw = (prefix + "_" + plain(table)).toLowerCase(Locale.ROOT);
        if (raw.length() <= 60) return raw;
        return raw.substring(0, 51) + "_" + String.format("%08x", raw.hashCode());
    }

    private static String plain(String name) {
        String bare = name.startsWith("\"") && name.endsWith("\"") && name.length() > 1
                ? name.substring(1, name.length() - 1) : name;
        return bare.replaceAll("[^A-Za-z0-9_]+", "_");
    }
}
