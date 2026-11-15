package com.menora.initializr.fullstack.schema;

import com.menora.initializr.fullstack.schema.Schema.Column;
import com.menora.initializr.fullstack.schema.Schema.ForeignKey;
import com.menora.initializr.fullstack.schema.Schema.Index;
import com.menora.initializr.fullstack.schema.Schema.Table;
import com.menora.initializr.fullstack.schema.Schema.Unique;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What takes a database from one model's schema to another's: the migration an updated project
 * needs beside its changed code.
 *
 * <p>Two kinds of statement come out of a comparison, and they are kept apart:
 * <ul>
 *   <li><b>live</b> — what the new model adds, which runs on a database with rows in it: a new
 *       table, a new column that may be null (or has a default), a foreign key and an index on
 *       such a column, a column that no longer has to be filled;</li>
 *   <li><b>to review</b> — what loses data or can fail on the rows already there: a dropped
 *       table or column, a changed type, a column that becomes required, a unique constraint over
 *       existing values. Each is written commented out under a line saying what changed.</li>
 * </ul>
 * A column the model requires is <em>added</em> without {@code NOT NULL} (live) and the
 * {@code NOT NULL} goes to review: the table may have rows, and they have no value for it yet.
 *
 * <p>A rename cannot be told from a removal and an addition — the model does not say — so it
 * comes out as both, and the note on the removal says so.
 */
public final class SchemaDiff {

    private SchemaDiff() {}

    /**
     * @param live   statements that run as they are, each ending in {@code ;}
     * @param review blocks to read first: a {@code --} note, then the statement, commented out
     */
    public record Result(List<String> live, List<String> review) {
        public boolean isEmpty() {
            return live.isEmpty() && review.isEmpty();
        }
    }

    public static Result between(Schema base, Schema target, SchemaDialect dialect) {
        List<String> creates = new ArrayList<>();
        List<String> alters = new ArrayList<>();
        List<String> after = new ArrayList<>();
        List<String> drops = new ArrayList<>();
        List<String> review = new ArrayList<>();

        for (Table table : target.tables()) {
            Table was = base.table(table.ref());
            if (was == null) {
                creates.add(SchemaSql.createTable(table, dialect).stripTrailing());
                after.addAll(SchemaSql.constraintsAndIndexes(table, dialect));
                continue;
            }
            Set<String> added = new HashSet<>();
            for (Column column : table.columns()) {
                Column before = was.column(column.name());
                if (before == null) {
                    added.add(column.name());
                    boolean fillable = column.nullable() || column.defaultFalse();
                    Column asAdded = fillable ? column
                            : new Column(column.name(), column.type(), column.length(), true, false, false);
                    alters.add("alter table " + table.ref() + " " + dialect.addColumn() + " "
                            + SchemaSql.columnDefinition(asAdded, dialect) + ";");
                    if (!fillable) {
                        review.add(note(table.ref() + "." + column.name() + " is required by the model. It was added"
                                + " above without NOT NULL, since the table may have rows: give them a value, then:",
                                dialect.setNotNull(table.ref(), column) + ";"));
                    }
                    continue;
                }
                if (before.type() != column.type() || !Objects.equals(before.length(), column.length())) {
                    review.add(note(table.ref() + "." + column.name() + " changed from " + dialect.typeOf(before)
                            + " to " + dialect.typeOf(column) + ". Values that do not fit the new type make this fail"
                            + reorgHint(dialect) + ":",
                            dialect.alterType(table.ref(), column) + ";"));
                }
                if (before.nullable() && !column.nullable()) {
                    review.add(note(table.ref() + "." + column.name() + " is now required. Rows without a value"
                            + " make this fail: give them one first, then:",
                            dialect.setNotNull(table.ref(), column) + ";"));
                } else if (!before.nullable() && column.nullable()) {
                    alters.add(dialect.dropNotNull(table.ref(), column) + ";");
                }
            }

            // What a removed column takes with it has to go first on some databases.
            Set<String> removed = new HashSet<>();
            for (Column before : was.columns()) {
                if (table.column(before.name()) != null) continue;
                removed.add(before.name());
                List<String> statements = new ArrayList<>();
                for (ForeignKey fk : was.foreignKeys()) {
                    if (fk.columns().contains(before.name())) statements.add(dropConstraint(table, fk.name()));
                }
                for (Unique unique : was.uniques()) {
                    if (unique.columns().contains(before.name())) statements.add(dropUnique(was, unique, dialect));
                }
                for (Index index : was.indexes()) {
                    if (index.columns().contains(before.name())) {
                        statements.add(dialect.dropIndex(table.schema(), table.ref(), index.name()) + ";");
                    }
                }
                statements.add("alter table " + table.ref() + " drop column " + before.name() + ";");
                review.add(note(table.ref() + "." + before.name() + " is no longer in the model; dropping it loses"
                        + " its values" + reorgHint(dialect) + ". If the field was renamed rather than removed, rename"
                        + " the column instead — and delete the statement above that adds the new one.",
                        statements.toArray(String[]::new)));
            }

            if (!was.primaryKey().equals(table.primaryKey())) {
                review.add(note("The key of " + table.ref() + " changed from (" + String.join(", ", was.primaryKey())
                        + ") to (" + String.join(", ", table.primaryKey()) + "). Nothing is written for that:"
                        + " rebuild the key, and whatever refers to it, by hand."));
            }

            for (Unique unique : table.uniques()) {
                if (has(was.uniques(), unique)) continue;
                String statement = SchemaSql.uniqueIsInline(table, unique, dialect)
                        ? "alter table " + table.ref() + " add " + SchemaSql.uniqueClause(unique) + ";"
                        : SchemaSql.createUniqueIndex(table, unique, dialect);
                if (added.containsAll(unique.columns())) {
                    after.add(statement);
                } else {
                    review.add(note(String.join(", ", unique.columns()) + " of " + table.ref() + " must now be unique."
                            + " Rows that share a value make this fail:", statement));
                }
            }
            for (Unique unique : was.uniques()) {
                if (has(table.uniques(), unique) || intersects(unique.columns(), removed)) continue;
                drops.add(dropUnique(was, unique, dialect));
            }

            for (ForeignKey fk : table.foreignKeys()) {
                if (has(was.foreignKeys(), fk)) continue;
                String statement = SchemaSql.addForeignKey(table, fk);
                if (added.containsAll(fk.columns())) {
                    after.add(statement);
                } else {
                    review.add(note(String.join(", ", fk.columns()) + " of " + table.ref() + " now refers to "
                            + fk.refTable() + ". Values with no such row make this fail:", statement));
                }
            }
            for (ForeignKey fk : was.foreignKeys()) {
                if (has(table.foreignKeys(), fk) || intersects(fk.columns(), removed)) continue;
                drops.add(dropConstraint(table, fk.name()));
            }

            for (Index index : table.indexes()) {
                if (!has(was.indexes(), index)) after.add(SchemaSql.createIndex(table, index));
            }
            for (Index index : was.indexes()) {
                if (has(table.indexes(), index) || intersects(index.columns(), removed)) continue;
                drops.add(dialect.dropIndex(table.schema(), table.ref(), index.name()) + ";");
            }
        }

        for (Table was : base.tables()) {
            if (target.table(was.ref()) != null) continue;
            review.add(note(was.ref() + " is no longer in the model; dropping the table loses its rows."
                    + " If the entity was renamed rather than removed, rename the table instead — and delete the"
                    + " statement above that creates the new one.",
                    "drop table " + was.ref() + ";"));
        }

        List<String> live = new ArrayList<>();
        live.addAll(creates);
        live.addAll(alters);
        live.addAll(drops);
        live.addAll(after);
        return new Result(live, review);
    }

    /**
     * The comparison as a migration file, or null when the two schemas are the same.
     *
     * @param generatedOn the date for the header
     */
    public static String migration(Schema base, Schema target, SchemaDialect dialect, String generatedOn) {
        Result result = between(base, target, dialect);
        if (result.isEmpty()) return null;
        StringBuilder sql = new StringBuilder();
        sql.append("-- What the changed model needs of the database, generated by the Menora Initializr on ")
                .append(generatedOn).append(" for ").append(SchemaSql.label(dialect)).append(".\n")
                .append("-- It is the difference between the schema of the model the project was generated from and\n")
                .append("-- the schema of the model as it is now — not of your database, which it has not seen.\n");
        if (result.live().isEmpty()) {
            sql.append("--\n-- Nothing in it runs as it is: read the statements below, and uncomment the ones you want.\n");
        } else {
            sql.append("--\n-- What the model adds runs as it is.");
            if (!result.review().isEmpty()) {
                sql.append(" What it removes or tightens is at the end, commented out:\n")
                        .append("-- those statements lose data or can fail on the rows already there. Read each, then\n")
                        .append("-- uncomment it — or move it to a later migration.");
            }
            sql.append('\n');
        }
        for (String statement : result.live()) sql.append('\n').append(statement).append('\n');
        if (!result.review().isEmpty()) {
            sql.append("\n-- ── To review ").append("─".repeat(66)).append('\n');
            for (String block : result.review()) sql.append('\n').append(block).append('\n');
        }
        return sql.toString();
    }

    private static String dropConstraint(Table table, String name) {
        return "alter table " + table.ref() + " drop constraint " + name + ";";
    }

    /** A unique constraint is a constraint, or — over nullable columns on DB2 and SQL Server — an index. */
    private static String dropUnique(Table table, Unique unique, SchemaDialect dialect) {
        return SchemaSql.uniqueIsInline(table, unique, dialect)
                ? dropConstraint(table, unique.name())
                : dialect.dropIndex(table.schema(), table.ref(), unique.name()) + ";";
    }

    /** DB2 leaves a table unusable after some changes until it is reorganised. */
    private static String reorgHint(SchemaDialect dialect) {
        return dialect == SchemaDialect.DB2 ? " (DB2: run REORG TABLE on it afterwards)" : "";
    }

    /** A {@code --} note wrapped at 96 columns, then each statement commented out. */
    private static String note(String text, String... statements) {
        StringBuilder block = new StringBuilder();
        StringBuilder line = new StringBuilder("--");
        for (String word : text.split(" ")) {
            if (line.length() + 1 + word.length() > 96 && line.length() > 2) {
                block.append(line).append('\n');
                line = new StringBuilder("--");
            }
            line.append(' ').append(word);
        }
        block.append(line);
        for (String statement : statements) block.append("\n-- ").append(statement);
        return block.toString();
    }

    private static boolean has(List<Unique> uniques, Unique unique) {
        return uniques.stream().anyMatch(u -> u.name().equals(unique.name()) && u.columns().equals(unique.columns()));
    }

    private static boolean has(List<ForeignKey> foreignKeys, ForeignKey fk) {
        return foreignKeys.stream().anyMatch(f -> f.name().equals(fk.name()) && f.columns().equals(fk.columns())
                && Objects.equals(f.refSchema(), fk.refSchema()) && f.refTable().equals(fk.refTable())
                && f.refColumns().equals(fk.refColumns()));
    }

    private static boolean has(List<Index> indexes, Index index) {
        return indexes.stream().anyMatch(i -> i.name().equals(index.name()) && i.columns().equals(index.columns()));
    }

    private static boolean intersects(List<String> columns, Set<String> names) {
        return columns.stream().anyMatch(names::contains);
    }
}
