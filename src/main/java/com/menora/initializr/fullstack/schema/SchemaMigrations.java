package com.menora.initializr.fullstack.schema;

import com.menora.initializr.config.GeneratedProjectFiles;
import com.menora.initializr.fullstack.EntityDefinition;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a generated backend with Flyway on its build gets from the entity model: the schema as
 * its first migration, and Hibernate told to check the schema rather than make one.
 *
 * <p>Without this a fullstack project on PostgreSQL, Oracle, SQL Server or DB2 started with no
 * tables: the datasource configurations of those databases switch Hibernate's schema creation
 * off, and the {@code flyway} dependency brought a placeholder migration that created none of
 * the entities' tables.
 */
public final class SchemaMigrations {

    private SchemaMigrations() {}

    /** The catalog dependency that puts Flyway on a generated build. */
    public static final String FLYWAY = "flyway";

    private static final String APPLICATION_YAML = "src/main/resources/application.yaml";

    /** Hibernate settings under which it would create or change the schema itself. */
    private static final Set<String> SCHEMA_WRITING = Set.of("create", "create-drop", "update");

    /**
     * Writes {@code db/migration/V1__init.sql} for the project's database — in place of the
     * {@code flyway} dependency's placeholder — and turns {@code ddl-auto: create-drop} (the H2
     * configuration) into {@code validate}: with a migration there, Hibernate creating the tables
     * as well would hide a migration that does not match the entities until the first real
     * database.
     */
    public static Generated writeBaseline(Path projectRoot, Map<String, Object> projectContext,
                                          List<EntityDefinition> entities, Collection<String> dependencyIds)
            throws IOException {
        Schema schema = SchemaBuilder.build(projectContext, entities);
        SchemaDialect dialect = SchemaDialect.of(dependencyIds);
        Path baseline = GeneratedProjectFiles.resolveInside(projectRoot, SchemaSql.BASELINE_PATH);
        GeneratedProjectFiles.createParentDirectories(baseline);
        Files.writeString(baseline, SchemaSql.baseline(schema, dialect));
        validateInsteadOfCreate(projectRoot.resolve(APPLICATION_YAML));
        return new Generated(schema, dialect);
    }

    /** The schema a generation wrote a baseline for, and the database it wrote it for. */
    public record Generated(Schema schema, SchemaDialect dialect) {
    }

    /** Where a fullstack project keeps its migrations, from the project's root. */
    private static final String MIGRATIONS = "backend/src/main/resources/db/migration/";

    /**
     * Makes the two generated trees of an update say what an existing project needs: its first
     * migration has run and must stay byte for byte what it was, and what the model changed is
     * a migration of its own, {@code V<yyyyMMddHHmmss>__model_update.sql}.
     *
     * <p>A timestamp, not {@code V2}: the project may have migrations of its own by now, and the
     * generator cannot see them. It sorts after every hand-numbered one — so a hand-written
     * migration added <em>later</em> needs a timestamp too (or Flyway's {@code outOfOrder}).
     *
     * <p>Nothing is done when either side has no Flyway (a baseline that appears or goes is
     * the change itself) or the two sides are for different databases (the new baseline is).
     *
     * @param baseTree   the project as its model generated it: path → bytes
     * @param targetTree the project as the changed model generates it; rewritten in place
     * @param stamp      the migration's version, {@code yyyyMMddHHmmss}
     * @param date       the date its header names
     */
    public static void carryBaselineOver(Map<String, byte[]> baseTree, Map<String, byte[]> targetTree,
                                         Generated base, Generated target, String stamp, String date) {
        if (base == null || target == null || base.dialect() != target.dialect()) return;
        String baselinePath = "backend/" + SchemaSql.BASELINE_PATH;
        byte[] ran = baseTree.get(baselinePath);
        if (ran == null) return;
        targetTree.put(baselinePath, ran);
        String migration = SchemaDiff.migration(base.schema(), target.schema(), target.dialect(), date);
        if (migration != null) {
            targetTree.put(MIGRATIONS + "V" + stamp + "__model_update.sql",
                    migration.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateInsteadOfCreate(Path applicationYaml) throws IOException {
        if (!Files.exists(applicationYaml)) return;
        Object loaded = new Yaml().load(Files.readString(applicationYaml));
        if (!(loaded instanceof Map)) return;
        if (!rewrite((Map<String, Object>) loaded)) return;
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Files.writeString(applicationYaml, new Yaml(options).dump(loaded));
    }

    /** {@code ddl-auto} / {@code hbm2ddl-auto}, wherever a datasource's settings keep it. */
    @SuppressWarnings("unchecked")
    private static boolean rewrite(Map<String, Object> node) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                changed |= rewrite((Map<String, Object>) value);
            } else if ((entry.getKey().equals("ddl-auto") || entry.getKey().equals("hbm2ddl-auto"))
                    && value != null && SCHEMA_WRITING.contains(String.valueOf(value))) {
                entry.setValue("validate");
                changed = true;
            }
        }
        return changed;
    }
}
