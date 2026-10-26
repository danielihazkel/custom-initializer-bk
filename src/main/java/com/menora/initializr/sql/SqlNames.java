package com.menora.initializr.sql;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a table/schema/column name from pasted DDL into the name the generated JPA code must carry,
 * shared by the SQL wizard and the fullstack scaffolder.
 *
 * <p>Spring Boot's default physical naming strategy ({@code CamelCaseToUnderscoresNamingStrategy})
 * rewrites <em>every</em> name before it reaches the database — explicit {@code @Column}/
 * {@code @Table} names and quoted ones included: it puts an underscore into each lower-UPPER-lower
 * hump and lower-cases the result ({@code orderDate} → {@code order_date}, {@code "OrderDate"} →
 * {@code "order_date"}). Native SQL ({@code @Formula}, {@code @SQLDelete}, native queries) is not
 * rewritten. So:
 * <ul>
 *   <li>An <b>unquoted</b> name the strategy would split is rendered in a form it leaves alone: upper
 *       case where the database folds unquoted names anyway (DB2, Oracle, H2 — and PostgreSQL, whose
 *       lower-case fold the strategy's own lower-casing reproduces), quoted on MySQL (table names are
 *       case-sensitive there, so any upper-case letter counts) and SQL Server, which keep names as
 *       written.</li>
 *   <li>A <b>quoted</b> name keeps its quotes in the dialect's style, unless it is exactly what the
 *       database would store for the unquoted name ({@code "ORD_NO"} on DB2, {@code "cust_no"} on
 *       PostgreSQL) — quoting-everything exports then render as plain names.</li>
 * </ul>
 * A project with a quoted name also needs {@link #namingStrategySource}, a strategy that passes
 * quoted names through untouched; without it they would still be lower-cased and split.
 */
public final class SqlNames {

    private SqlNames() {}

    private static final Pattern UPPER_PLAIN = Pattern.compile("[A-Z_][A-Z0-9_$#@]*");
    private static final Pattern LOWER_PLAIN = Pattern.compile("[a-z_][a-z0-9_$]*");

    /** The rendered form of a DDL name: {@code name} is the bare text, {@code quoted} whether the
     *  DDL quoted it; {@code dialect} may be null (treated like the upper-folding databases). */
    public static String physical(String name, boolean quoted, SqlDialect dialect) {
        if (name == null || name.isEmpty()) return name;
        if (quoted) {
            return plain(name, dialect) ? name : quote(name, dialect);
        }
        if (dialect == SqlDialect.MYSQL && !name.equals(name.toLowerCase(Locale.ROOT))) {
            return quote(name, dialect);
        }
        if (!strategySplits(name)) return name;
        return dialect == SqlDialect.MSSQL || dialect == SqlDialect.MYSQL
                ? quote(name, dialect)
                : name.toUpperCase(Locale.ROOT);
    }

    /** The rendered form of an already stored name (the fullstack {@code column}/{@code tableName}):
     *  a quoted one is final, a bare one goes through {@link #physical(String, boolean, SqlDialect)}. */
    public static String physical(String stored, SqlDialect dialect) {
        if (stored == null || stored.isEmpty() || isQuoted(stored)) return stored;
        return physical(stored, false, dialect);
    }

    /** True for {@code "x"}, {@code `x`} and {@code [x]} — the three styles Hibernate also reads. */
    public static boolean isQuoted(String s) {
        if (s == null || s.length() < 2) return false;
        char first = s.charAt(0), last = s.charAt(s.length() - 1);
        return (first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']');
    }

    /** {@code "x"}/{@code `x`}/{@code [x]} → {@code x}; anything else unchanged. */
    public static String unquote(String s) {
        return isQuoted(s) ? s.substring(1, s.length() - 1) : s;
    }

    /** {@code name} as the body of a Java string literal ({@code "OrderDate"} → {@code \"OrderDate\"}). */
    public static String javaLiteral(String name) {
        return name == null ? null : name.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Mirrors {@code CamelCaseToUnderscoresNamingStrategy.isUnderscoreRequired}: a lower-case
     *  letter, then an upper-case one, then a lower-case one. */
    static boolean strategySplits(String name) {
        for (int i = 1; i < name.length() - 1; i++) {
            if (Character.isLowerCase(name.charAt(i - 1)) && Character.isUpperCase(name.charAt(i))
                    && Character.isLowerCase(name.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code name} is exactly what the database stores for it unquoted. */
    private static boolean plain(String name, SqlDialect dialect) {
        boolean lowerFold = dialect == SqlDialect.POSTGRESQL || dialect == SqlDialect.MYSQL
                || dialect == SqlDialect.MSSQL;
        return (lowerFold ? LOWER_PLAIN : UPPER_PLAIN).matcher(name).matches();
    }

    private static String quote(String name, SqlDialect dialect) {
        if (dialect == SqlDialect.MYSQL) return "`" + name + "`";
        if (dialect == SqlDialect.MSSQL) return "[" + name + "]";
        return "\"" + name + "\"";
    }

    /** Where {@link #namingStrategySource} lands in a generated project. */
    public static final String NAMING_STRATEGY_PATH = "src/main/java/{{packagePath}}/config/QuotedNamesNamingStrategy.java";

    /**
     * Source of the generated project's physical naming strategy: Spring Boot's default for every
     * plain name (so nothing else in the project changes), but a quoted name — which the default
     * would lower-case and split — is passed through as written. Boot picks a
     * {@code PhysicalNamingStrategy} bean up on its own, so no property is needed.
     */
    public static String namingStrategySource(String packageName) {
        return """
                package %s.config;

                import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
                import org.hibernate.boot.model.naming.Identifier;
                import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
                import org.springframework.stereotype.Component;

                /**
                 * Spring Boot's naming strategy, except that a quoted table/column name (one the schema
                 * spells with exact case, e.g. {@code "OrderDate"}) reaches the database as written instead
                 * of being lower-cased and split into {@code order_date}.
                 */
                @Component
                public class QuotedNamesNamingStrategy extends CamelCaseToUnderscoresNamingStrategy {

                    @Override
                    public Identifier toPhysicalCatalogName(Identifier name, JdbcEnvironment env) {
                        return isQuoted(name) ? name : super.toPhysicalCatalogName(name, env);
                    }

                    @Override
                    public Identifier toPhysicalSchemaName(Identifier name, JdbcEnvironment env) {
                        return isQuoted(name) ? name : super.toPhysicalSchemaName(name, env);
                    }

                    @Override
                    public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment env) {
                        return isQuoted(name) ? name : super.toPhysicalTableName(name, env);
                    }

                    @Override
                    public Identifier toPhysicalSequenceName(Identifier name, JdbcEnvironment env) {
                        return isQuoted(name) ? name : super.toPhysicalSequenceName(name, env);
                    }

                    @Override
                    public Identifier toPhysicalColumnName(Identifier name, JdbcEnvironment env) {
                        return isQuoted(name) ? name : super.toPhysicalColumnName(name, env);
                    }

                    private static boolean isQuoted(Identifier name) {
                        return name != null && name.isQuoted();
                    }
                }
                """.formatted(packageName);
    }
}
