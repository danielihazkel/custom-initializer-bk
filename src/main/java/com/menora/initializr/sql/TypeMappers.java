package com.menora.initializr.sql;

/**
 * Dialect-aware SQL-type → Java-type resolver. Each dialect branch handles its
 * specifics first (e.g. {@code SERIAL}, {@code UUID}, {@code NVARCHAR}); on
 * miss, it falls through to {@link #mapCommon}.
 *
 * <p>The input {@code rawType} is upper-cased; {@code precision} and
 * {@code scale} may be {@code null} when the DDL omits them.
 */
public final class TypeMappers {

    private TypeMappers() {}

    public static JavaType map(SqlDialect dialect, String rawType, Integer precision, Integer scale) {
        String t = rawType == null ? "" : rawType.trim().toUpperCase();
        JavaType specific = switch (dialect) {
            case POSTGRESQL -> mapPostgresql(t);
            case MYSQL -> mapMysql(t, precision);
            case H2 -> mapH2(t);
            case MSSQL -> mapMssql(t);
            case ORACLE -> mapOracle(t, precision, scale);
            case DB2 -> mapDb2(t);
        };
        if (specific != null) {
            return specific;
        }
        JavaType common = mapCommon(t);
        return common != null ? common : JavaType.langType("String");
    }

    // ── Dialect specifics ─────────────────────────────────────────────────────

    private static JavaType mapPostgresql(String t) {
        return switch (t) {
            case "SERIAL" -> JavaType.langType("Integer");
            case "BIGSERIAL" -> JavaType.langType("Long");
            case "SMALLSERIAL" -> JavaType.langType("Short");
            case "UUID" -> JavaType.of("UUID", "java.util.UUID");
            case "BYTEA" -> JavaType.langType("byte[]");
            case "JSON", "JSONB" -> JavaType.langType("String");
            case "TEXT" -> JavaType.langType("String");
            case "TIMESTAMPTZ" -> JavaType.of("OffsetDateTime", "java.time.OffsetDateTime");
            default -> null;
        };
    }

    private static JavaType mapMysql(String t, Integer precision) {
        return switch (t) {
            case "TINYINT" -> (precision != null && precision == 1)
                    ? JavaType.langType("Boolean")
                    : JavaType.langType("Short");
            case "DATETIME" -> JavaType.of("LocalDateTime", "java.time.LocalDateTime");
            case "MEDIUMTEXT", "LONGTEXT", "TINYTEXT" -> JavaType.langType("String");
            case "MEDIUMBLOB", "LONGBLOB", "TINYBLOB" -> JavaType.langType("byte[]");
            default -> null;
        };
    }

    private static JavaType mapH2(String t) {
        return switch (t) {
            case "UUID" -> JavaType.of("UUID", "java.util.UUID");
            case "IDENTITY" -> JavaType.langType("Long");
            default -> null;
        };
    }

    private static JavaType mapMssql(String t) {
        return switch (t) {
            case "NVARCHAR", "NCHAR", "NTEXT" -> JavaType.langType("String");
            case "UNIQUEIDENTIFIER" -> JavaType.of("UUID", "java.util.UUID");
            case "BIT" -> JavaType.langType("Boolean");
            case "DATETIME2", "DATETIME", "SMALLDATETIME" -> JavaType.of("LocalDateTime", "java.time.LocalDateTime");
            case "DATETIMEOFFSET" -> JavaType.of("OffsetDateTime", "java.time.OffsetDateTime");
            case "MONEY", "SMALLMONEY" -> JavaType.of("BigDecimal", "java.math.BigDecimal");
            default -> null;
        };
    }

    private static JavaType mapOracle(String t, Integer precision, Integer scale) {
        return switch (t) {
            case "VARCHAR2", "NVARCHAR2", "CLOB", "NCLOB" -> JavaType.langType("String");
            case "NUMBER" -> {
                if (scale != null && scale > 0) {
                    yield JavaType.of("BigDecimal", "java.math.BigDecimal");
                }
                if (precision == null) {
                    yield JavaType.of("BigDecimal", "java.math.BigDecimal");
                }
                if (precision <= 9) yield JavaType.langType("Integer");
                if (precision <= 18) yield JavaType.langType("Long");
                yield JavaType.of("BigDecimal", "java.math.BigDecimal");
            }
            case "BINARY_FLOAT" -> JavaType.langType("Float");
            case "BINARY_DOUBLE" -> JavaType.langType("Double");
            case "RAW", "LONG RAW", "BLOB" -> JavaType.langType("byte[]");
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE" ->
                    JavaType.of("OffsetDateTime", "java.time.OffsetDateTime");
            default -> null;
        };
    }

    private static JavaType mapDb2(String t) {
        return switch (t) {
            case "TIMESTAMP" -> JavaType.of("LocalDateTime", "java.time.LocalDateTime");
            case "GRAPHIC", "VARGRAPHIC" -> JavaType.langType("String");
            case "CLOB", "DBCLOB" -> JavaType.langType("String");
            case "BLOB" -> JavaType.langType("byte[]");
            default -> null;
        };
    }

    // ── Common fallthrough ────────────────────────────────────────────────────

    private static JavaType mapCommon(String t) {
        return switch (t) {
            case "VARCHAR", "CHAR", "CHARACTER", "CHARACTER VARYING", "TEXT", "STRING" -> JavaType.langType("String");
            case "INT", "INTEGER", "INT4" -> JavaType.langType("Integer");
            case "BIGINT", "INT8" -> JavaType.langType("Long");
            case "SMALLINT", "INT2" -> JavaType.langType("Short");
            case "TINYINT" -> JavaType.langType("Short");
            case "BOOLEAN", "BOOL" -> JavaType.langType("Boolean");
            case "REAL", "FLOAT4" -> JavaType.langType("Float");
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> JavaType.langType("Double");
            case "FLOAT" -> JavaType.langType("Double");
            case "NUMERIC", "DECIMAL", "DEC" -> JavaType.of("BigDecimal", "java.math.BigDecimal");
            case "DATE" -> JavaType.of("LocalDate", "java.time.LocalDate");
            case "TIME" -> JavaType.of("LocalTime", "java.time.LocalTime");
            case "TIMESTAMP", "DATETIME" -> JavaType.of("LocalDateTime", "java.time.LocalDateTime");
            case "BLOB", "VARBINARY", "BINARY", "BYTEA" -> JavaType.langType("byte[]");
            default -> null;
        };
    }
}
