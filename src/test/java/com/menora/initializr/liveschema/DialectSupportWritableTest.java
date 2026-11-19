package com.menora.initializr.liveschema;

import com.menora.initializr.sql.SqlDialect;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connection that creates a model's tables leaves out each driver's read-only switch, and a
 * read keeps it — the only difference between the two.
 */
class DialectSupportWritableTest {

    private static ConnectionSpec spec(SqlDialect dialect) {
        return new ConnectionSpec(dialect, "db.example", null, "app", null, "user", "secret", false, "test");
    }

    @Test
    void postgresqlDropsReadOnlyOnlyForAWrite() {
        DialectSupport pg = DialectSupport.of(SqlDialect.POSTGRESQL);
        assertThat(pg.properties(spec(SqlDialect.POSTGRESQL), true)).containsEntry("readOnly", "true");
        Properties write = pg.properties(spec(SqlDialect.POSTGRESQL), false);
        assertThat(write).doesNotContainKey("readOnly").containsEntry("user", "user").containsKey("socketTimeout");
        assertThat(pg.url(spec(SqlDialect.POSTGRESQL), false)).isEqualTo(pg.url(spec(SqlDialect.POSTGRESQL)));
    }

    @Test
    void sqlServerDropsTheReadOnlyIntentOnlyForAWrite() {
        DialectSupport ms = DialectSupport.of(SqlDialect.MSSQL);
        assertThat(ms.url(spec(SqlDialect.MSSQL), true)).contains(";applicationIntent=ReadOnly");
        assertThat(ms.url(spec(SqlDialect.MSSQL), false))
                .doesNotContain("applicationIntent")
                .contains(";databaseName=app;encrypt=true")
                .contains(";loginTimeout=");
    }

    @Test
    void db2DropsReadOnlyAccessOnlyForAWrite() {
        DialectSupport db2 = DialectSupport.of(SqlDialect.DB2);
        assertThat(db2.properties(spec(SqlDialect.DB2), true)).containsEntry("access", "read only");
        assertThat(db2.properties(spec(SqlDialect.DB2), false)).doesNotContainKey("access").containsEntry("naming", "sql");
    }

    @Test
    void oracleHasNoDriverSwitchToDrop() {
        DialectSupport ora = DialectSupport.of(SqlDialect.ORACLE);
        assertThat(ora.properties(spec(SqlDialect.ORACLE), false)).isEqualTo(ora.properties(spec(SqlDialect.ORACLE), true));
    }
}
