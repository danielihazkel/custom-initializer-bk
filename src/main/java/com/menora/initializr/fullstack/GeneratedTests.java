package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tests a fullstack project gets with the {@code tests} option: what they need to know about
 * the model, as the templates read it.
 *
 * <p>Backend: one {@code <Entity>ApiTest} per table-backed entity, run against the real
 * application on an in-memory H2 ({@code src/test/resources/application-test.yaml}, the
 * {@code test} profile, whatever database the application itself uses), all extending one
 * {@code ApiTestSupport} that knows how to make a valid row of every entity through the API —
 * parents first, values inside each field's constraints, a fresh number per row so unique columns
 * never clash. Frontend: per entity a test of the generated validator over a valid row and each
 * rule it checks.
 *
 * <p>A row the tests cannot make is said so, not guessed at: a required field with a pattern and
 * no default (nothing here can invent a value that matches an arbitrary pattern), or a required
 * link to an entity whose rows cannot be made (read-only, itself, a cycle, or one with that
 * problem). Such an entity's writing tests are generated {@code @Disabled} with the reason.
 */
public final class GeneratedTests {

    private GeneratedTests() {}

    /** The callers the test profile gives roles (app.security.dev-roles), when the build checks them. */
    public static final String ADMIN_USER = "api-test-admin";
    public static final String PLAIN_USER = "api-test-user";
    public static final String NO_ROLES_USER = "api-test-nobody";

    /** The project context's lookup: entity name → its per-entity test view. */
    static final String API_TESTS_KEY = "__apiTests";

    /** The databases that come with a datasource config class of their own (prefix = dependency id). */
    private static final List<String> CONFIGURED_DATABASES = List.of("postgresql", "oracle", "mssql", "db2");

    /** Whether one entity gets its tests: the option, as the entity resolves it, on a table. */
    public static boolean tested(EntityDefinition entity, boolean projectTests) {
        Boolean override = entity.opts().get(ScaffoldOption.TESTS.key());
        return !entity.isView() && (override != null ? override : projectTests);
    }

    /** Whether any entity of the model gets tests (and so the project their support files). */
    public static boolean anyTested(List<EntityDefinition> entities, boolean projectTests) {
        return entities.stream().anyMatch(e -> tested(e, projectTests));
    }

    // ── backend ─────────────────────────────────────────────────────────────────────────────

    /**
     * Puts on the backend project context what {@code ApiTestSupport} and the test profile read
     * ({@code hasApiTests}, {@code apiTestRows}, {@code testDatabases}, …) and, under
     * {@link #API_TESTS_KEY}, each entity's view for its {@code <Entity>ApiTest}. Reads the context's
     * {@code optScaffoldTests}, {@code hasLdapAuth} and {@code hasValidation}, so it runs after them.
     */
    public static void putBackend(Map<String, Object> ctx, List<EntityDefinition> entities, Collection<String> dependencyIds) {
        boolean projectTests = Boolean.TRUE.equals(ctx.get("optScaffoldTests"));
        boolean any = anyTested(entities, projectTests);
        ctx.put("hasApiTests", any);
        if (!any) return;
        Map<String, EntityDefinition> byLower = new LinkedHashMap<>();
        for (EntityDefinition e : entities) byLower.put(e.name().toLowerCase(Locale.ROOT), e);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (EntityDefinition e : entities) reasonOf(e, byLower, new LinkedHashSet<>(), reasons);

        boolean ldap = Boolean.TRUE.equals(ctx.get("hasLdapAuth"));
        boolean validation = Boolean.TRUE.equals(ctx.get("hasValidation"));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> perEntity = new LinkedHashMap<>();
        boolean attachments = false;
        boolean linkedValues = false;
        for (EntityDefinition e : entities) {
            Map<String, Object> row = new LinkedHashMap<>();
            String pascal = Naming.toPascalCase(e.name());
            row.put("EntityName", pascal);
            row.put("listPath", "/api/" + Naming.pluralize(Naming.toKebabCase(e.name())));
            row.put("pathJava", pathJava(e));
            String reason = reasons.get(e.name().toLowerCase(Locale.ROOT));
            row.put("unwritable", reason != null);
            row.put("reasonJava", reason == null ? "" : javaString(reason));
            List<String> puts = new ArrayList<>();
            if (reason == null) {
                // The fields the entity's rules read take values the rules accept.
                Map<String, RuleRows.Pick> picks = solved(e).picks();
                for (FieldDefinition f : sent(e)) {
                    RuleRows.Pick pick = picks.get(f.name());
                    if (pick != null) {
                        if (pick.java() != null) puts.add("row.put(\"" + f.name() + "\", " + pick.java() + ");");
                        continue;
                    }
                    // Several files: a list of one, by id.
                    puts.add(f.multiple()
                            ? "row.putArray(\"" + f.name() + "\").addObject().put(\"id\", " + valueJava(f) + ");"
                            : "row.put(\"" + f.name() + "\", " + valueJava(f) + ");");
                    attachments |= f.type() == FieldType.FILE || f.type() == FieldType.IMAGE;
                }
                for (RelationDefinition r : e.relations()) {
                    if (!r.required()) continue;
                    EntityDefinition target = byLower.get(r.targetEntity().toLowerCase(Locale.ROOT));
                    // A field of the parent the rules read (costCenter.budget): the parent is made with the value they hold for.
                    StringBuilder values = new StringBuilder();
                    for (Map.Entry<String, RuleRows.Pick> pick : picks.entrySet()) {
                        if (!pick.getKey().startsWith(r.fieldName() + ".") || pick.getValue().java() == null) continue;
                        values.append("parent.put(\"").append(pick.getKey().substring(r.fieldName().length() + 1))
                                .append("\", ").append(pick.getValue().java()).append("); ");
                    }
                    linkedValues |= values.length() > 0;
                    String made = values.length() == 0 ? "create(\"" + Naming.toPascalCase(target.name()) + "\")"
                            : "create(\"" + Naming.toPascalCase(target.name()) + "\", parent -> { " + values.toString().trim() + " })";
                    puts.add("row.set(\"" + Naming.toCamelCase(r.fieldName()) + "Id\", " + made
                            + ".get(\"" + keys(target).get(0).name() + "\"));");
                }
            }
            row.put("puts", puts);
            rows.add(row);
            perEntity.put(e.name(), entityView(e, reason, ldap, validation));
        }
        ctx.put("apiTestRows", rows);
        ctx.put("apiTestUsesAttachments", attachments);
        ctx.put("apiTestLinkedValues", linkedValues);
        ctx.put(API_TESTS_KEY, perEntity);
        ctx.put("apiTestAdmin", ADMIN_USER);
        ctx.put("apiTestUser", PLAIN_USER);
        ctx.put("apiTestNobody", NO_ROLES_USER);
        putTestDatabase(ctx, entities, dependencyIds);
    }

    /** Copies one entity's test view onto its render context (the per-entity file reads it). */
    @SuppressWarnings("unchecked")
    static void putEntity(Map<String, Object> entityCtx, EntityDefinition entity) {
        Map<String, Map<String, Object>> frontend = (Map<String, Map<String, Object>>) entityCtx.get(FE_TESTS_KEY);
        Map<String, Object> feView = frontend == null ? null : frontend.get(entity.name());
        boolean feOn = feView != null && Boolean.TRUE.equals(entityCtx.get("optScaffoldTests"));
        entityCtx.put("feTestApplicable", feOn);
        if (feOn) entityCtx.putAll(feView);
        // The list page renders its rows in a table or as cards; the board, calendar and gallery
        // place them by values a sample row does not have.
        String initialView = String.valueOf(entityCtx.get("initialView"));
        entityCtx.put("feRenderTestApplicable", feOn && feView.get("feRenderText") != null
                && ("table".equals(initialView) || "cards".equals(initialView)));
        Map<String, Map<String, Object>> all = (Map<String, Map<String, Object>>) entityCtx.get(API_TESTS_KEY);
        Map<String, Object> view = all == null ? null : all.get(entity.name());
        boolean on = view != null && Boolean.TRUE.equals(entityCtx.get("optScaffoldTests")) && !entity.isView();
        entityCtx.put("apiTestApplicable", on);
        if (!on) return;
        entityCtx.putAll(view);
        entityCtx.put("apiTestRestores", Boolean.TRUE.equals(entityCtx.get("softDeleteApplicable"))
                && Boolean.TRUE.equals(view.get("apiTestWritable")));
    }

    private static Map<String, Object> entityView(EntityDefinition e, String reason, boolean ldap, boolean validation) {
        Map<String, Object> v = new LinkedHashMap<>();
        boolean mutable = !e.readOnly();
        List<FieldDefinition> keys = keys(e);
        FieldDefinition key = keys.get(0);
        v.put("apiTestKey", key.name());
        v.put("apiTestListPath", "/api/" + Naming.pluralize(Naming.toKebabCase(e.name())));
        v.put("apiTestMutable", mutable);
        v.put("apiTestWritable", mutable && reason == null);
        v.put("apiTestBlocked", mutable && reason != null);
        v.put("apiTestBlockedReason", reason == null ? "" : javaString(reason));
        // A row that is not there: a key of the entity's type that no generated row has.
        String missing = keys.size() == 1 ? switch (key.type()) {
            case LONG, INTEGER -> "/987654321";
            case STRING -> "/no-such-row";
            case UUID -> "/00000000-0000-0000-0000-000000000000";
            default -> null;
        } : null;
        v.put("apiTestHasMissing", missing != null);
        v.put("apiTestMissing", missing);
        List<FieldDefinition> sent = sent(e);
        // The field an update changes: plain text the row can hold another value of — and that
        // no rule reads, so the new value cannot break one.
        Set<String> ruled = RuleRows.read(e);
        FieldDefinition update = sent.stream()
                .filter(f -> (f.type() == FieldType.STRING || f.type() == FieldType.TEXT || f.type() == FieldType.MARKDOWN)
                        && !f.primaryKey() && !f.readOnly() && f.pattern() == null && !f.email() && !ruled.contains(f.name())
                        && !f.computed() && f.showWhen() == null)
                .findFirst().orElse(null);
        v.put("apiTestHasUpdate", update != null);
        if (update != null) {
            v.put("apiTestUpdateField", update.name());
            v.put("apiTestUpdateValue", "label(\"Changed\", nextRow(), " + (update.length() != null ? update.length() : "Integer.MAX_VALUE") + ")");
        }
        // Search: the first text field the list's `q` reads that the tests fill.
        FieldDefinition search = sent.stream()
                .filter(f -> (f.type() == FieldType.STRING || f.type() == FieldType.TEXT) && f.searchable()
                        && !f.computed() && f.showWhen() == null)
                .findFirst().orElse(null);
        v.put("apiTestHasSearch", search != null);
        v.put("apiTestSearchField", search == null ? null : search.name());
        // Bean Validation: a required value left out is a 400 naming it.
        FieldDefinition required = !validation ? null : sent.stream()
                .filter(f -> f.required() && !f.primaryKey() && f.type() != FieldType.FILE && f.type() != FieldType.IMAGE)
                .findFirst().orElse(null);
        v.put("apiTestHasRequired", required != null);
        v.put("apiTestRequiredField", required == null ? null : required.name());
        // Workflow: the first move out of the initial state that asks for nothing, and one that
        // cannot be made from where that move lands.
        EntityDefinition.Workflow wf = e.workflow();
        EntityDefinition.Transition move = wf == null ? null : wf.transitions().stream()
                .filter(t -> t.from().contains(wf.initial()) && t.requires().isEmpty()).findFirst().orElse(null);
        v.put("apiTestHasWorkflow", move != null);
        if (move != null) {
            v.put("apiTestStatusField", wf.statusField());
            v.put("apiTestInitial", wf.initial());
            v.put("apiTestMove", move.name());
            v.put("apiTestMovedTo", move.to());
            EntityDefinition.Transition wrong = wf.transitions().stream()
                    .filter(t -> !t.from().contains(move.to()) && t.requires().isEmpty()).findFirst().orElse(null);
            v.put("apiTestHasWrongMove", wrong != null);
            v.put("apiTestWrongMove", wrong == null ? null : wrong.name());
        }
        v.put("apiTestHasAssignee", wf != null && wf.assigneeField() != null && wf.openStates().contains(wf.initial()));
        v.put("apiTestAssigneeField", wf == null ? null : wf.assigneeField());
        // Access: who is turned away, by the entity's own levels.
        v.put("apiTestChecksAccess", ldap);
        if (ldap) {
            String read = e.access().read();
            String write = e.access().write();
            v.put("apiTestReadSignedIn", "SIGNED_IN".equals(read));
            v.put("apiTestReadUser", "USER".equals(read));
            v.put("apiTestReadAdmin", "ADMIN".equals(read));
            v.put("apiTestWriteAdmin", "ADMIN".equals(write) && mutable && reason == null);
        }
        return v;
    }

    /** The values {@code e}'s rules accept for a test row; none when it has no rules or none can be found. */
    static RuleRows.Solution solved(EntityDefinition e) {
        try {
            return RuleRows.solve(e, sent(e));
        } catch (RuleRows.Unsolvable u) {
            return RuleRows.Solution.NONE;
        }
    }

    /** The fields a test row sends: everything a client may set, less what it can leave out and the tests cannot fill. */
    private static List<FieldDefinition> sent(EntityDefinition e) {
        EntityDefinition.Workflow wf = e.workflow();
        List<FieldDefinition> out = new ArrayList<>();
        for (FieldDefinition f : e.fields()) {
            if (f.primaryKey() && f.generated()) continue;
            if (wf != null && (f.name().equals(wf.statusField()) || f.name().equals(wf.assigneeField()))) continue;
            boolean attachment = f.type() == FieldType.FILE || f.type() == FieldType.IMAGE;
            if (!f.required() && !f.primaryKey() && (attachment || f.pattern() != null)) continue;
            out.add(f);
        }
        return out;
    }

    private static List<FieldDefinition> keys(EntityDefinition e) {
        List<FieldDefinition> keys = e.fields().stream().filter(FieldDefinition::primaryKey).toList();
        return keys.isEmpty() ? e.fields() : keys;
    }

    /**
     * Why a valid row of {@code e} cannot be made by the tests, or null when it can — memoized in
     * {@code reasons} (keyed by lower-cased name); {@code visiting} breaks cycles of required links.
     */
    private static String reasonOf(EntityDefinition e, Map<String, EntityDefinition> byLower,
                                   Set<String> visiting, Map<String, String> reasons) {
        String lower = e.name().toLowerCase(Locale.ROOT);
        if (reasons.containsKey(lower)) return reasons.get(lower);
        if (e.readOnly()) {
            reasons.put(lower, e.name() + " is read-only: the API makes no rows of it");
            return reasons.get(lower);
        }
        if (!visiting.add(lower)) return e.name() + " needs a row of itself, through a cycle of required links";
        String reason = null;
        for (FieldDefinition f : e.fields()) {
            if (f.pattern() != null && (f.required() || f.primaryKey()) && !f.hasDefault()) {
                reason = "'" + f.name() + "' must match a pattern the tests cannot guess a value for"
                        + " — put a matching value in ApiTestSupport.body";
                break;
            }
        }
        if (reason == null && !e.allRules().isEmpty()) {
            try {
                RuleRows.solve(e, sent(e));
            } catch (RuleRows.Unsolvable u) {
                reason = "no row the tests can make passes its rule " + (u.rule + 1) + " ('"
                        + e.allRules().get(u.rule).check() + "') — put values that do in ApiTestSupport.body";
            }
        }
        for (RelationDefinition r : e.relations()) {
            if (reason != null) break;
            if (!r.required()) continue;
            EntityDefinition target = byLower.get(r.targetEntity().toLowerCase(Locale.ROOT));
            if (target == null || target == e) {
                reason = "the required link '" + r.fieldName() + "' points at " + e.name() + " itself";
            } else {
                String upstream = reasonOf(target, byLower, visiting, reasons);
                if (upstream != null) reason = "the required link '" + r.fieldName() + "' needs a " + target.name()
                        + ", and " + upstream;
            }
        }
        visiting.remove(lower);
        reasons.put(lower, reason);
        return reason;
    }

    /** Java building one row's path from its JSON: the list path and each key, in order. */
    private static String pathJava(EntityDefinition e) {
        StringBuilder sb = new StringBuilder("listPath(entity)");
        for (FieldDefinition k : keys(e)) sb.append(" + \"/\" + row.get(\"").append(k.name()).append("\").asText()");
        return sb.toString();
    }

    /** A Java expression for a valid value of {@code f} in row number {@code i} (ApiTestSupport's helpers). */
    static String valueJava(FieldDefinition f) {
        // A pattern the tests cannot satisfy themselves: its default (only here when it has one).
        if (f.pattern() != null) return "\"" + escape(f.defaultValue()) + "\"";
        String max = f.length() != null ? String.valueOf(f.length()) : "Integer.MAX_VALUE";
        String base = Naming.toPascalCase(f.name());
        return switch (f.type()) {
            // A key travels in the row's path: no space in it.
            case STRING -> f.email() ? "\"user\" + i + \"@example.com\""
                    : f.primaryKey() ? "key(\"" + base + "\", i, " + max + ")" : "label(\"" + base + "\", i, " + max + ")";
            case TEXT, MARKDOWN -> "\"" + base + " text \" + i";
            case LONG -> "bounded(i, " + ceil(f.min()) + ", " + floor(f.max()) + ")";
            case INTEGER -> "(int) bounded(i, " + ceil(f.min()) + ", " + floor(f.max()) + ")";
            case BIG_DECIMAL, MONEY -> "decimal(i, " + decimalLiteral(f.min()) + ", " + decimalLiteral(f.max()) + ")";
            case BOOLEAN -> "i % 2 == 0";
            case ENUM -> "pick(i" + f.enumValues().stream().map(c -> ", \"" + c + "\"").reduce("", String::concat) + ")";
            case LOCAL_DATE -> "java.time.LocalDate.now().minusDays(i % 365).toString()";
            case LOCAL_DATE_TIME -> "java.time.LocalDateTime.now().withNano(0).minusHours(i % 1000).toString()";
            case LOCAL_TIME -> "java.time.LocalTime.of(8 + i % 10, 0).toString()";
            case INSTANT -> "java.time.Instant.now().minusSeconds(i * 60L).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()";
            case UUID -> "java.util.UUID.randomUUID().toString()";
            case FILE -> "attachment(i, false)";
            case IMAGE -> "attachment(i, true)";
        };
    }

    private static String ceil(BigDecimal v) {
        return v == null ? "null" : v.setScale(0, RoundingMode.CEILING).toPlainString() + "L";
    }

    private static String floor(BigDecimal v) {
        return v == null ? "null" : v.setScale(0, RoundingMode.FLOOR).toPlainString() + "L";
    }

    private static String decimalLiteral(BigDecimal v) {
        return v == null ? "null" : "\"" + v.toPlainString() + "\"";
    }

    /** The test profile's database: an in-memory H2 under every datasource the build configures. */
    private static void putTestDatabase(Map<String, Object> ctx, List<EntityDefinition> entities, Collection<String> deps) {
        List<Map<String, Object>> configured = new ArrayList<>();
        for (String db : CONFIGURED_DATABASES) {
            if (deps.contains(db)) configured.add(Map.of("prefix", db));
        }
        // H2 normally runs on spring.datasource; its own config class (the h2-primary role) binds h2.*.
        List<Map<String, Object>> prefixes = new ArrayList<>(configured);
        if (deps.contains("h2")) prefixes.add(Map.of("prefix", "h2"));
        ctx.put("testDatabases", prefixes);
        // Schema-qualified tables need their schema to exist before Hibernate creates them.
        Set<String> schemas = new LinkedHashSet<>();
        for (EntityDefinition e : entities) {
            if (e.schema() != null && !e.schema().isBlank() && !e.isView()) schemas.add(e.schema().replaceAll("[\"`\\[\\]]", ""));
        }
        StringBuilder url = new StringBuilder("jdbc:h2:mem:api-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
        // Names the application's own database takes bare, and H2 would read as keywords.
        List<String> freed = SchemaNames.freedOnH2(entities);
        if (!freed.isEmpty()) url.append(";NON_KEYWORDS=").append(String.join(",", freed));
        if (!schemas.isEmpty()) {
            url.append(";INIT=");
            List<String> creates = schemas.stream().map(s -> "CREATE SCHEMA IF NOT EXISTS " + s).toList();
            url.append(String.join("\\;", creates));
        }
        ctx.put("testDbUrl", url.toString());
        // With Flyway and H2 as the application's own database, the baseline is H2 SQL: the tests
        // run it and Hibernate validates against it. Any other database's baseline is not H2's, so
        // the tests let Hibernate create the schema instead.
        boolean flyway = deps.contains("flyway");
        boolean migrations = flyway && configured.isEmpty() && deps.contains("h2");
        ctx.put("testHasFlyway", flyway);
        ctx.put("testUsesMigrations", migrations);
        ctx.put("testDdlAuto", migrations ? "validate" : "create-drop");
        ctx.put("testNeedsH2", !deps.contains("h2"));
    }

    // ── frontend ────────────────────────────────────────────────────────────────────────────

    /** The project context's lookup for the frontend: entity name → its validator test view. */
    static final String FE_TESTS_KEY = "__feTests";

    /**
     * Puts on the frontend project context {@code hasFrontendTests} (Vitest in the package, its
     * config) and, under {@link #FE_TESTS_KEY}, each entity's validator test: a valid row as a TS
     * literal and one case per rule the generated {@code validate<Entity>} checks.
     */
    public static void putFrontend(Map<String, Object> ctx, List<EntityDefinition> entities) {
        boolean projectTests = Boolean.TRUE.equals(ctx.get("optScaffoldTests"));
        boolean any = anyTested(entities, projectTests);
        ctx.put("hasFrontendTests", any);
        if (!any) return;
        Map<String, EntityDefinition> byLower = new LinkedHashMap<>();
        for (EntityDefinition e : entities) byLower.put(e.name().toLowerCase(Locale.ROOT), e);
        Map<String, Map<String, Object>> perEntity = new LinkedHashMap<>();
        for (EntityDefinition e : entities) {
            if (!tested(e, projectTests)) continue;
            perEntity.put(e.name(), frontendView(e, byLower));
        }
        ctx.put(FE_TESTS_KEY, perEntity);
        // The shared code with tests of its own, where the app ships it.
        ctx.put("feTestsCsv", Boolean.TRUE.equals(ctx.get("hasCsvImport")));
        ctx.put("feTestsRoute", Boolean.TRUE.equals(ctx.get("hasPages")));
    }

    private static Map<String, Object> frontendView(EntityDefinition e, Map<String, EntityDefinition> byLower) {
        List<String> props = new ArrayList<>();
        List<Map<String, Object>> cases = new ArrayList<>();
        boolean complete = true;
        EntityDefinition.Workflow wf = e.workflow();
        for (FieldDefinition f : e.fields()) {
            if (f.primaryKey() && f.generated()) continue;
            if (wf != null && (f.name().equals(wf.statusField()) || f.name().equals(wf.assigneeField()))) continue;
            // Computed: never checked in the form. Shown on a condition: cleared while hidden, so a
            // case of its own might not reach its check.
            if (f.computed() || f.showWhen() != null) continue;
            boolean checked = f.required() || f.primaryKey();
            if (f.pattern() != null) {
                if (f.hasDefault()) props.add(f.name() + ": " + tsString(f.defaultValue()));
                else if (checked) complete = false;
            } else if (checked || f.length() != null || f.min() != null || f.max() != null || f.email()) {
                props.add(f.name() + ": " + valueTs(f));
            }
            if (checked) cases.add(testCase("asks for " + f.name(), f.name(), f.name() + ": undefined"));
            if (f.length() != null && (f.type() == FieldType.STRING)) {
                cases.add(testCase("keeps " + f.name() + " to " + f.length() + " characters", f.name(),
                        f.name() + ": 'x'.repeat(" + (f.length() + 1) + ")"));
            }
            boolean numeric = f.type() == FieldType.LONG || f.type() == FieldType.INTEGER
                    || f.type() == FieldType.BIG_DECIMAL || f.type() == FieldType.MONEY;
            if (numeric && f.min() != null) {
                cases.add(testCase("holds " + f.name() + " to at least " + f.min().toPlainString(), f.name(),
                        f.name() + ": " + f.min().subtract(BigDecimal.ONE).toPlainString()));
            }
            if (numeric && f.max() != null) {
                cases.add(testCase("holds " + f.name() + " to at most " + f.max().toPlainString(), f.name(),
                        f.name() + ": " + f.max().add(BigDecimal.ONE).toPlainString()));
            }
            if (f.email()) cases.add(testCase("wants an e-mail address in " + f.name(), f.name(), f.name() + ": 'not-an-address'"));
        }
        for (RelationDefinition r : e.relations()) {
            if (!r.required()) continue;
            String fk = Naming.toCamelCase(r.fieldName()) + "Id";
            EntityDefinition target = byLower.get(r.targetEntity().toLowerCase(Locale.ROOT));
            FieldDefinition targetKey = target == null ? null : keys(target).get(0);
            props.add(fk + ": " + (targetKey == null || targetKey.type() == FieldType.LONG || targetKey.type() == FieldType.INTEGER
                    ? "1" : targetKey.type() == FieldType.UUID ? "'00000000-0000-0000-0000-000000000001'" : "'1'"));
            cases.add(testCase("asks for " + r.fieldName(), fk, fk + ": undefined"));
        }
        // The list page's test shows a row; it runs no rules, so it keeps the usual values.
        List<String> unruled = new ArrayList<>(props);
        // The fields the entity's rules read: values the rules accept (empty where that is how).
        boolean usesDays = false;
        if (!e.allRules().isEmpty()) {
            try {
                RuleRows.Solution solution = RuleRows.solve(e, sent(e));
                for (Map.Entry<String, RuleRows.Pick> pick : solution.picks().entrySet()) {
                    props.removeIf(p -> p.startsWith(pick.getKey() + ": "));
                    if (pick.getValue().ts() != null) props.add(pick.getKey() + ": " + pick.getValue().ts());
                }
                usesDays = solution.usesDays();
            } catch (RuleRows.Unsolvable u) {
                complete = false;
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("feTestUsesDays", usesDays);
        v.put("feTestRowTs", props.isEmpty() ? "{}" : "{\n  " + String.join(",\n  ", props) + ",\n}");
        // The list page's test: the API's one row, and a text of it to find on the page.
        List<String> shown = new ArrayList<>(unruled);
        for (FieldDefinition k : keys(e)) {
            if (k.generated()) shown.add(0, k.name() + ": " + (k.type() == FieldType.UUID ? "'00000000-0000-0000-0000-000000000001'" : "1"));
        }
        FieldDefinition text = e.fields().stream()
                .filter(f -> f.type() == FieldType.STRING && !f.primaryKey() && f.pattern() == null && !f.email()
                        && (wf == null || (!f.name().equals(wf.statusField()) && !f.name().equals(wf.assigneeField()))))
                .findFirst().orElse(null);
        if (text != null && shown.stream().noneMatch(p -> p.startsWith(text.name() + ": "))) {
            shown.add(text.name() + ": " + valueTs(text));
        }
        v.put("feRenderRowTs", "{\n  " + String.join(",\n  ", shown) + ",\n}");
        v.put("feRenderText", text == null ? null : valueTs(text));
        v.put("feTestComplete", complete);
        v.put("feTestCases", cases);
        return v;
    }

    private static Map<String, Object> testCase(String title, String field, String patch) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("title", title.replace("'", "\\'"));
        c.put("field", field);
        c.put("patch", patch);
        return c;
    }

    /** A TS literal for a valid value of {@code f} (inside its length and bounds). */
    static String valueTs(FieldDefinition f) {
        return switch (f.type()) {
            case STRING -> f.email() ? "'user1@example.com'" : tsString(fit(Naming.toPascalCase(f.name()) + " 1", f.length()));
            case TEXT, MARKDOWN -> "'Text'";
            case LONG, INTEGER, BIG_DECIMAL, MONEY -> numberInside(f).toPlainString();
            case BOOLEAN -> "true";
            case ENUM -> tsString(f.enumValues().get(0));
            case LOCAL_DATE -> "'2026-01-15'";
            case LOCAL_DATE_TIME -> "'2026-01-15T10:00'";
            case LOCAL_TIME -> "'08:00'";
            case INSTANT -> "'2026-01-15T10:00:00Z'";
            case UUID, FILE, IMAGE -> "'00000000-0000-0000-0000-000000000001'";
        };
    }

    /** 1, or the nearest number to it the bounds allow (a whole one for a whole-number type). */
    private static BigDecimal numberInside(FieldDefinition f) {
        BigDecimal v = BigDecimal.ONE;
        if (f.min() != null && v.compareTo(f.min()) < 0) v = f.min();
        if (f.max() != null && v.compareTo(f.max()) > 0) v = f.max();
        if (f.type() == FieldType.LONG || f.type() == FieldType.INTEGER) {
            BigDecimal whole = v.setScale(0, RoundingMode.CEILING);
            if (f.max() != null && whole.compareTo(f.max()) > 0) whole = v.setScale(0, RoundingMode.FLOOR);
            v = whole;
        }
        return v.stripTrailingZeros().scale() < 0 ? v.setScale(0) : v.stripTrailingZeros();
    }

    private static String fit(String s, Integer max) {
        return max == null || s.length() <= max ? s : s.substring(0, Math.max(1, max));
    }

    private static String tsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    // ── shared ──────────────────────────────────────────────────────────────────────────────

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String javaString(String s) {
        return escape(s);
    }
}
