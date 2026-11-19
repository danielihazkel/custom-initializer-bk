package com.menora.initializr.sql;

import com.menora.initializr.gen.WizardMemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.menora.initializr.sql.SqlSelectReader.*;
import static com.menora.initializr.sql.SqlScriptParser.*;
import static com.menora.initializr.sql.SqlEntityRenderer.*;
import static com.menora.initializr.sql.SqlRestRenderer.*;
import static com.menora.initializr.sql.SqlJavaNames.*;

/**
 * Parses CREATE TABLE DDL via JSqlParser and emits JPA entity (and optionally
 * Spring Data repository) source files. One {@code SqlEntityGenerator} call
 * handles one dialect's worth of DDL for a single project.
 */
@Service
public class SqlEntityGenerator {

    static final Logger log = LoggerFactory.getLogger(SqlEntityGenerator.class);

    /** Parses {@code sql} for the declared {@code dialect} and returns all
     *  generated files keyed under {@code src/main/java/{{packagePath}}/...}. */
    public List<GeneratedJavaFile> generate(String sql, SqlDialect dialect,
                                            String packageName, SqlDepOptions options) {
        return generate(sql, dialect, packageName, options, SqlProjectFacts.NONE);
    }

    /**
     * As above, knowing what is on the project's build: with the validation starter the DTOs (or,
     * exposing entities directly, the entities) carry Bean Validation and the controllers
     * {@code @Valid}; with Flyway the DDL becomes the first migration.
     */
    public List<GeneratedJavaFile> generate(String sql, SqlDialect dialect, String packageName,
                                            SqlDepOptions options, SqlProjectFacts projectFacts) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        SqlProjectFacts facts = projectFacts == null ? SqlProjectFacts.NONE : projectFacts;
        SqlDepOptions opts = options != null ? options : new SqlDepOptions("entity", List.of());
        // A wizard request checks its script by generating it, then writes what the same inputs give.
        return WizardMemo.get(WizardMemo.Key.of("sql", sql, dialect, packageName, opts, facts),
                () -> render(sql, dialect, packageName, opts, facts));
    }

    private List<GeneratedJavaFile> render(String sql, SqlDialect dialect, String packageName,
                                           SqlDepOptions opts, SqlProjectFacts facts) {
        List<TableModel> tables = parseTables(sql, dialect);
        if (tables.isEmpty()) {
            log.warn("SQL parse produced no CREATE TABLE statements for dialect {}", dialect);
            return List.of();
        }

        Set<String> knownTableNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, TableModel> tablesByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TableModel t : tables) {
            knownTableNames.add(t.name());
            tablesByName.put(t.name(), t);
        }

        List<GeneratedJavaFile> files = new ArrayList<>();
        if (tables.stream().anyMatch(t -> rendersQuotedName(t, dialect))) {
            files.add(new GeneratedJavaFile(SqlNames.NAMING_STRATEGY_PATH, SqlNames.namingStrategySource(packageName)));
        }
        for (TableModel table : tables) {
            files.add(renderEntity(table, dialect, packageName, opts, knownTableNames, facts));
            files.addAll(renderEnums(table, dialect, packageName, opts, knownTableNames));
            if (table.hasCompositePk()) {
                files.add(renderIdClass(table, dialect, packageName, opts));
            }
            // A generated REST stack needs a repository even if the per-table
            // toggle is off — and only makes sense for an addressable (PK) table.
            boolean apiOn = opts.apiMode().generatesApi() && !table.pkColumns().isEmpty();
            // …and a repository only for an entity: a table without a key is not one (see the entity template).
            if ((opts.generateRepositoryFor(table.name()) || apiOn) && !table.pkColumns().isEmpty()) {
                files.add(renderRepository(table, dialect, packageName, opts));
            }
            if (opts.apiMode().generatesApi() && table.pkColumns().isEmpty()) {
                log.debug("Skipping REST stack for table {} — no primary key to address rows by", table.name());
            }
            if (apiOn) {
                files.add(renderService(table, dialect, packageName, opts, tablesByName, knownTableNames));
                if (opts.apiMode().generatesDto()) {
                    files.add(renderDto(table, dialect, packageName, opts, tablesByName, knownTableNames, facts));
                }
                if (opts.apiMode() == SqlApiMode.MAPSTRUCT_DTO) {
                    files.add(renderMapper(table, dialect, packageName, opts, tablesByName, knownTableNames));
                }
                files.add(renderController(table, dialect, packageName, opts, tablesByName, knownTableNames, facts));
                if (opts.generateTests()) {
                    files.add(renderControllerTest(table, packageName, opts, facts));
                }
            }
        }
        // With Flyway on the build, the DDL the project was generated from is its first migration.
        if (facts.flyway() && opts.flywayBaselineOrDefault()) {
            String baseline = baselineMigration(sql);
            if (baseline != null) files.add(new GeneratedJavaFile(FLYWAY_BASELINE_PATH, baseline));
        }
        return files;
    }

    /** Where the Flyway dependency's placeholder migration is: the baseline replaces it. */
    public static final String FLYWAY_BASELINE_PATH = "src/main/resources/db/migration/V1__init.sql";

    /**
     * The pasted script as a migration: every statement but the SELECTs (those became view
     * entities, and a migration that only reads does nothing), as it was written. Null when
     * nothing is left.
     */
    static String baselineMigration(String sql) {
        StringBuilder out = new StringBuilder();
        for (String raw : splitStatements(sql)) {
            String bare = stripComments(raw).trim();
            if (bare.isEmpty() || SELECT_LEAD.matcher(bare).find()) continue;
            out.append(raw.strip()).append(";\n\n");
        }
        if (out.length() == 0) return null;
        return """
                -- Baseline: the schema this project was generated from (the DDL given to the SQL wizard).
                --
                -- On a database that already has these tables Flyway does not run it:
                -- spring.flyway.baseline-on-migrate marks an existing schema as version 1.
                -- On an empty one (a new environment, a test database) it creates them.
                -- Later changes go in V2__describe_change.sql, V3__…

                """ + out.toString().stripTrailing() + "\n";
    }

    /** Exposed for lightweight preview and up-front validation — just returns
     *  detected table names (also throws {@link SqlParseException} if the DDL
     *  cannot be parsed, so controllers can surface the error as HTTP 400). */
    public List<String> detectTableNames(String sql, SqlDialect dialect) {
        return parseTables(sql, dialect).stream().map(TableModel::name).toList();
    }

    /** Exposed for callers that need the full parsed schema (e.g. the fullstack
     *  wizard's "Import from DDL"). Throws {@link SqlParseException} on parse
     *  failure so the controller can surface HTTP 400. */
    public List<TableModel> parseTablesForImport(String sql, SqlDialect dialect) {
        return parseTables(sql, dialect);
    }

    /** A SELECT's projected column names plus the raw query text, for the
     *  fullstack "Import from SELECT" → read-only {@code @Subselect} view flow.
     *  {@code heuristic} is true when the columns came from the regex fallback
     *  (JSqlParser couldn't parse the query — native/vendor SQL), so callers can
     *  warn the user to double-check the detected fields. */
    public record SelectProjection(String fromTable, List<String> columns, String rawSql,
                                   boolean heuristic) {}

    /**
     * Parses a single SELECT and returns its projected column names (using each
     * item's alias, else the bare column name). Unlike CREATE TABLE, a SELECT
     * carries no column types — callers default every field to STRING and let the
     * user pick types.
     *
     * <p>When JSqlParser can't parse the query (valid native/vendor SQL it doesn't
     * model), this falls back to a best-effort regex extraction of the projected
     * column labels rather than failing — {@code @Subselect} runs the raw SQL
     * regardless. A deliberate {@code SELECT *} or unaliased expression still throws
     * {@link SqlParseException} (those parse fine; they just can't be auto-named).
     */
    public SelectProjection parseSelectForImport(String sql, SqlDialect dialect) {
        return parseSelect(sql, dialect);
    }

    /** Thrown when the submitted DDL cannot be parsed. Callers in a request
     *  context should surface this as HTTP 400. {@code depId} is populated by
     *  the controller that knows which dep-keyed script failed; the generator
     *  itself leaves it null. {@code statementIndex} is 1-based. */
    public static class SqlParseException extends RuntimeException {
        private final String depId;
        private final Integer statementIndex;
        private final String statementSnippet;
        public SqlParseException(String message, Throwable cause) {
            this(null, null, null, message, cause);
        }
        public SqlParseException(String depId, String message, Throwable cause) {
            this(depId, null, null, message, cause);
        }
        public SqlParseException(String depId, Integer statementIndex,
                                 String statementSnippet, String message, Throwable cause) {
            super(message, cause);
            this.depId = depId;
            this.statementIndex = statementIndex;
            this.statementSnippet = statementSnippet;
        }
        public String depId() { return depId; }
        public Integer statementIndex() { return statementIndex; }
        public String statementSnippet() { return statementSnippet; }
    }
}
