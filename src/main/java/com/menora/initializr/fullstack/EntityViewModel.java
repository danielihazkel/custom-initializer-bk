package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.SqlNames;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One entity's view-model for the per-entity templates: naming, fields, relations, list
 * views, forms, workflow, and the options that apply to it. The fields themselves are
 * {@link FieldViewModel}'s.
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
final class EntityViewModel {

    private EntityViewModel() {}

    /**
     * An entity's workflow as the templates read it: the status field (marked read-only and as the
     * workflow's status on its view), its states as Java and TS literals, each transition with its
     * from/to/roles/requires spelled for both, and the assignee and due fields.
     */
    private static void putWorkflow(Map<String, Object> view, EntityDefinition entity, EntityDefinition.Workflow wf,
                                    List<Map<String, Object>> fieldViews) {
        Map<String, Object> status = fieldViews.stream().filter(fv -> wf.statusField().equals(fv.get("name"))).findFirst().orElseThrow();
        status.put("isReadOnly", true);
        status.put("isWorkflowStatus", true);
        status.put("isWorkflowLocked", true);
        // The workflow sets it on every create, whatever was sent: a client is never asked for it
        // (its column keeps its NOT NULL — isNotNullColumn).
        status.put("isRequired", false);
        // A workflow that logs its moves: the demo rows are spread over its states — whatever the
        // status' default — so the histories seeded for them give the log something to show.
        if (wf.trackMoves()) {
            String enumRef = view.get("EntityName") + "." + status.get("enumTypeName");
            status.put("seedExpr", enumRef + ".values()[(i - 1) % " + enumRef + ".values().length]");
        }
        // The assignee changes only by Claim, Release and Reassign: out of the form, PUT, bulk and
        // inline edit and the import, like the status.
        if (wf.assigneeField() != null) {
            fieldViews.stream().filter(fv -> wf.assigneeField().equals(fv.get("name"))).findFirst().ifPresent(fv -> {
                fv.put("isReadOnly", true);
                fv.put("isWorkflowAssignee", true);
                fv.put("isWorkflowLocked", true);
            });
        }
        String pascal = Naming.toPascalCase(entity.name());
        String enumType = (String) status.get("enumTypeName");
        java.util.function.Function<String, String> javaState = s -> pascal + "." + enumType + "." + s;
        java.util.function.Function<java.util.Collection<String>, String> tsList =
                xs -> String.join(", ", xs.stream().map(x -> "'" + x + "'").toList());
        view.put("workflowStatusField", wf.statusField());
        view.put("WorkflowStatusName", status.get("Name"));
        view.put("workflowEnumType", enumType);
        view.put("workflowInitial", wf.initial());
        view.put("workflowInitialJava", javaState.apply(wf.initial()));
        view.put("workflowOpenStatesJava", String.join(", ", wf.openStates().stream().map(javaState).toList()));
        view.put("workflowOpenStatesTs", tsList.apply(wf.openStates()));
        Map<String, String> relationIds = new LinkedHashMap<>();
        for (RelationDefinition r : entity.relations()) relationIds.put(r.fieldName(), Naming.toCamelCase(r.fieldName()) + "Id");
        List<Map<String, Object>> transitions = new ArrayList<>();
        boolean usesRoles = false;
        boolean requiresAny = false;
        for (int i = 0; i < wf.transitions().size(); i++) {
            EntityDefinition.Transition t = wf.transitions().get(i);
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("name", t.name());
            tv.put("labelTs", TemplateText.tsString(t.label() != null ? t.label() : TemplateText.humanizeIdentifier(t.name())));
            tv.put("fromJava", String.join(", ", t.from().stream().map(javaState).toList()));
            tv.put("fromTs", tsList.apply(t.from()));
            tv.put("to", t.to());
            tv.put("toJava", javaState.apply(t.to()));
            tv.put("hasRoles", !t.roles().isEmpty());
            tv.put("rolesJava", String.join(", ", t.roles().stream().map(r -> "\"" + r + "\"").toList()));
            tv.put("rolesTs", tsList.apply(t.roles()));
            List<Map<String, Object>> requires = new ArrayList<>();
            for (String name : t.requires()) {
                Map<String, Object> rv = new LinkedHashMap<>();
                boolean relation = relationIds.containsKey(name);
                rv.put("Name", Naming.toPascalCase(name));
                // The key a 400 reports it under: the form's own (a relation's picker is `<name>Id`).
                rv.put("key", relation ? relationIds.get(name) : name);
                // A file the move asks for is checked like a save's (Attachments.requireAttachable).
                rv.put("isAttachment", !relation && entity.fields().stream()
                        .anyMatch(f -> f.name().equals(name) && f.type().isAttachment()));
                requires.add(rv);
            }
            tv.put("requires", requires);
            tv.put("hasRequires", !requires.isEmpty());
            // The form fields the transition's drawer shows (a relation by its field name, as `only` takes it).
            tv.put("requiresTs", tsList.apply(t.requires()));
            tv.put("last", i == wf.transitions().size() - 1);
            usesRoles |= !t.roles().isEmpty();
            requiresAny |= !requires.isEmpty();
            transitions.add(tv);
        }
        view.put("workflowTransitions", transitions);
        view.put("workflowUsesRoles", usesRoles);
        view.put("workflowRequiresFields", requiresAny);
        view.put("hasAssignee", wf.assigneeField() != null);
        view.put("assigneeField", wf.assigneeField());
        view.put("AssigneeName", wf.assigneeField() == null ? null : Naming.toPascalCase(wf.assigneeField()));
        view.put("hasDue", wf.dueField() != null);
        view.put("dueField", wf.dueField());
        // Demo rows: a third each for the dev user, nobody and someone else, so every inbox queue has rows.
        if (wf.assigneeField() != null) {
            fieldViews.stream().filter(fv -> wf.assigneeField().equals(fv.get("name"))).findFirst()
                    .ifPresent(fv -> fv.put("seedExpr", "i % 3 == 1 ? \"dev-user\" : i % 3 == 2 ? null : \"user\" + i"));
        }
    }

    /** One entry of a {@code /stats} whitelist: enough to splice the column into the generated
     *  {@code switch} and to label it. */
    private static Map<String, Object> statsField(Map<String, Object> fv) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", fv.get("name"));
        out.put("Name", fv.get("Name"));
        TemplateText.putText(out, "label", (String) fv.get("label"));
        out.put("javaType", fv.get("javaType"));
        return out;
    }

    /**
     * The list views the generated page of {@code entity} actually offers: the requested
     * {@code listViews}, intersected with what its fields support — table and cards always, kanban
     * needs a breakdown field (the first enum, else the first boolean) on a writable entity, calendar
     * a temporal field — in the fixed table/cards/kanban/calendar order; {@code [table]} when nothing
     * requested fits. Derived from the entity model alone and shared with
     * {@link FullstackPageValidator}, so a list page's {@code view} is checked against exactly what
     * renders.
     */
    static List<String> emittedListViews(EntityDefinition entity) {
        Set<String> requested = new LinkedHashSet<>(entity.listViews());
        boolean breakdown = entity.fields().stream().anyMatch(f -> f.type().isEnum() || f.type().isBoolean());
        boolean temporal = entity.fields().stream().anyMatch(f -> f.type().isTemporal());
        List<String> emitted = new ArrayList<>();
        if (requested.contains("table")) emitted.add("table");
        if (requested.contains("cards")) emitted.add("cards");
        if (requested.contains("kanban") && breakdown && !entity.readOnly()) emitted.add("kanban");
        if (requested.contains("calendar") && temporal) emitted.add("calendar");
        // A gallery is the rows by their pictures: it needs an image field to show.
        if (requested.contains("gallery") && entity.fields().stream().anyMatch(f -> f.type().isImage() && !f.multiple())) emitted.add("gallery");
        if (emitted.isEmpty()) emitted.add("table");
        return emitted;
    }

    /** A user-supplied string as a single-quoted TS literal (apostrophes, backslashes and line
     *  breaks escaped). */
    /** One group of the form: its title (null: untitled) and its field and relation view-models. */
    private static Map<String, Object> formGroup(String title, List<Map<String, Object>> fields, List<Map<String, Object>> relations) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("groupHasTitle", title != null);
        g.put("groupTitleExpr", title == null ? null : TemplateText.tsString(title));
        g.put("groupFields", fields);
        g.put("groupRelations", relations);
        return g;
    }

    /** The frontend value-input control a bulk-editable field uses in the bulk-edit bar. Mirrors the
     *  form's control matrix: enum/boolean → &lt;select&gt;, temporal → date pickers, numeric → number,
     *  everything else (string/text/uuid) → a plain text input. */
    private static String bulkInputKind(Map<String, Object> fv) {
        if (Boolean.TRUE.equals(fv.get("isEnum"))) return "enum";
        if (Boolean.TRUE.equals(fv.get("isBoolean"))) return "boolean";
        if (Boolean.TRUE.equals(fv.get("isDate"))) return "date";
        if (Boolean.TRUE.equals(fv.get("isDateTime"))) return "datetime";
        if (Boolean.TRUE.equals(fv.get("isNumeric"))) return "number";
        return "text";
    }

    /** A single kanban lane: {@code value} is matched against the grouping field's stringified
     *  value, {@code label} is the column heading. */
    private static Map<String, Object> kanbanColumn(String value, String label, String labelExpr) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("value", value);
        col.put("label", label);
        // The TS expression the page emits for the lane heading: a quoted literal for an enum
        // lane, `t('trueLabel')`/`t('falseLabel')` for the boolean lanes.
        col.put("labelExpr", labelExpr);
        return col;
    }

    static Map<String, Object> of(
            EntityDefinition entity, Map<String, Map<String, Object>> summaries,
            Map<String, List<Map<String, Object>>> inverseByLower) {
        Map<String, Object> view = new LinkedHashMap<>();

        String pascal = Naming.toPascalCase(entity.name());
        String camel = Naming.toCamelCase(entity.name());
        String snake = Naming.toSnakeCase(entity.name());
        String kebab = Naming.toKebabCase(entity.name());
        String pascalPlural = Naming.pluralize(pascal);
        String camelPlural = Naming.pluralize(camel);
        String kebabPlural = Naming.pluralize(kebab);
        String snakePlural = Naming.pluralize(snake);

        view.put("EntityName", pascal);
        // The name as the model spells it — what pages and their links are keyed by.
        view.put("entityDeclaredName", entity.name());
        view.put("entityName", camel);
        view.put("entity_name", snake);
        view.put("entityNameKebab", kebab);
        view.put("EntityNamePlural", pascalPlural);
        view.put("entityNamePlural", camelPlural);
        view.put("entityNamePluralKebab", kebabPlural);
        view.put("entity_name_plural", snakePlural);
        // Human-facing display labels for the generated frontend (nav, page titles, dashboard,
        // dialogs). Optional — fall back to the PascalCase name and its derived plural, mirroring
        // the per-field `label`. `entityLabelPlural` prefers an explicit plural label, then the
        // singular label, then the derived plural (auto-pluralizing a localized label is unsafe).
        String label = (entity.label() != null && !entity.label().isBlank()) ? entity.label() : pascal;
        String labelPlural = (entity.labelPlural() != null && !entity.labelPlural().isBlank())
                ? entity.labelPlural()
                : (entity.label() != null && !entity.label().isBlank()) ? entity.label() : pascalPlural;
        TemplateText.putText(view, "entityLabel", label);
        TemplateText.putText(view, "entityLabelPlural", labelPlural);
        // SQL names go through SqlNames (a mixed-case or quoted schema name survives Boot's naming
        // strategy) and are Java-escaped — every template splices them into a string literal.
        view.put("tableName", SqlNames.javaLiteral(SchemaNames.tableOf(entity)));
        view.put("schema", SqlNames.javaLiteral(SchemaNames.schemaOf(entity)));
        view.put("hasSchema", SchemaNames.schemaOf(entity) != null);
        // Schema-qualified, for native SQL (@SQLDelete, the restore query), which @Table's schema
        // attribute does not reach.
        view.put("tableRef", SqlNames.javaLiteral(SchemaNames.tableRefOf(entity)));
        // Unique keys over several fields: @Table's uniqueConstraints and the demo loader's check.
        List<Map<String, Object>> uniqueKeys = FeatureContext.uniqueKeyViews(entity);
        view.put("uniqueKeys", uniqueKeys);
        view.put("hasUniqueKeys", !uniqueKeys.isEmpty());
        // Rules over several fields: a <Entity>Rules class the service asks before a save, and the
        // same checks in the form's validate<Entity>.
        List<Map<String, Object>> rules = FeatureContext.ruleViews(entity);
        view.put("rules", rules);
        view.put("hasRules", entity.hasRowLogic());
        FeatureContext.putDerivations(view, entity);
        FeatureContext.putLinkedReads(view, entity, summaries);
        view.put("uniqueConstraintsJava", "{" + String.join(", ", uniqueKeys.stream()
                .map(k -> "@UniqueConstraint(name = \"" + k.get("constraint") + "\", columnNames = {" + k.get("columnsJava") + "})")
                .toList()) + "}");
        // Read-only / view flags — gate CRUD in the backend & frontend templates.
        // `isView` swaps @Table for @Immutable/@Subselect; `mutable` gates create/update/delete.
        view.put("readOnly", entity.readOnly());
        view.put("mutable", !entity.readOnly());
        view.put("isView", entity.isView());
        // The security.Constants an endpoint asks for (the controller template, with ldap-auth).
        view.put("readLevel", entity.access().read());
        view.put("writeLevel", entity.access().write());
        view.put("viewQuery", entity.viewQuery());

        List<Map<String, Object>> fieldViews = new ArrayList<>(entity.fields().size());
        Map<String, Object> pkView = null;
        List<Map<String, Object>> pkViews = new ArrayList<>();
        List<Map<String, Object>> nonPkViews = new ArrayList<>();
        Set<String> imports = new TreeSet<>();

        Set<String> keyed = new HashSet<>();
        for (EntityDefinition.UniqueKey key : entity.uniqueKeys()) keyed.addAll(key.fields());
        // Values the entity's rules accept, for the fields they read (the tests' rows take the same).
        Map<String, RuleRows.Pick> ruled = GeneratedTests.solved(entity).picks();
        for (int i = 0; i < entity.fields().size(); i++) {
            FieldDefinition f = entity.fields().get(i);
            Map<String, Object> fv = FieldViewModel.of(pascal, f);
            // Part of a unique key: demo rows vary it by row number, as they do a unique column.
            if (keyed.contains(f.name()) && f.hasDefault() && !f.unique() && !f.primaryKey()) {
                fv.put("seedExpr", FieldViewModel.seedExpression(pascal, f, (String) fv.get("label"), (String) fv.get("enumTypeName"), true));
            }
            RuleRows.Pick pick = ruled.get(f.name());
            if (pick != null && pick.seed() != null) fv.put("seedExpr", pick.seed());
            // Shown only while a condition holds (the form's guard is put once the form's steps are known).
            fv.put("hasShowWhen", f.showWhen() != null);
            if (f.showWhen() != null) {
                var when = com.menora.initializr.fullstack.expr.ExprCheck.check(f.showWhen(), entity,
                        com.menora.initializr.fullstack.expr.Expr.Type.BOOL);
                fv.put("showWhenTs", "rule.applies(" + com.menora.initializr.fullstack.expr.ExprCode.ts(when, "value") + ")");
                fv.put("rowShowWhenTs", "rule.applies(" + com.menora.initializr.fullstack.expr.ExprCode.ts(when, "row") + ")");
                TemplateText.putText(fv, "showWhenText", f.showWhen());
            }
            // Computed on every save: the form shows it, read-only, as it would come out.
            fv.put("isComputed", f.computed());
            if (f.computed()) {
                fv.put("computedTs", FeatureContext.computedTs(entity, f));
                fv.put("rowComputedTs", FeatureContext.computedTs(entity, f, "row"));
                // Never set by a client: locked like a workflow's status (no PUT, bulk or inline edit, no import column).
                fv.put("isReadOnly", true);
                fv.put("isEditable", false);
            }
            // Several files: a list of {id, name} kept in a table of its own (attachment/AttachedFile).
            fv.put("isMultiFile", f.multiple());
            if (f.multiple()) {
                fv.put("maxFiles", f.maxFiles());
                fv.put("filesTable", com.menora.initializr.sql.SqlNames.javaLiteral(SchemaNames.filesTableOf(entity, f)));
                fv.put("javaType", "java.util.List<AttachedFile>");
                fv.put("tsType", "{ id: string; name?: string | null }[]");
                // The single-file shapes do not apply.
                fv.put("isNotNullColumn", false);
                fv.put("cellTs", FieldViewModel.multiFileTs(f, "r." + f.name(), FieldViewModel.Display.CELL));
                fv.put("detailTs", FieldViewModel.multiFileTs(f, "value." + f.name(), FieldViewModel.Display.DETAIL));
                fv.put("seedExpr", f.required() ? "sampleFiles(i, " + f.type().isImage() + ")" : "new java.util.ArrayList<>()");
            }
            fv.put("first", i == 0);
            fv.put("last", i == entity.fields().size() - 1);
            fieldViews.add(fv);
            if (f.primaryKey()) {
                if (pkView == null) pkView = fv;  // first PK drives the single-PK pkField.* back-compat
                // A lightweight copy with its own first/last so composite-key iteration ({{#pkFields}})
                // does not corrupt the field's own first/last (used by Dto's comma logic).
                Map<String, Object> pk = new LinkedHashMap<>();
                pk.put("name", fv.get("name"));
                pk.put("Name", fv.get("Name"));
                pk.put("column", fv.get("column"));
                pk.put("javaType", fv.get("javaType"));
                pk.put("tsType", fv.get("tsType"));
                pkViews.add(pk);
            } else {
                nonPkViews.add(fv);
            }
            if (f.type() != FieldType.ENUM && f.type().javaImport() != null
                    && !f.type().javaImport().startsWith("java.lang.")) {
                imports.add(f.type().javaImport());
            }
        }
        // A workflow's status changes by its transitions only: locked like a read-only field (no PUT,
        // bulk or inline edit, no import column) — on the view, not the field, so demo data still
        // cycles the states — and hidden from the form.
        EntityDefinition.Workflow workflow = entity.workflow();
        view.put("hasWorkflow", workflow != null);
        view.put("tracksMoves", workflow != null && workflow.trackMoves());
        view.put("workflowNotifies", workflow != null && workflow.notifies());
        if (workflow != null) putWorkflow(view, entity, workflow, fieldViews);
        // Re-tag last on nonPkViews
        for (int i = 0; i < nonPkViews.size(); i++) {
            nonPkViews.get(i).put("lastNonPk", i == nonPkViews.size() - 1);
        }
        for (int i = 0; i < pkViews.size(); i++) {
            pkViews.get(i).put("first", i == 0);
            pkViews.get(i).put("last", i == pkViews.size() - 1);
        }

        boolean hasCompositePk = pkViews.size() > 1;
        String keyClassName = pascal + "Id";
        // Pre-built path-variable segment for composite keys, e.g. "/{orderId}/{lineNo}". Built here
        // so the controller template emits a plain string and avoids Mustache triple-brace clashes.
        StringBuilder pkPath = new StringBuilder();
        for (Map<String, Object> pk : pkViews) {
            pkPath.append("/{").append(pk.get("name")).append('}');
        }
        view.put("pkPath", pkPath.toString());
        // Fields eligible for bulk field-edit: non-PK, non-read-only scalar fields (relations are
        // deliberately excluded in v1). Each entry is a shallow copy of the field view-model — so the
        // backend switch reuses the same type flags (isEnum/isIntegral/…) and the frontend gets a
        // `bulkInputKind` picking its value control — with its own `last` for comma/join logic.
        List<Map<String, Object>> bulkUpdatableViews = new ArrayList<>();
        for (Map<String, Object> fv : nonPkViews) {
            if (Boolean.TRUE.equals(fv.get("isReadOnly"))) continue;
            if (Boolean.FALSE.equals(fv.get("bulkEditable"))) continue;
            Map<String, Object> bf = new LinkedHashMap<>(fv);
            bf.put("bulkInputKind", bulkInputKind(fv));
            bulkUpdatableViews.add(bf);
        }
        for (int i = 0; i < bulkUpdatableViews.size(); i++) {
            bulkUpdatableViews.get(i).put("last", i == bulkUpdatableViews.size() - 1);
        }
        view.put("bulkUpdatableFields", bulkUpdatableViews);
        view.put("hasBulkUpdatableFields", !bulkUpdatableViews.isEmpty());

        view.put("fields", fieldViews);
        // One file per field (an id in a column), and several (a list in a table of its own).
        List<Map<String, Object>> attachmentViews = fieldViews.stream()
                .filter(fv -> Boolean.TRUE.equals(fv.get("isAttachment")) && !Boolean.TRUE.equals(fv.get("isMultiFile"))).toList();
        List<Map<String, Object>> multiFileViews = fieldViews.stream()
                .filter(fv -> Boolean.TRUE.equals(fv.get("isMultiFile"))).toList();
        view.put("attachmentFields", attachmentViews);
        view.put("multiFileFields", multiFileViews);
        view.put("hasMultiFileFields", !multiFileViews.isEmpty());
        view.put("hasSingleFileFields", !attachmentViews.isEmpty());
        view.put("hasAttachmentFields", !attachmentViews.isEmpty() || !multiFileViews.isEmpty());
        view.put("hasFieldDefaults", fieldViews.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("hasDefault"))));
        view.put("nonPkFields", nonPkViews);
        view.put("pkField", pkView);
        view.put("pkFields", pkViews);
        view.put("hasCompositePk", hasCompositePk);
        view.put("keyClassName", keyClassName);
        // The repository/service id type and controller path: a single field's Java type, or the
        // generated @IdClass key class when the entity has a composite primary key.
        view.put("pkType", hasCompositePk ? keyClassName
                : (pkView == null ? "Long" : (String) pkView.get("javaType")));
        view.put("hasEnumFields", fieldViews.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("isEnum"))));

        // Text-backed fields drive the generated search Specification (lower()/LIKE) — both STRING
        // and TEXT columns qualify, so the search box appears whenever either is present.
        List<Map<String, Object>> stringFieldViews = new ArrayList<>();
        for (Map<String, Object> fv : fieldViews) {
            if ((Boolean.TRUE.equals(fv.get("isString")) || Boolean.TRUE.equals(fv.get("isText")))
                    && Boolean.TRUE.equals(fv.get("isSearchable"))) {
                stringFieldViews.add(fv);
            }
        }
        for (int i = 0; i < stringFieldViews.size(); i++) {
            stringFieldViews.get(i).put("lastString", i == stringFieldViews.size() - 1);
        }
        view.put("stringFields", stringFieldViews);
        view.put("hasStringFields", !stringFieldViews.isEmpty());

        // Dashboard breakdown: the first ENUM field (else the first BOOLEAN) becomes a grouped
        // bar chart on the generated home page. Low-cardinality columns chart well; free-text and
        // numeric columns don't, so only enum/boolean qualify.
        Map<String, Object> breakdown = null;
        for (Map<String, Object> fv : fieldViews) {
            if (Boolean.TRUE.equals(fv.get("isEnum"))) { breakdown = fv; break; }
        }
        if (breakdown == null) {
            for (Map<String, Object> fv : fieldViews) {
                if (Boolean.TRUE.equals(fv.get("isBoolean"))) { breakdown = fv; break; }
            }
        }
        view.put("hasBreakdown", breakdown != null);
        view.put("breakdownField", breakdown == null ? null : breakdown.get("name"));
        view.put("breakdownLabel", breakdown == null ? null : breakdown.get("Name"));
        boolean breakdownIsEnum = breakdown != null && Boolean.TRUE.equals(breakdown.get("isEnum"));
        view.put("breakdownIsEnum", breakdownIsEnum);
        // The per-entity enum type whose `...Labels` const the dashboard chart reads (null for a
        // boolean breakdown, whose two values need no label map).
        view.put("breakdownEnumTypeName", breakdownIsEnum ? breakdown.get("enumTypeName") : null);

        // Aggregation endpoint (GET /api/x/stats): the columns it may group by, bucket over and
        // reduce. Derived from the entity alone — deliberately not from the page layout, because the
        // backend render context never sees `pages` (putPageContext is frontend-only), and the two
        // render paths have to agree on whether the endpoint exists.
        List<Map<String, Object>> statsGroupByFields = new ArrayList<>();
        List<Map<String, Object>> statsDateFields = new ArrayList<>();
        List<Map<String, Object>> statsNumericFields = new ArrayList<>();
        for (Map<String, Object> fv : fieldViews) {
            boolean isPk = Boolean.TRUE.equals(fv.get("isPrimaryKey"));
            if (Boolean.TRUE.equals(fv.get("isEnum")) || Boolean.TRUE.equals(fv.get("isBoolean"))) {
                // Primary keys included on purpose: `hasBreakdown` above charts the first enum
                // whether or not it is the key, and this whitelist has to be able to answer it.
                Map<String, Object> sf = statsField(fv);
                sf.put("isRelation", false);
                statsGroupByFields.add(sf);
            } else if (!isPk && Boolean.TRUE.equals(fv.get("isTemporal"))) {
                statsDateFields.add(statsField(fv));
            } else if (!isPk && Boolean.TRUE.equals(fv.get("isNumeric"))) {
                statsNumericFields.add(statsField(fv));
            }
        }
        // A MANY_TO_ONE groups by the id it points at: "top customers by revenue". The key is the
        // Java field name, which is also what the frontend sends (and its filter param minus "Id").
        for (RelationDefinition rel : entity.relations()) {
            if (rel.type() != RelationType.MANY_TO_ONE) continue;
            Map<String, Object> target = summaries == null ? null
                    : summaries.get(rel.targetEntity().toLowerCase(Locale.ROOT));
            Map<String, Object> sf = new LinkedHashMap<>();
            sf.put("name", Naming.toCamelCase(rel.fieldName()));
            sf.put("isRelation", true);
            sf.put("targetPkName", target != null ? target.get("pkName") : "id");
            statsGroupByFields.add(sf);
        }
        view.put("statsGroupByFields", statsGroupByFields);
        view.put("hasStatsGroupByFields", !statsGroupByFields.isEmpty());
        view.put("statsDateFields", statsDateFields);
        view.put("hasStatsDateFields", !statsDateFields.isEmpty());
        view.put("statsNumericFields", statsNumericFields);
        view.put("hasStatsNumericFields", !statsNumericFields.isEmpty());
        // Nothing to group, bucket or reduce means the endpoint could only answer a bare count,
        // which the list endpoint's page metadata (?size=1) already gives — so it is not emitted.
        view.put("statsApplicable", !statsGroupByFields.isEmpty() || !statsDateFields.isEmpty()
                || !statsNumericFields.isEmpty());
        // Exposed so a per-page report screen can resolve this entity's Export button: a page
        // context is project context + page view-model and never runs through buildEntityContext,
        // where the opt overrides are normally applied. null = inherit the project flag.
        view.put("csvExportOverride", entity.opts().get("csvExport"));
        view.put("softDeleteOverride", entity.opts().get("softDelete"));
        view.put("optimisticLockingOverride", entity.opts().get("optimisticLocking"));

        // Kanban board view (listView == "kanban"): reuse the breakdown field as the grouping
        // column and turn its distinct values into lanes. Dragging a card writes the new lane value
        // back via the entity's update endpoint, so kanban needs a writable entity — a read-only
        // entity that asked for kanban falls back to the table view below.
        boolean mutableEntity = !entity.readOnly();
        boolean kanbanIsEnum = breakdown != null && Boolean.TRUE.equals(breakdown.get("isEnum"));
        boolean kanbanApplicable = breakdown != null && mutableEntity;
        view.put("kanbanField", breakdown == null ? null : breakdown.get("name"));
        view.put("kanbanLabel", breakdown == null ? null : breakdown.get("Name"));
        view.put("kanbanIsEnum", kanbanIsEnum);
        List<Map<String, Object>> kanbanColumns = new ArrayList<>();
        if (breakdown != null) {
            if (kanbanIsEnum) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> evs = (List<Map<String, Object>>) breakdown.get("enumValues");
                for (Map<String, Object> ev : evs) {
                    String laneLabel = String.valueOf(ev.get("label"));
                    kanbanColumns.add(kanbanColumn(String.valueOf(ev.get("value")), laneLabel,
                            "'" + TemplateText.escapeTsSingleQuoted(laneLabel) + "'"));
                }
            } else { // boolean breakdown — two fixed lanes, headed by the generated app's own i18n words
                kanbanColumns.add(kanbanColumn("true", "True", "t('trueLabel')"));
                kanbanColumns.add(kanbanColumn("false", "False", "t('falseLabel')"));
            }
        }
        for (int i = 0; i < kanbanColumns.size(); i++) {
            kanbanColumns.get(i).put("last", i == kanbanColumns.size() - 1);
        }
        view.put("kanbanColumns", kanbanColumns);
        view.put("kanbanEnumTypeName", kanbanIsEnum ? breakdown.get("enumTypeName") : null);
        // A board over the workflow's status moves a card by the transition between its lanes.
        view.put("kanbanIsWorkflow", workflow != null && breakdown != null && workflow.statusField().equals(breakdown.get("name")));

        // Calendar view (listView == "calendar"): bucket records onto a month grid by their first
        // temporal (LOCAL_DATE / LOCAL_DATE_TIME) field. No writable requirement — it only reads.
        Map<String, Object> calendarFieldView = null;
        for (Map<String, Object> fv : fieldViews) {
            if (Boolean.TRUE.equals(fv.get("isTemporal"))) { calendarFieldView = fv; break; }
        }
        boolean calendarApplicable = calendarFieldView != null;
        view.put("calendarField", calendarFieldView == null ? null : calendarFieldView.get("name"));
        view.put("calendarLabel", calendarFieldView == null ? null : calendarFieldView.get("Name"));

        // Type-aware filter bar: every non-PK enum / boolean / temporal / numeric field becomes a
        // filter control. The view-model carries one entry per filterable field with a kind flag the
        // FilterBar switches on; the backend Specification reads the matching query params.
        List<Map<String, Object>> filterFieldViews = new ArrayList<>();
        for (Map<String, Object> fv : fieldViews) {
            if (Boolean.TRUE.equals(fv.get("isPrimaryKey"))) continue;
            if (!Boolean.TRUE.equals(fv.get("isFilterable"))) continue;
            boolean isEnumF = Boolean.TRUE.equals(fv.get("isEnum"));
            boolean isBoolF = Boolean.TRUE.equals(fv.get("isBoolean"));
            boolean isTemporalF = Boolean.TRUE.equals(fv.get("isTemporal"));
            boolean isNumericF = Boolean.TRUE.equals(fv.get("isNumeric"));
            if (!(isEnumF || isBoolF || isTemporalF || isNumericF)) continue;
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("name", fv.get("name"));
            ff.put("Name", fv.get("Name"));
            TemplateText.putText(ff, "label", (String) fv.get("label"));
            // The Java type the backend Filters carrier / @RequestParam uses for this field. For an
            // enum this is the per-entity enum type (Spring binds the request String to it by name).
            ff.put("javaType", fv.get("javaType"));
            ff.put("isEnumFilter", isEnumF);
            ff.put("isBooleanFilter", isBoolF);
            ff.put("isTemporalFilter", isTemporalF);
            ff.put("isNumericFilter", isNumericF);
            ff.put("isDate", fv.get("isDate"));
            ff.put("isDateTime", fv.get("isDateTime"));
            ff.put("enumValues", fv.get("enumValues"));
            ff.put("enumTypeName", fv.get("enumTypeName"));
            ff.put("isRelationFilter", false);
            filterFieldViews.add(ff);
        }
        // (relation filters are appended below, once the relation view-models exist)

        // The set of views the page actually generates: the user-requested listViews, intersected
        // with what this entity's fields support (table/cards always; kanban needs a breakdown field
        // + a writable entity; calendar needs a temporal field), order preserved. Falls back to
        // [table] if nothing requested is supported. A runtime toggle is emitted only for 2+ views;
        // initialView is the first. viewModeType is the TS union the template seeds useState with.
        List<String> emitted = emittedListViews(entity);
        view.put("viewTable", emitted.contains("table"));
        view.put("viewCards", emitted.contains("cards"));
        view.put("viewKanban", emitted.contains("kanban"));
        view.put("viewCalendar", emitted.contains("calendar"));
        // The gallery's picture: the entity's first image field.
        boolean gallery = emitted.contains("gallery");
        view.put("viewGallery", gallery);
        if (gallery) {
            FieldDefinition picture = entity.fields().stream().filter(f -> f.type().isImage() && !f.multiple()).findFirst().orElseThrow();
            Map<String, Object> pictureView = fieldViews.stream().filter(fv -> picture.name().equals(fv.get("name"))).findFirst().orElseThrow();
            view.put("galleryField", picture.name());
            view.put("galleryLabelTs", pictureView.get("labelTs"));
        }
        view.put("hasViewToggle", emitted.size() > 1);
        view.put("initialView", emitted.get(0));
        StringBuilder union = new StringBuilder();
        for (int i = 0; i < emitted.size(); i++) {
            if (i > 0) union.append(" | ");
            union.append('\'').append(emitted.get(i)).append('\'');
        }
        view.put("viewModeType", union.toString());
        view.put("viewModesTs", "[" + union.toString().replace(" | ", ", ") + "]");
        // The board and the calendar load what they show, not one page of the list: the calendar
        // asks for its month through the date field's own From/To filter (so that field has to be
        // filterable), the board loads every matching row and takes its lane totals from /stats
        // (its lane field is always a stats group). A lane with more rows than it shows opens them
        // in the table, filtered to that lane, when the entity has both and the field is a filter.
        Object calendarName = calendarFieldView == null ? null : calendarFieldView.get("name");
        Object laneName = breakdown == null ? null : breakdown.get("name");
        boolean calendarRanged = emitted.contains("calendar")
                && filterFieldViews.stream().anyMatch(ff -> ff.get("name").equals(calendarName));
        view.put("calendarRanged", calendarRanged);
        view.put("calendarIsDateTime", calendarRanged && Boolean.TRUE.equals(calendarFieldView.get("isDateTime")));
        view.put("wholeViews", calendarRanged || emitted.contains("kanban"));
        view.put("kanbanShowsLane", emitted.contains("kanban") && emitted.contains("table")
                && filterFieldViews.stream().anyMatch(ff -> ff.get("name").equals(laneName)));

        // Relations (MANY_TO_ONE foreign keys). Each resolves its target's PK type/name from the
        // summary lookup so the entity gets a typed @ManyToOne, the DTO exposes the key as
        // <field>Id, and the service can stub the reference on create/update.
        List<Map<String, Object>> relationViews = new ArrayList<>(entity.relations().size());
        for (int i = 0; i < entity.relations().size(); i++) {
            RelationDefinition rel = entity.relations().get(i);
            Map<String, Object> target = summaries == null ? null
                    : summaries.get(rel.targetEntity().toLowerCase(Locale.ROOT));
            String relField = Naming.toCamelCase(rel.fieldName());
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("fieldName", relField);
            rv.put("FieldName", Naming.toPascalCase(rel.fieldName()));
            TemplateText.putText(rv, "label", TemplateText.labelOf(rel));
            rv.put("fkFieldName", relField + "Id");
            rv.put("joinColumn", SqlNames.javaLiteral(SchemaNames.joinColumnOf(rel)));
            rv.put("targetEntity", target != null ? target.get("pascal") : Naming.toPascalCase(rel.targetEntity()));
            rv.put("targetEntityCamel", target != null ? target.get("camel") : Naming.toCamelCase(rel.targetEntity()));
            rv.put("targetEntityKebab", target != null ? target.get("kebab") : Naming.toKebabCase(rel.targetEntity()));
            rv.put("targetEntityKebabPlural", target != null ? target.get("kebabPlural")
                    : Naming.pluralize(Naming.toKebabCase(rel.targetEntity())));
            rv.put("targetPkName", target != null ? target.get("pkName") : "id");
            rv.put("TargetPkName", target != null ? target.get("PkName") : "Id");
            rv.put("targetPkJavaType", target != null ? target.get("pkJavaType") : "Long");
            String targetPkTs = target != null ? (String) target.get("pkTsType") : "number";
            rv.put("targetPkTsType", targetPkTs);
            rv.put("isTargetPkNumeric", "number".equals(targetPkTs));
            // First non-PK string field on the target, shown as the readable option label (else the id).
            Object labelField = target == null ? null : target.get("labelField");
            rv.put("targetLabelField", labelField);
            rv.put("hasTargetLabel", labelField != null);
            rv.put("targetSearchable", target != null && Boolean.TRUE.equals(target.get("labelSearchable")));
            // SQL names for the entity's @Formula `<field>Label` column (a per-row subselect of the
            // target's label column, so the DTO can show a name instead of a raw FK id without an
            // open session — open-in-view is off in the generated app).
            rv.put("targetTableRef", target != null ? target.get("tableRef") : Naming.pluralize(Naming.toSnakeCase(rel.targetEntity())));
            rv.put("targetPkColumn", target != null ? target.get("pkColumn") : "id");
            rv.put("targetLabelColumn", target == null ? null : target.get("labelColumn"));
            rv.put("required", rel.required());
            // What the DTO asks of a client: the same, but for the link of a composed child.
            rv.put("dtoRequired", rel.required());
            rv.put("isManyToOne", rel.type() == RelationType.MANY_TO_ONE);
            rv.put("last", i == entity.relations().size() - 1);
            relationViews.add(rv);
        }
        boolean hasRequiredRelations = relationViews.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("required")));
        view.put("relations", relationViews);
        // The sets of other rows this entity owns (MANY_TO_MANY), apart from its foreign keys: the
        // templates' `relations` loops all mean "a foreign key", and these are not one. The DTO
        // carries the linked rows' ids (idsField) and labels (labelsField).
        List<Map<String, Object>> collectionViews = new ArrayList<>(entity.collections().size());
        for (RelationDefinition c : entity.collections()) {
            Map<String, Object> target = summaries == null ? null
                    : summaries.get(c.targetEntity().toLowerCase(Locale.ROOT));
            String field = Naming.toCamelCase(c.fieldName());
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("fieldName", field);
            cv.put("FieldName", Naming.toPascalCase(c.fieldName()));
            TemplateText.putText(cv, "label", TemplateText.labelOf(c));
            cv.put("idsField", field + "Ids");
            cv.put("labelsField", field + "Labels");
            cv.put("filterName", field + "Id");
            cv.put("targetEntity", target != null ? target.get("pascal") : Naming.toPascalCase(c.targetEntity()));
            cv.put("targetEntityKebabPlural", target != null ? target.get("kebabPlural")
                    : Naming.pluralize(Naming.toKebabCase(c.targetEntity())));
            cv.put("targetPkName", target != null ? target.get("pkName") : "id");
            cv.put("TargetPkName", target != null ? target.get("PkName") : "Id");
            cv.put("targetPkJavaType", target != null ? target.get("pkJavaType") : "Long");
            String targetPkTs = target != null ? (String) target.get("pkTsType") : "number";
            cv.put("targetPkTsType", targetPkTs);
            cv.put("isTargetPkNumeric", "number".equals(targetPkTs));
            Object labelField = target == null ? null : target.get("labelField");
            cv.put("targetLabelField", labelField);
            cv.put("TargetLabelField", labelField == null ? null : Naming.toPascalCase(String.valueOf(labelField)));
            cv.put("hasTargetLabel", labelField != null);
            cv.put("targetSearchable", target != null && Boolean.TRUE.equals(target.get("labelSearchable")));
            // The join table: named, or <entity>_<field> with one <entity>_id column per side.
            cv.put("joinTable", SqlNames.javaLiteral(SchemaNames.joinTableOf(entity, c)));
            cv.put("joinColumn", SqlNames.javaLiteral(SchemaNames.ownerJoinColumnOf(entity, c)));
            cv.put("inverseJoinColumn", SqlNames.javaLiteral(SchemaNames.inverseJoinColumnOf(c)));
            collectionViews.add(cv);
        }
        for (int i = 0; i < collectionViews.size(); i++) {
            collectionViews.get(i).put("last", i == collectionViews.size() - 1);
        }
        view.put("collections", collectionViews);
        view.put("hasCollections", !collectionViews.isEmpty());
        // The form and the details in groups: without sections one untitled group of every field
        // then every relation (the bytes they always had); with sections the entity's key(s) not in
        // one first, each section in its order, then an untitled group of the rest.
        List<Map<String, Object>> formGroups = new ArrayList<>();
        if (entity.formSections().isEmpty()) {
            formGroups.add(formGroup(null, fieldViews, relationViews));
        } else {
            Set<String> placed = new HashSet<>();
            entity.formSections().forEach(sec -> sec.fields().forEach(f -> placed.add(f)));
            formGroups.add(formGroup(null,
                    fieldViews.stream().filter(f -> Boolean.TRUE.equals(f.get("isPrimaryKey")) && !placed.contains(f.get("name"))).toList(),
                    List.of()));
            for (EntityDefinition.FormSection sec : entity.formSections()) {
                List<Map<String, Object>> fs = new ArrayList<>();
                List<Map<String, Object>> rs = new ArrayList<>();
                for (String name : sec.fields()) {
                    fieldViews.stream().filter(f -> name.equals(f.get("name"))).findFirst().ifPresent(fs::add);
                    relationViews.stream().filter(r -> name.equals(r.get("fieldName"))).findFirst().ifPresent(rs::add);
                }
                Map<String, Object> group = formGroup(sec.title(), fs, rs);
                group.put("groupShowExpr", String.join(" || ", sec.fields().stream().map(n -> "show('" + n + "')").toList()));
                formGroups.add(group);
            }
            formGroups.add(formGroup(null,
                    fieldViews.stream().filter(f -> !Boolean.TRUE.equals(f.get("isPrimaryKey")) && !placed.contains(f.get("name"))).toList(),
                    relationViews.stream().filter(r -> !placed.contains(r.get("fieldName"))).toList()));
            formGroups.removeIf(g -> ((List<?>) g.get("groupFields")).isEmpty() && ((List<?>) g.get("groupRelations")).isEmpty());
        }
        view.put("formGroups", formGroups);
        view.put("hasFormSections", !entity.formSections().isEmpty());
        view.put("hasRelations", !relationViews.isEmpty());
        view.put("hasRelationLabels", relationViews.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("hasTargetLabel"))));
        // The frontend validator (model/validate.ts) needs its `blank` helper only when something
        // is required: a client-supplied PK, a required non-PK field, or a required relation —
        // emitting it otherwise would trip the generated project's no-unused-vars lint rule.
        boolean hasBlankChecks = hasRequiredRelations
                || fieldViews.stream().anyMatch(m ->
                        (Boolean.TRUE.equals(m.get("isPrimaryKey")) && !Boolean.TRUE.equals(m.get("isGenerated")))
                        || (!Boolean.TRUE.equals(m.get("isPrimaryKey")) && Boolean.TRUE.equals(m.get("isRequired"))
                                // A list of files is checked by its length, not blank().
                                && !Boolean.TRUE.equals(m.get("isMultiFile"))));
        view.put("hasBlankChecks", hasBlankChecks);
        // Same lint concern for the validator's `t`/`tf` string imports: only import them when at
        // least one check (blank, length, min/max, pattern, email) is actually emitted.
        view.put("hasValidationChecks",
                hasBlankChecks
                        || entity.fields().stream().anyMatch(FieldDefinition::requiredWhenShown)
                        // A list of files: its most, and at least one when required.
                        || entity.fields().stream().anyMatch(FieldDefinition::multiple)
                        || fieldViews.stream().anyMatch(m ->
                                !Boolean.TRUE.equals(m.get("isGenerated")) && !Boolean.TRUE.equals(m.get("isComputed"))
                                && (Boolean.TRUE.equals(m.get("hasLength")) || Boolean.TRUE.equals(m.get("hasMin"))
                                        || Boolean.TRUE.equals(m.get("hasMax")) || Boolean.TRUE.equals(m.get("hasPattern"))
                                        || Boolean.TRUE.equals(m.get("isEmail")))));
        // And for the form (ui/<Entity>Form.tsx): it reads a chrome string only for a generated PK's
        // hint, boolean/enum <option> labels, and the relation picker's loading placeholder.
        // A searchable target is picked by typing (RelationPicker); any other keeps the one-page
        // <select> fed by useOptions, whose placeholder is the chrome string below.
        boolean formUsesOptions = relationViews.stream().anyMatch(m -> !Boolean.TRUE.equals(m.get("targetSearchable")));
        view.put("formUsesOptions", formUsesOptions);
        view.put("formUsesPicker", relationViews.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("targetSearchable"))));
        // The form calls t() for a generated key's hint, a boolean's yes/no and a relation select's
        // "loading" — an enum select reads its labels from <Enum>Options, so it does not count.
        view.put("formUsesStrings",
                formUsesOptions
                        || fieldViews.stream().anyMatch(m ->
                                (Boolean.TRUE.equals(m.get("isPrimaryKey")) && Boolean.TRUE.equals(m.get("isGenerated")))
                                || Boolean.TRUE.equals(m.get("isBoolean"))));

        // Filter by relation FK ("orders of customer 7"): one filter entry per MANY_TO_ONE, keyed
        // by the DTO's <field>Id, equality on the relation's target PK. The frontend renders a
        // <select> fed from the target's list endpoint (labelled like the form's FK picker).
        for (Map<String, Object> rv : relationViews) {
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("name", rv.get("fkFieldName"));
            ff.put("Name", rv.get("FieldName") + "Id");
            TemplateText.putText(ff, "label", (String) rv.get("label"));
            ff.put("javaType", rv.get("targetPkJavaType"));
            ff.put("isEnumFilter", false);
            ff.put("isBooleanFilter", false);
            ff.put("isTemporalFilter", false);
            ff.put("isNumericFilter", false);
            ff.put("isRelationFilter", true);
            ff.put("isCollectionFilter", false);
            ff.put("relationField", rv.get("fieldName"));
            ff.put("targetPkName", rv.get("targetPkName"));
            ff.put("targetEntityKebabPlural", rv.get("targetEntityKebabPlural"));
            ff.put("targetLabelField", rv.get("targetLabelField"));
            ff.put("hasTargetLabel", rv.get("hasTargetLabel"));
            ff.put("targetSearchable", rv.get("targetSearchable"));
            ff.put("isTargetPkNumeric", rv.get("isTargetPkNumeric"));
            ff.put("enumValues", List.of());
            filterFieldViews.add(ff);
        }
        // Filter by a linked row ("posts tagged 3"): to every surface it is a relation filter — the
        // same control, the same <field>Id parameter holding ids — and only the service's predicate
        // differs: the row must have one of them in its set (isCollectionFilter).
        for (Map<String, Object> cv : collectionViews) {
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("name", cv.get("filterName"));
            ff.put("Name", cv.get("FieldName") + "Id");
            TemplateText.putText(ff, "label", (String) cv.get("label"));
            ff.put("javaType", cv.get("targetPkJavaType"));
            ff.put("isEnumFilter", false);
            ff.put("isBooleanFilter", false);
            ff.put("isTemporalFilter", false);
            ff.put("isNumericFilter", false);
            ff.put("isRelationFilter", true);
            ff.put("isCollectionFilter", true);
            ff.put("relationField", cv.get("fieldName"));
            ff.put("targetPkName", cv.get("targetPkName"));
            ff.put("targetEntityKebabPlural", cv.get("targetEntityKebabPlural"));
            ff.put("targetLabelField", cv.get("targetLabelField"));
            ff.put("hasTargetLabel", cv.get("hasTargetLabel"));
            ff.put("targetSearchable", cv.get("targetSearchable"));
            ff.put("isTargetPkNumeric", cv.get("isTargetPkNumeric"));
            ff.put("enumValues", List.of());
            filterFieldViews.add(ff);
        }
        for (int i = 0; i < filterFieldViews.size(); i++) {
            filterFieldViews.get(i).put("last", i == filterFieldViews.size() - 1);
        }
        view.put("filterFields", filterFieldViews);
        view.put("hasFilters", !filterFieldViews.isEmpty());
        // A new record made from a list filtered to one value (status = OPEN, customer 4) starts
        // with that value, so it shows up in the list it was made from. Ranges are left out.
        view.put("newFromFilters", !entity.readOnly() && filterFieldViews.stream().anyMatch(ff ->
                Boolean.TRUE.equals(ff.get("isEnumFilter")) || Boolean.TRUE.equals(ff.get("isBooleanFilter"))
                        || (Boolean.TRUE.equals(ff.get("isRelationFilter")) && !Boolean.TRUE.equals(ff.get("isCollectionFilter")))));
        // An enum or relation filter may hold several values; a new record takes it only when it
        // names one (the helper is emitted only when such a filter exists — the lint rejects it unused).
        view.put("fromFiltersChecksOne", !entity.readOnly() && filterFieldViews.stream().anyMatch(ff ->
                Boolean.TRUE.equals(ff.get("isEnumFilter"))
                        || (Boolean.TRUE.equals(ff.get("isRelationFilter")) && !Boolean.TRUE.equals(ff.get("isCollectionFilter")))));
        // The generated Service builds a JPA Specification when it has either text search or
        // type-aware filters — gates the Specification import / machinery in the template.
        view.put("needsSpecification", !stringFieldViews.isEmpty() || !filterFieldViews.isEmpty());

        // Inverse @OneToMany collections derived from other entities' MANY_TO_ONE relations (opt-in,
        // rendered only when optScaffoldInverse). Exposed read-only — the DTO surfaces a count.
        List<Map<String, Object>> inverseViews = inverseByLower == null ? List.of()
                : inverseByLower.getOrDefault(entity.name().toLowerCase(Locale.ROOT), List.of());
        view.put("inverseRelations", inverseViews);
        view.put("hasInverseRelations", !inverseViews.isEmpty());

        // Aggregate flags so the DTO template only imports a Bean Validation constraint it
        // actually uses. @NotNull is skipped on a generated PK (it is null until persisted);
        // a required relation's FK id, however, does carry @NotNull.
        view.put("hasNotNullFields", hasRequiredRelations || fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("isRequired")) && !Boolean.TRUE.equals(m.get("isGenerated"))
                        // A required list of files: no list on an update keeps the files (the service checks the rest).
                        && !Boolean.TRUE.equals(m.get("isMultiFile"))));
        view.put("hasSizeFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasLength"))));
        // @Min/@Max apply to integral fields; @DecimalMin/@DecimalMax to BigDecimal; @Pattern/@Email
        // to strings. Each gates its own DTO import so we never import an unused constraint.
        view.put("hasMinFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMin")) && Boolean.TRUE.equals(m.get("isIntegral"))));
        view.put("hasMaxFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMax")) && Boolean.TRUE.equals(m.get("isIntegral"))));
        view.put("hasDecimalMinFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMin")) && Boolean.TRUE.equals(m.get("isBigDecimal"))));
        view.put("hasDecimalMaxFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMax")) && Boolean.TRUE.equals(m.get("isBigDecimal"))));
        view.put("hasPatternFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasPattern"))));
        view.put("hasEmailFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("isEmail"))));

        List<Map<String, Object>> importViews = new ArrayList<>(imports.size());
        for (String imp : imports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", imp);
            importViews.add(m);
        }
        view.put("imports", importViews);
        return view;
    }
}
