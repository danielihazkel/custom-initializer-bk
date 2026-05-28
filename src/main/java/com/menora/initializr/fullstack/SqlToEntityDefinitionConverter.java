package com.menora.initializr.fullstack;

import com.menora.initializr.sql.ColumnModel;
import com.menora.initializr.sql.JavaType;
import com.menora.initializr.sql.SqlDialect;
import com.menora.initializr.sql.SqlEntityGenerator;
import com.menora.initializr.sql.TableModel;
import com.menora.initializr.sql.TypeMappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 *   <li>Column-level {@code UNIQUE} constraints are not currently in {@link ColumnModel},
 *       so {@code unique} starts as {@code false}.</li>
 *   <li>{@code CHECK col IN ('A','B')} is not parsed — users wanting an enum
 *       switch the type after import.</li>
 * </ul>
 */
@Component
public class SqlToEntityDefinitionConverter {

    private final SqlEntityGenerator generator;

    public SqlToEntityDefinitionConverter(SqlEntityGenerator generator) {
        this.generator = generator;
    }

    /** Parses {@code sql} for {@code dialect} and converts each detected
     *  {@code CREATE TABLE} into an {@link EntityDefinition}. */
    public List<EntityDefinition> convert(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) return List.of();
        SqlDialect effective = dialect != null ? dialect : SqlDialect.H2;
        List<TableModel> tables = generator.parseTablesForImport(sql, effective);
        List<EntityDefinition> result = new ArrayList<>(tables.size());
        for (TableModel t : tables) {
            result.add(toEntity(t, effective));
        }
        return result;
    }

    private EntityDefinition toEntity(TableModel table, SqlDialect dialect) {
        String entityName = singularize(toPascalFromSnake(table.name()));
        List<FieldDefinition> fields = new ArrayList<>(table.columns().size());
        for (ColumnModel col : table.columns()) {
            fields.add(toField(col, dialect));
        }
        return new EntityDefinition(entityName, table.name(), fields);
    }

    private FieldDefinition toField(ColumnModel col, SqlDialect dialect) {
        JavaType jt = TypeMappers.map(dialect, col.rawType(), col.precision(), col.scale());
        FieldType type = mapType(jt);
        Integer length = type == FieldType.STRING ? col.precision() : null;
        return new FieldDefinition(
                Naming.toCamelCase(col.name()),
                type,
                col.isPk(),
                col.isAutoIncrement(),
                !col.nullable(),
                false,
                length,
                List.of());
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
