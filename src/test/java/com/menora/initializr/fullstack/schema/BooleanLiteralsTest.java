package com.menora.initializr.fullstack.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The soft-delete SQL a generated project carries says true and false as its database does
 * ({@link SchemaDialect#flag}, {@link SchemaDialect#flagIs}): 1 and 0 on SQL Server and Oracle,
 * where the words are no literals. The project's own tests run that same SQL on H2 against a
 * boolean column — so H2 has to read every spelling. ({@code LocalRunDockerSmokeTests} runs it
 * on the real databases.)
 */
class BooleanLiteralsTest {

    @Test
    void h2ReadsEveryDatabasesSpellingAgainstABooleanColumn() throws Exception {
        try (Connection h2 = DriverManager.getConnection("jdbc:h2:mem:literals");
             Statement sql = h2.createStatement()) {
            sql.execute("create table t (id int primary key, deleted boolean not null)");
            sql.execute("insert into t values (1, false), (2, false)");
            for (SchemaDialect dialect : SchemaDialect.values()) {
                // What @SQLDelete, the restore and @SQLRestriction say.
                assertThat(sql.executeUpdate("update t set deleted = " + dialect.flag(true) + " where id = 1")).as(dialect.name()).isEqualTo(1);
                assertThat(count(sql, dialect.flagIs("deleted", false))).as(dialect.name()).isEqualTo(1);
                assertThat(count(sql, dialect.flagIs("deleted", true))).as(dialect.name()).isEqualTo(1);
                assertThat(sql.executeUpdate("update t set deleted = " + dialect.flag(false) + " where id = 1")).as(dialect.name()).isEqualTo(1);
                assertThat(count(sql, dialect.flagIs("deleted", false))).as(dialect.name()).isEqualTo(2);
            }
        }
    }

    @Test
    void theWordsWhereTheDatabaseHasThemAndOneAndZeroWhereItDoesNot() {
        assertThat(SchemaDialect.H2.flag(true)).isEqualTo("true");
        assertThat(SchemaDialect.POSTGRESQL.flag(false)).isEqualTo("false");
        assertThat(SchemaDialect.MSSQL.flag(true)).isEqualTo("1");
        assertThat(SchemaDialect.MSSQL.flag(false)).isEqualTo("0");
        assertThat(SchemaDialect.ORACLE.flag(true)).isEqualTo("1");
        assertThat(SchemaDialect.H2.flagIs("deleted", false)).isEqualTo("deleted = false");
        // H2 compares a boolean with neither a number nor a text: the column is read as a number.
        assertThat(SchemaDialect.MSSQL.flagIs("deleted", false)).isEqualTo("cast(deleted as integer) = 0");
        assertThat(SchemaDialect.ORACLE.flagIs("deleted", true)).isEqualTo("cast(deleted as integer) = 1");
    }

    private static int count(Statement sql, String where) throws Exception {
        try (ResultSet rows = sql.executeQuery("select count(*) from t where " + where)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
