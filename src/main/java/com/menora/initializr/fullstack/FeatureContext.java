package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.SqlNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The project- and entity-level scaffold options as context flags: audit, history, events,
 * optimistic locking, unique keys, rules and computed fields, inline edit, exports, imports,
 * saved views, and the support packages they need.
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
public final class FeatureContext {

    private FeatureContext() {}

    /**
     * {@code hasCsvImport} on a frontend project context: whether any entity imports CSV (the
     * {@code csvImport} opt, per entity, on a writable entity — an import page switches it on), so
     * the shared CSV reader and import panel ship. Computed with or without a page layout: the
     * classic shell's list pages get the Import button too. Call after {@code optScaffoldCsvImport}
     * is set.
     */
    /**
     * Whether any writable entity resolves the {@code audit} option on (its override, else the
     * project option) — {@code hasAudit}, which gates {@code JpaAuditingConfig}. The file used to
     * follow the project option alone, so an entity that opted in on its own got
     * {@code @CreatedDate} fields that nothing ever filled.
     */
    public static void putAudit(Map<String, Object> ctx, List<EntityDefinition> entities) {
        boolean projectOpt = Boolean.TRUE.equals(ctx.get("optScaffoldAudit"));
        ctx.put("hasAudit", entities.stream().anyMatch(e -> !e.readOnly()
                && (e.opts().get("audit") != null ? e.opts().get("audit") : projectOpt)));
    }

    /**
     * The {@code history} option across the project: {@code hasHistory} (the change log ships),
     * {@code historyEntities} (the classes the log records, each with the fields a restore must
     * leave alone), and {@code usesRequestUser} — who makes a change, for the audit columns and the
     * log. Call after {@code putAudit} and the scaffold flags, on both render paths.
     */
    public static void putHistory(Map<String, Object> ctx, List<EntityDefinition> entities) {
        boolean projectOpt = Boolean.TRUE.equals(ctx.get("optScaffoldHistory"));
        List<Map<String, Object>> tracked = new ArrayList<>();
        for (EntityDefinition e : entities) {
            if (!historyApplies(e, projectOpt)) continue;
            Map<String, Object> view = new LinkedHashMap<>();
            // Fully qualified: the recorder names every tracked class in one file.
            view.put("entityClass", ctx.get("entityPackage") + "." + e.name());
            view.put("EntityName", e.name());
            view.put("entityKey", Naming.toKebabCase(e.name()));
            // A workflow's status and assignee move only through its own calls; a file is deleted
            // when replaced, so an old id points at nothing.
            List<String> keep = new ArrayList<>();
            if (e.workflow() != null) {
                keep.add(e.workflow().statusField());
                if (e.workflow().assigneeField() != null) keep.add(e.workflow().assigneeField());
            }
            e.fields().stream().filter(f -> f.type().isAttachment()).forEach(f -> keep.add(f.name()));
            view.put("keepJava", keep.stream().map(k -> "\"" + k + "\"").collect(java.util.stream.Collectors.joining(", ")));
            tracked.add(view);
        }
        if (!tracked.isEmpty()) tracked.get(tracked.size() - 1).put("last", true);
        ctx.put("hasHistory", !tracked.isEmpty());
        ctx.put("historyEntities", tracked);
        ctx.put("usesRequestUser", !tracked.isEmpty() || Boolean.TRUE.equals(ctx.get("hasAudit")));
    }

    /**
     * Entity events and transition e-mails across the project, on both render paths. Call after
     * {@code putHistory}.
     *
     * <ul>
     *   <li>{@code hasEvents}: some entity resolves the {@code events} option on. Its changes go out
     *       to Kafka (with the {@code kafka} dependency, {@code eventsKafka}) and to webhooks, and
     *       the browser hears of them over {@code /api/events/stream}.</li>
     *   <li>{@code hasNotifications}: some workflow transition e-mails somebody ({@code notify}),
     *       whether or not its entity has the option.</li>
     *   <li>{@code hasOutbox}: either. Both leave through the same outbox table and relay.</li>
     *   <li>{@code outboxEntities}: the classes the recorder watches; {@code notifyTransitions}: the
     *       moves that send mail.</li>
     * </ul>
     */
    public static void putEvents(Map<String, Object> ctx, List<EntityDefinition> entities, java.util.Collection<String> deps) {
        boolean projectOpt = Boolean.TRUE.equals(ctx.get("optScaffoldEvents"));
        List<Map<String, Object>> watched = new ArrayList<>();
        List<Map<String, Object>> notify = new ArrayList<>();
        List<Map<String, Object>> stateLabels = new ArrayList<>();
        boolean publishes = false;
        for (EntityDefinition e : entities) {
            boolean events = eventsApply(e, projectOpt);
            boolean notifies = notifies(e);
            if (!events && !notifies) continue;
            publishes |= events;
            String key = Naming.toKebabCase(e.name());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("entityClass", ctx.get("entityPackage") + "." + e.name());
            view.put("entityKey", key);
            view.put("publishes", events);
            view.put("statusJava", e.workflow() == null ? "null" : "\"" + e.workflow().statusField() + "\"");
            view.put("readLevel", e.access().read());
            watched.add(view);
            if (!notifies) continue;
            String entityLabel = e.label() != null && !e.label().isBlank() ? e.label() : Naming.toPascalCase(e.name());
            FieldDefinition status = e.fields().stream()
                    .filter(f -> f.name().equals(e.workflow().statusField())).findFirst().orElseThrow();
            List<String> labels = new ArrayList<>();
            for (String v : status.enumValues()) {
                String constant = v.toUpperCase(Locale.ROOT);
                String label = status.enumLabels() != null && status.enumLabels().get(constant) != null
                        ? status.enumLabels().get(constant) : TemplateText.humanizeConstant(constant);
                labels.add("Map.entry(\"" + constant + "\", \"" + TemplateText.escapeStringLiteral(label) + "\")");
            }
            stateLabels.add(new LinkedHashMap<>(Map.of("entityKey", key,
                    "labelsJava", String.join(", ", labels))));
            for (EntityDefinition.Transition t : e.workflow().transitions()) {
                if (t.mailTo().isEmpty()) continue;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("key", key + ":" + t.name());
                n.put("assigneeJava", t.mailTo().contains("assignee") ? "\"" + e.workflow().assigneeField() + "\"" : "null");
                n.put("creator", t.mailTo().contains("creator"));
                n.put("rolesJava", t.mailTo().stream().filter(r -> r.equals("ADMIN") || r.equals("USER"))
                        .map(r -> "\"" + r + "\"").collect(java.util.stream.Collectors.joining(", ")));
                n.put("entityLabelJava", TemplateText.escapeStringLiteral(entityLabel));
                n.put("transitionLabelJava", TemplateText.escapeStringLiteral(t.label() != null ? t.label() : TemplateText.humanizeCamel(t.name())));
                notify.add(n);
            }
        }
        if (!watched.isEmpty()) watched.get(watched.size() - 1).put("last", true);
        if (!notify.isEmpty()) notify.get(notify.size() - 1).put("last", true);
        if (!stateLabels.isEmpty()) stateLabels.get(stateLabels.size() - 1).put("last", true);
        boolean hasNotifications = !notify.isEmpty();
        ctx.put("hasEvents", publishes);
        ctx.put("hasNotifications", hasNotifications);
        ctx.put("hasOutbox", publishes || hasNotifications);
        ctx.put("outboxEntities", watched);
        ctx.put("notifyTransitions", notify);
        ctx.put("notifyStateLabels", stateLabels);
        ctx.put("eventsKafka", publishes && deps.contains("kafka"));
        ctx.put("notifyLdapRest", hasNotifications && deps.contains("ldap-auth-rest"));
        // Who caused the change travels with each event.
        if (publishes || hasNotifications) ctx.put("usesRequestUser", true);
    }

    /** {@code events} for one entity: its override, else the project option; writable single-key tables only. */
    public static boolean eventsApply(EntityDefinition e, boolean projectOpt) {
        Boolean override = e.opts().get("events");
        return !e.readOnly() && e.viewQuery() == null
                && e.fields().stream().filter(FieldDefinition::primaryKey).count() == 1
                && (override != null ? override : projectOpt);
    }

    /** Whether a transition of the entity's workflow e-mails somebody. */
    public static boolean notifies(EntityDefinition e) {
        return e.workflow() != null && e.workflow().notifies();
    }

    /** {@code history} for one entity: its override, else the project option; writable single-key tables only. */
    public static boolean historyApplies(EntityDefinition e, boolean projectOpt) {
        Boolean override = e.opts().get("history");
        return !e.readOnly() && e.viewQuery() == null
                && e.fields().stream().filter(FieldDefinition::primaryKey).count() == 1
                && (override != null ? override : projectOpt);
    }

    /**
     * Whether any entity resolves the {@code optimisticLocking} option on (its override, else the
     * project option) and can use it (writable, table-backed) — {@code hasOptimisticLocking}, which
     * gates the project-wide stale-update handler, so an entity that opts in on its own still gets it.
     */
    public static void putOptimisticLocking(Map<String, Object> ctx, List<EntityDefinition> entities) {
        boolean projectOpt = Boolean.TRUE.equals(ctx.get("optScaffoldOptimisticLocking"));
        ctx.put("hasOptimisticLocking", entities.stream().anyMatch(e -> lockingApplies(e, projectOpt)));
    }

    /**
     * {@code hasUniqueKeys} — some entity has a unique key over several fields — and
     * {@code uniqueKeyViolations}: every such key's constraint (lower case, the longest first, so a
     * name that begins another is tried after it), the fields to mark and the message, for the
     * API's 409. The frontend's api-client keeps a 409's field errors for the form when it is set.
     */
    public static void putUniqueKeys(Map<String, Object> ctx, List<EntityDefinition> entities) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (EntityDefinition e : entities) violations.addAll(uniqueKeyViews(e));
        violations.sort(java.util.Comparator.comparingInt((Map<String, Object> v) -> ((String) v.get("constraint")).length()).reversed());
        for (int i = 0; i < violations.size(); i++) violations.get(i).put("last", i == violations.size() - 1);
        ctx.put("hasUniqueKeys", !violations.isEmpty());
        ctx.put("uniqueKeyViolations", violations);
    }

    /**
     * {@code hasRules}: some entity has rules, show-when conditions or computed fields — the
     * generated rules runtime (both sides) and its 400.
     */
    public static void putRules(Map<String, Object> ctx, List<EntityDefinition> entities) {
        ctx.put("hasRules", entities.stream().anyMatch(EntityDefinition::hasRowLogic));
    }

    /** A computed field's value in the form, as the field holds it (a date and time as text, not milliseconds). */
    static String computedTs(EntityDefinition entity, FieldDefinition f) {
        var c = com.menora.initializr.fullstack.expr.ExprCheck.check(f.formula(), entity.fields(), null);
        String ts = com.menora.initializr.fullstack.expr.ExprCode.ts(c, "value");
        return switch (f.type()) {
            case LOCAL_DATE_TIME -> "rule.localMoment(" + ts + ")";
            case INSTANT -> "rule.isoInstant(" + ts + ")";
            default -> ts;
        };
    }

    /** A computed field's value in Java, as the entity holds it (a whole number rounded, money to cents, text to its length). */
    static String computedJava(EntityDefinition entity, FieldDefinition f) {
        var c = com.menora.initializr.fullstack.expr.ExprCheck.check(f.formula(), entity.fields(), null);
        String java = com.menora.initializr.fullstack.expr.ExprCode.java(c, "row");
        return switch (f.type()) {
            case INTEGER -> "Expr.toInteger(" + java + ")";
            case LONG -> "Expr.toLong(" + java + ")";
            case MONEY -> "Expr.money(" + java + ")";
            case STRING -> f.length() != null ? "Expr.fit(" + java + ", " + f.length() + ")" : java;
            default -> java;
        };
    }

    /** What the rules class does before it checks: hide (clear) and compute. */
    static void putDerivations(Map<String, Object> view, EntityDefinition entity) {
        List<Map<String, Object>> hidden = new ArrayList<>();
        List<Map<String, Object>> computed = new ArrayList<>();
        for (FieldDefinition f : entity.fields()) {
            if (f.showWhen() != null) {
                var when = com.menora.initializr.fullstack.expr.ExprCheck.check(f.showWhen(), entity.fields(),
                        com.menora.initializr.fullstack.expr.Expr.Type.BOOL);
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("name", f.name());
                h.put("Name", Naming.toPascalCase(f.name()));
                TemplateText.putText(h, "showWhenText", f.showWhen());
                h.put("showWhenJava", com.menora.initializr.fullstack.expr.ExprCode.java(when, "row"));
                h.put("showWhenTs", "rule.applies(" + com.menora.initializr.fullstack.expr.ExprCode.ts(when, "value") + ")");
                hidden.add(h);
            }
            if (f.computed()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", f.name());
                c.put("Name", Naming.toPascalCase(f.name()));
                TemplateText.putText(c, "formulaText", f.formula());
                c.put("formulaJava", computedJava(entity, f));
                c.put("formulaTs", computedTs(entity, f));
                computed.add(c);
            }
        }
        view.put("hiddenFields", hidden);
        view.put("computedFields", computed);
        view.put("hasComputedFields", !computed.isEmpty());
        view.put("hasShowWhenFields", !hidden.isEmpty());
        view.put("formUsesRules", !computed.isEmpty() || !hidden.isEmpty());
    }

    /** An entity's rules as the templates write them: the check (and when) in Java over {@code row} and in TS over {@code value}. */
    static List<Map<String, Object>> ruleViews(EntityDefinition entity) {
        if (entity.allRules().isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 0;
        for (EntityDefinition.Rule rule : entity.allRules()) {
            var check = com.menora.initializr.fullstack.expr.ExprCheck.check(rule.check(), entity.fields(),
                    com.menora.initializr.fullstack.expr.Expr.Type.BOOL);
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("index", index++);
            rv.put("field", rule.field());
            rv.put("messageJava", TemplateText.escapeJavaLiteral(rule.message()));
            TemplateText.putText(rv, "message", rule.message());
            // The form says a required field's message in its own language.
            rv.put("messageTsExpr", rule.requiredWhenShown() ? "t('required')" : "'" + TemplateText.tsLiteralBody(rule.message()) + "'");
            TemplateText.putText(rv, "checkText", rule.check());
            rv.put("checkJava", com.menora.initializr.fullstack.expr.ExprCode.java(check, "row"));
            rv.put("checkTs", com.menora.initializr.fullstack.expr.ExprCode.ts(check, "value"));
            rv.put("hasWhen", rule.when() != null);
            if (rule.when() != null) {
                var when = com.menora.initializr.fullstack.expr.ExprCheck.check(rule.when(), entity.fields(),
                        com.menora.initializr.fullstack.expr.Expr.Type.BOOL);
                TemplateText.putText(rv, "whenText", rule.when());
                rv.put("whenJava", com.menora.initializr.fullstack.expr.ExprCode.java(when, "row"));
                rv.put("whenTs", com.menora.initializr.fullstack.expr.ExprCode.ts(when, "value"));
            }
            out.add(rv);
        }
        return out;
    }

    /** The physical column of a unique key's member: a field's column, or a link's join column. */
    public static String uniqueKeyColumnOf(EntityDefinition entity, String member) {
        for (FieldDefinition f : entity.fields()) {
            if (f.name().equals(member)) return SchemaNames.columnOf(f);
        }
        for (RelationDefinition r : entity.relations()) {
            if (r.fieldName().equals(member)) return SchemaNames.joinColumnOf(r);
        }
        throw new IllegalArgumentException("No field or link '" + member + "' on " + entity.name());
    }

    /**
     * An entity's unique keys as the templates read them: the constraint's name (the schema's),
     * its columns for {@code @UniqueConstraint}, the getters of a row for the demo loader, the
     * names a client knows the members by (a link's is its {@code <field>Id}) and the message.
     */
    static List<Map<String, Object>> uniqueKeyViews(EntityDefinition entity) {
        if (entity.uniqueKeys().isEmpty()) return List.of();
        String entityLabel = entity.label() != null ? entity.label() : Naming.toPascalCase(entity.name());
        String table = com.menora.initializr.fullstack.schema.SchemaBuilder.name(SchemaNames.tableOf(entity));
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 0;
        for (EntityDefinition.UniqueKey key : entity.uniqueKeys()) {
            List<String> columns = new ArrayList<>();
            List<String> getters = new ArrayList<>();
            List<String> clientNames = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (String member : key.fields()) {
                columns.add(uniqueKeyColumnOf(entity, member));
                FieldDefinition field = entity.fields().stream().filter(f -> f.name().equals(member)).findFirst().orElse(null);
                if (field != null) {
                    getters.add("row.get" + Naming.toPascalCase(field.name()) + "()");
                    clientNames.add(field.name());
                    labels.add(field.label() != null && !field.label().isBlank() ? field.label() : Naming.toPascalCase(field.name()));
                } else {
                    RelationDefinition link = entity.relations().stream().filter(r -> r.fieldName().equals(member))
                            .findFirst().orElseThrow();
                    getters.add("row.get" + Naming.toPascalCase(link.fieldName()) + "()");
                    clientNames.add(Naming.toCamelCase(link.fieldName()) + "Id");
                    labels.add(TemplateText.labelOf(link));
                }
            }
            List<String> physical = columns.stream().map(com.menora.initializr.fullstack.schema.SchemaBuilder::name).toList();
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("index", index++);
            kv.put("constraint", com.menora.initializr.fullstack.schema.SchemaBuilder.uniqueKeyName(table, physical)
                    .toLowerCase(Locale.ROOT));
            kv.put("columnsJava", String.join(", ", columns.stream().map(c -> "\"" + SqlNames.javaLiteral(c) + "\"").toList()));
            kv.put("tuple", String.join(", ", getters));
            kv.put("fieldsJava", String.join(", ", clientNames.stream().map(n -> "\"" + n + "\"").toList()));
            kv.put("messageJava", TemplateText.escapeJavaLiteral("Another " + entityLabel + " has the same " + listing(labels)));
            out.add(kv);
        }
        return out;
    }

    /** "a and b", "a, b and c". */
    private static String listing(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    /** {@code optimisticLocking} for one entity: the override, else the project option; writable tables only. */
    public static boolean lockingApplies(EntityDefinition e, boolean projectOpt) {
        Boolean override = e.opts().get("optimisticLocking");
        return !e.readOnly() && e.viewQuery() == null && (override != null ? override : projectOpt);
    }

    /**
     * Whether any entity can edit its table cells in place ({@code inlineEdit}: its override, else
     * the project option; writable, single-key entities) — {@code hasInlineEdit}, which ships the
     * shared {@code InlineCell} component.
     */
    public static void putInlineEdit(Map<String, Object> ctx, List<EntityDefinition> entities) {
        boolean projectOpt = Boolean.TRUE.equals(ctx.get("optScaffoldInlineEdit"));
        ctx.put("hasInlineEdit", entities.stream().anyMatch(e -> !e.readOnly()
                && e.fields().stream().filter(FieldDefinition::primaryKey).count() == 1
                && (e.opts().get("inlineEdit") != null ? e.opts().get("inlineEdit") : projectOpt)));
    }

    /**
     * Whether any entity resolves the {@code xlsxExport} option on (its override, else the project
     * option) — {@code hasXlsxExport}, which ships the backend's {@code XlsxWriter} and the
     * frontend's string for the button.
     */
    public static void putXlsxExport(Map<String, Object> ctx, List<EntityDefinition> entities) {
        boolean projectOpt = Boolean.TRUE.equals(ctx.get("optScaffoldXlsxExport"));
        ctx.put("hasXlsxExport", entities.stream().anyMatch(e ->
                e.opts().get("xlsxExport") != null ? e.opts().get("xlsxExport") : projectOpt));
    }

    /**
     * The entities whose lists keep saved views — each resolves the {@code savedViews} option on
     * (its override, else the project option): {@code hasSavedViews}, which ships the backend's
     * {@code views} package and the frontend's menu, and {@code savedViewLists}, the list names the
     * backend takes a view for (an entity's name in kebab case, as its pages spell it).
     */
    public static void putSavedViews(Map<String, Object> ctx, List<EntityDefinition> entities) {
        List<String> lists = savedViewLists(entities, Boolean.TRUE.equals(ctx.get("optScaffoldSavedViews")));
        ctx.put("hasSavedViews", !lists.isEmpty());
        ctx.put("savedViewLists", lists);
    }

    private static List<String> savedViewLists(List<EntityDefinition> entities, boolean projectOpt) {
        return entities.stream()
                .filter(e -> e.opts().get("savedViews") != null ? e.opts().get("savedViews") : projectOpt)
                .map(e -> Naming.toKebabCase(e.name())).toList();
    }

    /** Text inside a Java string literal, on one line. */
    private static String javaText(Object text) {
        return SqlNames.javaLiteral(String.valueOf(text).replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
    }

    /**
     * The Excel export's columns ({@code xlsxHeaders}, {@code xlsxCells}): every value under the
     * name the UI gives it — not the property's — a link by the row it points at where that row
     * has a name, a file by its name, a set of links by their names.
     */
    @SuppressWarnings("unchecked")
    static void putXlsxColumns(Map<String, Object> ctx) {
        List<String> headers = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) ctx.get("fields")) {
            headers.add(javaText(f.get("label")));
            cells.add("dto." + (Boolean.TRUE.equals(f.get("isAttachment")) ? f.get("nameField") : f.get("name")) + "()");
        }
        for (Map<String, Object> rel : (List<Map<String, Object>>) ctx.get("relations")) {
            headers.add(javaText(rel.get("label")));
            cells.add("dto." + (Boolean.TRUE.equals(rel.get("hasTargetLabel")) ? rel.get("fieldName") + "Label" : rel.get("fkFieldName")) + "()");
        }
        for (Map<String, Object> set : (List<Map<String, Object>>) ctx.getOrDefault("collections", List.of())) {
            headers.add(javaText(set.get("label")));
            cells.add("dto." + set.get("labelsField") + "() == null ? null : String.join(\"; \", dto." + set.get("labelsField") + "())");
        }
        if (Boolean.TRUE.equals(ctx.get("auditApplicable"))) {
            headers.add("Created");
            cells.add("dto.createdAt()");
            headers.add("Updated");
            cells.add("dto.updatedAt()");
            headers.add("Created by");
            cells.add("dto.createdBy()");
            headers.add("Updated by");
            cells.add("dto.updatedBy()");
        }
        ctx.put("xlsxHeaders", headers);
        ctx.put("xlsxCells", cells);
        ctx.put("xlsxSheetName", javaText(ctx.get("entityLabelPlural")));
    }

    public static void putCsvImport(Map<String, Object> ctx, List<EntityDefinition> entities) {
        boolean projectOpt = Boolean.TRUE.equals(ctx.get("optScaffoldCsvImport"));
        ctx.put("hasCsvImport", entities.stream().anyMatch(e -> !e.readOnly()
                && (e.opts().get("csvImport") != null ? e.opts().get("csvImport") : projectOpt)));
    }

    /**
     * The packages of the generated backend that hold a JPA entity and its repository outside
     * {@code <domain>.entity} / {@code .repository}: the attachment table behind FILE and IMAGE
     * fields. Boot's own scan finds them; a datasource config class that names its packages
     * (every database but the default H2) has to be told.
     */
    public static List<String> supportPackages(String packageName, List<EntityDefinition> entities) {
        return supportPackages(packageName, entities, false);
    }

    /** As above, knowing whether the request's {@code savedViews} option is on (an entity may
     *  still turn it on or off for itself). */
    public static List<String> supportPackages(String packageName, List<EntityDefinition> entities, boolean savedViewsOpt) {
        return supportPackages(packageName, entities, savedViewsOpt, false);
    }

    /** As above, knowing the {@code history} option too (its change log is an entity of its own). */
    public static List<String> supportPackages(String packageName, List<EntityDefinition> entities, boolean savedViewsOpt,
                                               boolean historyOpt) {
        return supportPackages(packageName, entities, savedViewsOpt, historyOpt, false);
    }

    /** As above, knowing the {@code events} option too (its outbox is an entity of its own). */
    public static List<String> supportPackages(String packageName, List<EntityDefinition> entities, boolean savedViewsOpt,
                                               boolean historyOpt, boolean eventsOpt) {
        List<String> packages = new ArrayList<>();
        if (entities.stream().anyMatch(e -> historyApplies(e, historyOpt))) {
            packages.add(packageName + ".history");
        }
        if (entities.stream().anyMatch(e -> eventsApply(e, eventsOpt) || notifies(e))) {
            packages.add(packageName + ".events");
        }
        if (entities.stream().flatMap(e -> e.fields().stream()).anyMatch(f -> f.type().isAttachment())) {
            packages.add(packageName + ".attachment");
        }
        if (!savedViewLists(entities, savedViewsOpt).isEmpty()) {
            packages.add(packageName + ".views");
        }
        if (entities.stream().anyMatch(e -> e.workflow() != null && e.workflow().trackMoves())) {
            packages.add(packageName + ".workflow");
        }
        return packages;
    }
}
