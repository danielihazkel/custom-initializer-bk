package com.menora.initializr.sql;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The words each database does not take as a bare table or column name ({@code order},
 * {@code user}, {@code value}…), from {@code sql/reserved-words.txt}. A generated name that is one
 * is written quoted — see {@link SqlNames#quoteFolded} and the fullstack {@code SchemaNames}.
 */
public final class SqlKeywords {

    private SqlKeywords() {}

    private static final String RESOURCE = "sql/reserved-words.txt";
    private static final Map<SqlDialect, Set<String>> RESERVED = new EnumMap<>(SqlDialect.class);
    private static final Map<SqlDialect, Set<String>> SYSTEM = new EnumMap<>(SqlDialect.class);
    /** Every word any list holds: what a database without a list of its own takes. */
    private static final Set<String> ANYWHERE = new TreeSet<>();

    static {
        Map<String, Set<String>> sections = new HashMap<>();
        try (InputStream in = SqlKeywords.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Set<String> current = null;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) continue;
                if (text.startsWith("[") && text.endsWith("]")) {
                    current = sections.computeIfAbsent(text.substring(1, text.length() - 1), k -> new TreeSet<>());
                    continue;
                }
                if (current == null) throw new IllegalStateException(RESOURCE + ": words before the first section");
                for (String word : text.split("\\s+")) current.add(word.toUpperCase(Locale.ROOT));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (SqlDialect dialect : SqlDialect.values()) {
            Set<String> own = sections.get(dialect.depId());
            if (own != null) {
                RESERVED.put(dialect, Collections.unmodifiableSet(own));
                ANYWHERE.addAll(own);
            }
            Set<String> system = sections.get("system:" + dialect.depId());
            SYSTEM.put(dialect, system == null ? Set.of() : Collections.unmodifiableSet(system));
        }
    }

    /**
     * Whether {@code name} (bare, any case) cannot be a bare name on {@code dialect}. A database
     * without a list of its own (DB2, MySQL) answers for every listed word. A null dialect
     * reserves nothing: the database is not known.
     */
    public static boolean reserved(String name, SqlDialect dialect) {
        if (name == null || dialect == null) return false;
        return words(dialect).contains(name.toUpperCase(Locale.ROOT));
    }

    /** Whether {@code name} is reserved on any of the databases. */
    public static boolean reservedAnywhere(String name) {
        return name != null && ANYWHERE.contains(name.toUpperCase(Locale.ROOT));
    }

    /** Whether {@code name} is a column every table of {@code dialect} already has, so no column of a model can be it. */
    public static boolean systemColumn(String name, SqlDialect dialect) {
        if (name == null || dialect == null) return false;
        return SYSTEM.get(dialect).contains(name.toUpperCase(Locale.ROOT));
    }

    /** The words reserved on {@code dialect}, in upper case. */
    public static Set<String> words(SqlDialect dialect) {
        Set<String> own = RESERVED.get(dialect);
        return own != null ? own : Collections.unmodifiableSet(ANYWHERE);
    }

    /** The system columns of {@code dialect}, in upper case. */
    public static Set<String> systemColumns(SqlDialect dialect) {
        return SYSTEM.get(dialect);
    }
}
