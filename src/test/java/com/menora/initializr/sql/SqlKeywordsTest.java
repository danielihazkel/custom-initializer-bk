package com.menora.initializr.sql;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sql/reserved-words.txt} against a database that is here to ask: H2. Every word any list
 * holds, and the names models commonly use, is tried as a bare column name; one H2 refuses must
 * be on H2's list (or a project with that name would not start), and every one must work quoted
 * in upper case — the form {@link SqlNames#quoteFolded} writes for H2.
 */
class SqlKeywordsTest {

    private static final List<String> COMMON = List.of("name", "title", "status", "type", "comment", "date", "level",
            "size", "file", "number", "count", "total", "amount", "description", "code", "state", "position", "index",
            "start", "end", "month", "year", "day", "hour", "minute", "second", "range", "limit", "offset", "row",
            "rows", "group", "groups", "order", "user", "value", "values", "key", "table", "column", "check", "default");

    @Test
    void everyWordH2RefusesBareIsOnItsListAndWorksQuoted() throws Exception {
        Set<String> candidates = new TreeSet<>();
        for (SqlDialect dialect : SqlDialect.values()) candidates.addAll(SqlKeywords.words(dialect));
        COMMON.forEach(word -> candidates.add(word.toUpperCase()));

        List<String> missing = new ArrayList<>();
        List<String> brokenQuoted = new ArrayList<>();
        try (Connection h2 = DriverManager.getConnection("jdbc:h2:mem:keywords;DB_CLOSE_DELAY=-1")) {
            int n = 0;
            for (String word : candidates) {
                // A name with a space is not a name a model can have.
                if (!word.matches("[A-Z_][A-Z0-9_]*")) continue;
                boolean bare = creates(h2, "create table kw_bare_" + (n++) + " (id int, " + word.toLowerCase() + " int)");
                if (!bare && !SqlKeywords.reserved(word, SqlDialect.H2) && !SqlKeywords.systemColumn(word, SqlDialect.H2)) {
                    missing.add(word);
                }
                if (SqlKeywords.systemColumn(word, SqlDialect.H2)) continue;
                String quoted = SqlNames.quoteFolded(word.toLowerCase(), SqlDialect.H2);
                assertThat(quoted).isEqualTo("\"" + word + "\"");
                if (!creates(h2, "create table kw_quoted_" + n + " (id int, " + quoted + " int)")) brokenQuoted.add(word);
            }
        }
        assertThat(missing).as("words H2 refuses as a bare column name that [h2] in sql/reserved-words.txt lacks").isEmpty();
        assertThat(brokenQuoted).as("words that do not work even quoted").isEmpty();
    }

    @Test
    void aKeywordIsQuotedInTheCaseItsDatabaseFoldsTo() {
        assertThat(SqlNames.quoteFolded("order", SqlDialect.H2)).isEqualTo("\"ORDER\"");
        assertThat(SqlNames.quoteFolded("order", SqlDialect.ORACLE)).isEqualTo("\"ORDER\"");
        assertThat(SqlNames.quoteFolded("order", SqlDialect.DB2)).isEqualTo("\"ORDER\"");
        assertThat(SqlNames.quoteFolded("order", SqlDialect.POSTGRESQL)).isEqualTo("\"order\"");
        assertThat(SqlNames.quoteFolded("order", SqlDialect.MSSQL)).isEqualTo("[order]");
        // Already quoted: as the model wrote it.
        assertThat(SqlNames.quoteFolded("\"Order\"", SqlDialect.H2)).isEqualTo("\"Order\"");
    }

    @Test
    void eachDatabaseAnswersForItsOwnWordsAndOneWithoutAListForAll() {
        assertThat(SqlKeywords.reserved("value", SqlDialect.H2)).isTrue();
        assertThat(SqlKeywords.reserved("VALUE", SqlDialect.POSTGRESQL)).isFalse();
        assertThat(SqlKeywords.reserved("set", SqlDialect.POSTGRESQL)).isTrue();
        assertThat(SqlKeywords.reserved("comment", SqlDialect.ORACLE)).isTrue();
        assertThat(SqlKeywords.reserved("comment", SqlDialect.H2)).isFalse();
        assertThat(SqlKeywords.reserved("order", null)).isFalse();
        // DB2 has no list of its own: it takes every word listed.
        assertThat(SqlKeywords.reserved("comment", SqlDialect.DB2)).isTrue();
        assertThat(SqlKeywords.reserved("value", SqlDialect.DB2)).isTrue();
        assertThat(SqlKeywords.systemColumn("xmin", SqlDialect.POSTGRESQL)).isTrue();
        assertThat(SqlKeywords.systemColumn("xmin", SqlDialect.H2)).isFalse();
    }

    @Test
    void aQuotedKeywordInPastedDdlKeepsItsQuotes() {
        // "order" on PostgreSQL is what the database stores for the bare name — but bare it is no name.
        assertThat(SqlNames.physical("order", true, SqlDialect.POSTGRESQL)).isEqualTo("\"order\"");
        assertThat(SqlNames.physical("cust_no", true, SqlDialect.POSTGRESQL)).isEqualTo("cust_no");
    }

    /**
     * A project on another database runs its own tests on H2. A name its database takes bare is
     * written bare — so, for those tests, the H2 connection is told that the word is no keyword
     * ({@code NON_KEYWORDS}). That has to work for every word H2 reserves and some other database
     * does not: a column of that name created, written, read through an alias, filtered and sorted.
     */
    @Test
    void h2CanBeToldThatAWordAnotherDatabaseTakesBareIsNoKeyword() throws Exception {
        List<String> stuck = new ArrayList<>();
        int n = 0;
        for (String word : SqlKeywords.words(SqlDialect.H2)) {
            boolean bareElsewhere = !SqlKeywords.reserved(word, SqlDialect.POSTGRESQL)
                    || !SqlKeywords.reserved(word, SqlDialect.ORACLE) || !SqlKeywords.reserved(word, SqlDialect.MSSQL);
            if (!bareElsewhere) continue;
            String name = word.toLowerCase();
            try (Connection h2 = DriverManager.getConnection("jdbc:h2:mem:nonkeyword" + (n++) + ";NON_KEYWORDS=" + word)) {
                // As a column and as a table: created, written, read through an alias, filtered, sorted, updated.
                boolean works = creates(h2, "create table " + name + " (id int, " + name + " int)")
                        && creates(h2, "insert into " + name + " (id, " + name + ") values (1, 2)")
                        && creates(h2, "select a.id, a." + name + " from " + name + " a where a." + name + " = 2 order by a." + name)
                        && creates(h2, "update " + name + " set " + name + " = 3 where id = 1");
                if (!works) stuck.add(word);
            }
        }
        assertThat(stuck).as("H2 keywords another database takes bare that NON_KEYWORDS does not free: list them for every database").isEmpty();
    }

    private static boolean creates(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return true;
        } catch (SQLException refused) {
            return false;
        }
    }
}
