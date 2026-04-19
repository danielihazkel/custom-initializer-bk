package com.menora.initializr.sql;

import java.util.Map;

/**
 * Supported SQL dialects for the CREATE TABLE → JPA entity wizard. Each value
 * is bound to the dependency id of the JDBC driver in the catalog.
 *
 * <p>MYSQL is wired in for future use but only advertised by
 * {@code /metadata/sql-dialects} once a corresponding dep is seeded.
 */
public enum SqlDialect {
    POSTGRESQL("postgresql"),
    H2("h2"),
    MSSQL("mssql"),
    ORACLE("oracle"),
    DB2("db2"),
    MYSQL("mysql");

    private static final Map<String, SqlDialect> BY_DEP_ID = Map.of(
            "postgresql", POSTGRESQL,
            "h2", H2,
            "mssql", MSSQL,
            "oracle", ORACLE,
            "db2", DB2,
            "mysql", MYSQL);

    private final String depId;

    SqlDialect(String depId) {
        this.depId = depId;
    }

    public String depId() {
        return depId;
    }

    public static SqlDialect forDepId(String depId) {
        return depId == null ? null : BY_DEP_ID.get(depId);
    }
}
