package com.menora.initializr.sql;

/**
 * What the SQL wizard needs to know about the project it writes into, beyond the script and its
 * options: which starters are on the build (code that needs one is only emitted with it) and
 * which test annotation the Spring Boot line has.
 *
 * @param validation  the {@code validation} starter: Bean Validation annotations and {@code @Valid}
 * @param flyway      the {@code flyway} dependency: the DDL as the first migration
 * @param mockitoBean Spring Boot 3.4+: {@code @MockitoBean} instead of the deprecated {@code @MockBean}
 */
public record SqlProjectFacts(boolean validation, boolean flyway, boolean mockitoBean) {

    /** Nothing extra on the build — what a parse-only call or an old caller means. */
    public static final SqlProjectFacts NONE = new SqlProjectFacts(false, false, false);
}
