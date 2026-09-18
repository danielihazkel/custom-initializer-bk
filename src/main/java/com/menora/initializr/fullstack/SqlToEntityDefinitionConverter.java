package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.ColumnModel;
import com.menora.initializr.sql.ForeignKey;
import com.menora.initializr.sql.JavaType;
import com.menora.initializr.sql.SqlDialect;
import com.menora.initializr.sql.SqlEntityGenerator;
import com.menora.initializr.sql.TableModel;
import com.menora.initializr.sql.TypeMappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges the SQL wizard's {@link TableModel} representation into the fullstack
 * scaffold's {@link EntityDefinition} model. Lets the wizard accept pasted CREATE
 * TABLE DDL without duplicating the type / PK / nullability logic that
 * {@link SqlEntityGenerator} already implements.
 *
 * <p>Mapping is lossy by design — the generated {@code EntityDefinition} is a
 * starting point the user can refine in the editor before submitting:
 * <ul>
 *   <li>Java {@code Short} → {@link FieldType#INTEGER}; {@code Float}/{@code Double} →
 *       {@link FieldType#BIG_DECIMAL}; {@code byte[]} and {@code UUID} → {@link FieldType#STRING}.</li>
 *   <li>Inline column-level {@code UNIQUE} is carried through to {@code unique}; table-level
 *       {@code UNIQUE(...)} constraints are not tracked.</li>
 *   <li>A single-column {@code FOREIGN KEY} whose referenced table is part of the same import
 *       (and has a single-column key) becomes a {@link RelationType#MANY_TO_ONE} relation and the
 *       FK column is dropped from the fields — the relation renders the join column. Composite FKs,
 *       FKs to tables outside the paste, FKs to composite-key tables and FK columns that are also
 *       part of the primary key stay plain fields.</li>
 *   <li>{@code CHECK (col IN ('A','B'))} — column-level or table-level — turns the column into an
 *       {@link FieldType#ENUM} with those constants. It is read from the statement text with a regex
 *       rather than the JSqlParser AST because column-level checks only survive there as raw
 *       tokens. Values that can't be made Java constants (or collide once upper-cased) leave the
 *       column as a plain string.</li>
 * </ul>
 */
@Component
public class SqlToEntityDefinitionConverter {

    /** {@code CHECK ( col IN ( 'A', 'B' ) )}, optionally with a quoted column name. */
    private static final Pattern CHECK_IN = Pattern.compile(
            "(?is)CHECK\\s*\\(\\s*[\"`\\[]?(\\w+)[\"`\\]]?\\s+IN\\s*\\(([^)]*)\\)\\s*\\)");

    private final SqlEntityGenerator generator;

    public SqlToEntityDefinitionConverter(SqlEntityGenerator generator) {
        this.generator = generator;
    }

    /** What an imported table becomes, so a foreign key can resolve its target entity. */
    private record ImportedTable(String entityName, int pkCount) {}

    /** Parses {@code sql} for {@code dialect} and converts each detected
     *  {@code CREATE TABLE} into an {@link EntityDefinition}. */
    public List<EntityDefinition> convert(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) return List.of();
        SqlDialect effective = dialect != null ? dialect : SqlDialect.H2;
        List<TableModel> tables = generator.parseTablesForImport(sql, effective);
        // Pass 1: every table in the paste, keyed by bare lower-case name, so pass 2 can turn a
        // foreign key into a relation only when its target is imported too.
        Map<String, ImportedTable> imported = new HashMap<>();
        for (TableModel t : tables) {
            imported.put(tableKey(t.name()),
                    new ImportedTable(singularize(toPascalFromSnake(t.name())), t.pkColumns().size()));
        }
        List<EntityDefinition> result = new ArrayList<>(tables.size());
        for (TableModel t : tables) {
            result.add(toEntity(t, effective, imported));
        }
        return result;
    }

    /** The single read-only view entity parsed from a SELECT, plus an optional
     *  user-facing note when columns were detected heuristically or not at all. */
    public record SelectImportResult(List<EntityDefinition> entities, String note) {}

    /**
     * Parses a single SELECT and converts it into one read-only view
     * {@link EntityDefinition}. A SELECT has no column types, so every field is
     * {@code STRING} (the user refines types in the editor) and the first column
     * is marked the primary key (needed for {@code @Id}). The raw query is kept in
     * {@code viewQuery} so the generated entity maps to a {@code @Subselect} view.
     *
     * <p>For native SQL JSqlParser can't parse, columns are extracted heuristically
     * (see {@link SqlEntityGenerator#parseSelectForImport}); if none can be derived,
     * the entity comes back with no fields for the user to add manually. Either case
     * carries a {@code note} so the UI can prompt the user to review.
     */
    public SelectImportResult convertSelect(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) return new SelectImportResult(List.of(), null);
        SqlDialect effective = dialect != null ? dialect : SqlDialect.H2;
        SqlEntityGenerator.SelectProjection p = generator.parseSelectForImport(sql, effective);

        String entityName = p.fromTable() != null && !p.fromTable().isBlank()
                ? singularize(toPascalFromSnake(p.fromTable()))
                : "View";
        List<FieldDefinition> fields = new ArrayList<>(p.columns().size());
        for (int i = 0; i < p.columns().size(); i++) {
            // Keep the projected label verbatim as the field name: for a @Subselect view the
            // entity's @Column(name=…) must match the column the SELECT actually produces, so we
            // must not snake_case/camelCase it away. Users control naming via SELECT aliases.
            String col = p.columns().get(i);
            fields.add(new FieldDefinition(
                    col,
                    FieldType.STRING,
                    i == 0,   // first column → primary key (user adjusts)
                    false,    // never generated on a view
                    false, false, null, null, null, null, false, List.of(),
                    true, true, // imported fields default to searchable/filterable
                    null, false)); // no custom label; editable
        }
        String note;
        if (fields.isEmpty()) {
            note = "Couldn't auto-detect columns for this query — add the view's fields manually.";
        } else if (p.heuristic()) {
            note = "Columns were detected heuristically (the query isn't standard SQL) — "
                    + "double-check the field names and types.";
        } else {
            note = null;
        }
        EntityDefinition view = new EntityDefinition(
                entityName, null, null, fields, List.of(), true, p.rawSql());
        return new SelectImportResult(List.of(view), note);
    }

    private EntityDefinition toEntity(TableModel table, SqlDialect dialect, Map<String, ImportedTable> imported) {
        String entityName = singularize(toPascalFromSnake(table.name()));
        Map<String, List<String>> enumsByColumn = detectEnumChecks(table.sourceSql());

        // Which FK columns become relations: single-column FKs to an imported, single-key table.
        Map<String, ForeignKey> relationByColumn = new LinkedHashMap<>();
        for (ForeignKey fk : table.foreignKeys()) {
            if (fk.columns().size() != 1) continue;
            ImportedTable target = imported.get(tableKey(fk.referencedTable()));
            if (target == null || target.pkCount() != 1) continue;
            relationByColumn.put(fk.columns().get(0).toLowerCase(Locale.ROOT), fk);
        }

        List<FieldDefinition> fields = new ArrayList<>(table.columns().size());
        List<ColumnModel> fkColumns = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (ColumnModel col : table.columns()) {
            String key = col.name().toLowerCase(Locale.ROOT);
            // A key column that is also an FK (join tables, shared PKs) stays a plain field: the
            // scaffold addresses rows by their PK fields, not by an association.
            if (relationByColumn.containsKey(key) && !col.isPk()) {
                fkColumns.add(col);
                continue;
            }
            FieldDefinition f = toField(col, dialect, enumsByColumn.get(key));
            fields.add(f);
            taken.add(f.name().toLowerCase(Locale.ROOT));
        }
        // Relations after the fields so their names can steer clear of every field name.
        List<RelationDefinition> relations = new ArrayList<>(fkColumns.size());
        for (ColumnModel col : fkColumns) {
            ForeignKey fk = relationByColumn.get(col.name().toLowerCase(Locale.ROOT));
            ImportedTable target = imported.get(tableKey(fk.referencedTable()));
            String fieldName = relationFieldName(col.name(), target.entityName(), taken);
            if (fieldName == null) {
                // No usable name — keep the column as a plain field rather than emit a clash.
                FieldDefinition f = toField(col, dialect, null);
                fields.add(f);
                taken.add(f.name().toLowerCase(Locale.ROOT));
                continue;
            }
            relations.add(new RelationDefinition(RelationType.MANY_TO_ONE, fieldName, target.entityName(), !col.nullable()));
            taken.add(fieldName.toLowerCase(Locale.ROOT));
        }
        return new EntityDefinition(entityName, table.name(), table.schema(), fields, relations,
                false, null, table.sourceSql());
    }

    private FieldDefinition toField(ColumnModel col, SqlDialect dialect, List<String> enumValues) {
        JavaType jt = TypeMappers.map(dialect, col.rawType(), col.precision(), col.scale());
        FieldType type = enumValues != null ? FieldType.ENUM : mapType(jt);
        Integer length = type == FieldType.STRING ? col.precision() : null;
        return new FieldDefinition(
                Naming.toCamelCase(col.name()),
                type,
                col.isPk(),
                col.isAutoIncrement(),
                !col.nullable(),
                col.isUnique(),
                length,
                null,
                null,
                null,
                false,
                enumValues != null ? enumValues : List.of(),
                true, true, // imported fields default to searchable/filterable
                null, false); // no custom label; editable
    }

    /**
     * The association field for an FK column: the column minus its {@code _id}/{@code Id} suffix
     * in camelCase ({@code customer_id} → {@code customer}), falling back to the decapitalized
     * target entity name when that is empty, reserved or already taken. Null when even the
     * fallback collides — the caller then keeps the column as a plain field.
     */
    static String relationFieldName(String column, String targetEntity, Set<String> taken) {
        String lower = column.toLowerCase(Locale.ROOT);
        String base;
        if (lower.equals("id")) {
            base = "";
        } else if (lower.endsWith("_id")) {
            base = column.substring(0, column.length() - 3);
        } else if (column.endsWith("Id") && column.length() > 2 && Character.isLowerCase(column.charAt(column.length() - 3))) {
            base = column.substring(0, column.length() - 2);
        } else {
            base = column;
        }
        String candidate = base.isEmpty() ? "" : Naming.toCamelCase(base);
        if (!usable(candidate, taken)) {
            candidate = Naming.decapitalize(targetEntity);
            if (!usable(candidate, taken)) return null;
        }
        return candidate;
    }

    private static boolean usable(String name, Set<String> taken) {
        return name != null && !name.isEmpty()
                && FullstackRequestValidator.isValidJavaIdentifier(name)
                && !FullstackRequestValidator.RESERVED_JAVA_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))
                && !taken.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Every {@code CHECK (col IN (...))} in the statement, keyed by lower-case column name, with
     * the listed values turned into enum constants. A column whose values can't all be made
     * distinct Java constants is left out (it stays a plain string).
     */
    static Map<String, List<String>> detectEnumChecks(String sourceSql) {
        Map<String, List<String>> out = new HashMap<>();
        if (sourceSql == null || sourceSql.isBlank()) return out;
        Matcher m = CHECK_IN.matcher(sourceSql);
        while (m.find()) {
            String column = m.group(1).toLowerCase(Locale.ROOT);
            List<String> constants = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean ok = true;
            for (String raw : m.group(2).split(",")) {
                String constant = toEnumConstant(unquote(raw.trim()));
                if (constant == null || !seen.add(constant)) {
                    ok = false;
                    break;
                }
                constants.add(constant);
            }
            if (ok && !constants.isEmpty()) out.put(column, constants);
        }
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\"")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** {@code in progress} → {@code IN_PROGRESS}; {@code 2fa} → {@code _2FA}; null when nothing
     *  identifier-like remains (e.g. an empty string or only punctuation). */
    static String toEnumConstant(String value) {
        String s = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (s.isEmpty() || s.chars().allMatch(c -> c == '_')) return null;
        if (Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }

    /** Bare, unquoted, lower-case table name — {@code inv."Orders"} → {@code orders}. */
    private static String tableKey(String name) {
        if (name == null) return "";
        String n = name.trim();
        int dot = n.lastIndexOf('.');
        if (dot >= 0) n = n.substring(dot + 1);
        n = n.replaceAll("^[\"`\\[]|[\"`\\]]$", "");
        return n.toLowerCase(Locale.ROOT);
    }

    /** Maps a resolved Java type back onto the fullstack {@link FieldType} enum.
     *  Lossy: {@code Short} → INTEGER, {@code Float}/{@code Double} → BIG_DECIMAL,
     *  {@code byte[]}/{@code UUID}/unknown → STRING. */
    static FieldType mapType(JavaType jt) {
        return switch (jt.simpleName()) {
            case "String" -> FieldType.STRING;
            case "Long" -> FieldType.LONG;
            case "Integer", "Short" -> FieldType.INTEGER;
            case "Boolean" -> FieldType.BOOLEAN;
            case "LocalDate" -> FieldType.LOCAL_DATE;
            case "LocalDateTime", "OffsetDateTime", "LocalTime" -> FieldType.LOCAL_DATE_TIME;
            case "BigDecimal", "Float", "Double" -> FieldType.BIG_DECIMAL;
            default -> FieldType.STRING;
        };
    }

    /** {@code order_items} → {@code OrderItems}. Leaves CamelCase input alone. */
    static String toPascalFromSnake(String tableName) {
        if (tableName == null || tableName.isEmpty()) return "";
        return Naming.toPascalCase(tableName);
    }

    /** Best-effort English singularization for table names — mirrors the
     *  pluralization rules in {@link Naming#pluralize(String)} run in reverse. */
    static String singularize(String s) {
        if (s == null || s.isEmpty()) return s;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ies") && s.length() > 3) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (lower.endsWith("ses") || lower.endsWith("xes") || lower.endsWith("zes")
                || lower.endsWith("ches") || lower.endsWith("shes")) {
            return s.substring(0, s.length() - 2);
        }
        if (lower.endsWith("s") && !lower.endsWith("ss") && !lower.endsWith("us")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
