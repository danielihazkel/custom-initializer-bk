package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.SqlNames;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One field's view-model, and how a value of each field type is shown (cell, detail, card),
 * imported, bulk-edited, defaulted and seeded.
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
final class FieldViewModel {

    private FieldViewModel() {}

    static Map<String, Object> of(String entityPascal, FieldDefinition f) {
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("name", f.name());
        String pascalName = Naming.toPascalCase(f.name());
        fv.put("Name", pascalName);
        fv.put("column", SqlNames.javaLiteral(SchemaNames.columnOf(f)));
        // A SELECT-backed view maps a field by its projected label (the field name): passed through
        // SqlNames so a camelCase alias is not turned into snake_case by the naming strategy.
        fv.put("viewColumn", SqlNames.javaLiteral(SqlNames.physical(f.name(), false, null)));
        // Human-facing display override for the generated UI (table header, form label, filter
        // chip, detail row). Defaults to the PascalCase name when no custom label was supplied —
        // done here because Mustache has no default operator.
        TemplateText.putText(fv, "label", (f.label() != null && !f.label().isBlank()) ? f.label() : pascalName);
        // A hint under the field's input in the form.
        TemplateText.putText(fv, "help", f.help());
        fv.put("hasHelp", f.help() != null);
        // Locked-after-create read-only: the form disables it on edit and Service.update skips it.
        fv.put("isReadOnly", f.readOnly());
        // A workflow's status: set by its transitions only (see putWorkflow).
        fv.put("isWorkflowStatus", false);
        // …and its assignee by claiming and reassigning; either is out of the form.
        fv.put("isWorkflowAssignee", false);
        fv.put("isWorkflowLocked", false);
        fv.put("isEditable", !f.readOnly());

        String javaType;
        String enumTypeName = null;
        if (f.type() == FieldType.ENUM) {
            enumTypeName = entityPascal + Naming.toPascalCase(f.name()) + "Type";
            javaType = enumTypeName;
        } else {
            javaType = f.type().javaType();
        }
        fv.put("javaType", javaType);
        fv.put("tsType", f.type().tsType());
        fv.put("enumTypeName", enumTypeName);

        boolean isIntegral = f.type() == FieldType.LONG || f.type() == FieldType.INTEGER;
        // MONEY is a BigDecimal to every template that reads this flag (@DecimalMin, step="any", …).
        boolean isBigDecimal = f.type().isDecimal();
        fv.put("isPrimaryKey", f.primaryKey());
        fv.put("isGenerated", f.generated());
        fv.put("isRequired", f.required());
        // The column's own NOT NULL: `isRequired` is what a client must send, which is not the
        // same for a value the server always sets (a workflow's status).
        fv.put("isNotNullColumn", f.required());
        fv.put("isUnique", f.unique());
        fv.put("isString", f.type().isString());
        fv.put("isText", f.type().isText());
        fv.put("isUuid", f.type().isUuid());
        fv.put("isNumeric", f.type().isNumeric());
        fv.put("isIntegral", isIntegral);
        fv.put("isBigDecimal", isBigDecimal);
        fv.put("isBoolean", f.type().isBoolean());
        fv.put("isTemporal", f.type().isTemporal());
        fv.put("isDate", f.type() == FieldType.LOCAL_DATE);
        fv.put("isDateTime", f.type() == FieldType.LOCAL_DATE_TIME);
        fv.put("isEnum", f.type().isEnum());
        fv.put("isMoney", f.type().isMoney());
        fv.put("currency", f.currency());
        fv.put("isMarkdown", f.type().isMarkdown());
        fv.put("isTime", f.type().isTime());
        fv.put("isInstant", f.type().isInstant());
        fv.put("bulkEditable", f.type().bulkEditable());
        // A file or image: the field holds an attachment id, and the entity reads the file's name
        // beside it (nameField) for the list, the detail and the form to show.
        fv.put("isAttachment", f.type().isAttachment());
        fv.put("isImage", f.type().isImage());
        // Several files (maxFiles): the entity view puts what changes with it (EntityViewModel).
        fv.put("isMultiFile", f.multiple());
        fv.put("nameField", f.name() + "Name");
        fv.put("NameField", pascalName + "Name");
        // The TS that draws a non-null value of this field: a list cell reads it off `r`, the
        // detail view off `value`.
        fv.put(FIELD_TYPE_KEY, f.type());
        // In the controller's SORTABLE whitelist and a column the table sorts by.
        fv.put("isSortable", f.type().sortable());
        fv.put("cellTs", cellTs(f, enumTypeName));
        fv.put("detailTs", detailTs(f, enumTypeName));
        // What a template would otherwise spell out as a chain of type flags — where a type the
        // chain does not know renders nothing, and the generated file is broken. These come from
        // switches the compiler checks, so a new FieldType cannot be forgotten here.
        fv.put("inputType", inputType(f));
        fv.put("importKind", importKind(f.type()));
        fv.put("bulkCoerceJava", bulkCoerceJava(f.type(), javaType, entityPascal, enumTypeName));
        fv.put("hasLength", f.length() != null);
        fv.put("length", f.length());
        // Numeric bounds (rendered as @Min/@Max on integral types, @DecimalMin/@DecimalMax on BigDecimal).
        // Rendered as strings so the same value drops into @Min(0) / @DecimalMin(value = "0.5") /
        // min="0.5" / Number(x) < 0.5 verbatim: integral bounds print as plain integers, decimal
        // bounds in plain (non-scientific) notation.
        fv.put("hasMin", f.min() != null);
        fv.put("min", boundLiteral(f.min(), isBigDecimal));
        fv.put("hasMax", f.max() != null);
        fv.put("max", boundLiteral(f.max(), isBigDecimal));
        // String constraints. The pattern is injected into Java (@Pattern(regexp="..")) and JS
        // ("..") string literals, so backslashes and double-quotes are escaped once here — the
        // C-style escaping is valid in both languages.
        boolean hasPattern = f.pattern() != null && !f.pattern().isBlank();
        fv.put("hasPattern", hasPattern);
        fv.put("pattern", f.pattern());
        fv.put("patternEscaped", hasPattern ? TemplateText.escapeStringLiteral(f.pattern()) : null);
        fv.put("isEmail", f.email());
        // Per-field search/filter opt-out. Consulted by the stringFields / filterFields collection
        // loops, which gate on field type first — so these flags are inert on ineligible types.
        fv.put("isSearchable", f.searchable());
        fv.put("isFilterable", f.filterable());

        // Optional default value (validated + canonicalized by FullstackRequestValidator), rendered
        // as the entity field's Java initializer (`= …`, resolved inside the entity class so a nested
        // enum is unqualified) and as the TS literal the form seeds a new record with.
        fv.put("hasDefault", f.hasDefault());
        fv.put("defaultValue", f.defaultValue());
        fv.put("defaultJava", f.hasDefault() ? defaultJavaExpression(f, enumTypeName) : null);
        fv.put("defaultTs", f.hasDefault() ? defaultTsLiteral(f) : null);

        // Java expression the demo-data loader (optScaffoldSeedData) assigns to this field for row
        // number `i` (1-based). Unique per row where uniqueness matters (strings carry i, integral
        // values are i within any min/max bounds), so seeded rows never clash on unique columns.
        // A declared default wins for non-key, non-unique fields.
        fv.put("seedExpr", seedExpression(entityPascal, f, (String) fv.get("label"), enumTypeName));

        if (f.type() == FieldType.ENUM) {
            List<Map<String, Object>> values = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            boolean anyCustomLabel = false;
            for (String v : f.enumValues()) {
                String constant = v.toUpperCase(Locale.ROOT);
                if (!seen.add(constant)) continue;
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("value", constant);
                // Display label: the user's, else a humanized constant ("IN_PROGRESS" -> "In progress").
                // `label` is the raw text; `labelTs` is safe inside a single-quoted TS literal.
                String custom = f.enumLabels().get(constant);
                String label = custom != null ? custom : TemplateText.humanizeConstant(constant);
                anyCustomLabel |= custom != null;
                ev.put("label", label);
                ev.put("labelTs", TemplateText.escapeTsSingleQuoted(label));
                values.add(ev);
            }
            for (int i = 0; i < values.size(); i++) {
                values.get(i).put("last", i == values.size() - 1);
            }
            fv.put("enumValues", values);
            fv.put("hasEnumLabels", anyCustomLabel);
        } else {
            fv.put("enumValues", List.of());
            fv.put("hasEnumLabels", false);
        }
        return fv;
    }

    /**
     * The Java initializer expression for a field's validated {@code defaultValue}. {@code enumRef}
     * is the enum type reference to use — the bare nested name inside the entity class, or the
     * {@code Entity.EnumType} form from outside it (demo-data loader).
     */
    /** The {@code type} of the plain {@code <input>} a field gets in the generated form. */
    static String inputType(FieldDefinition f) {
        return switch (f.type()) {
            case LONG, INTEGER, BIG_DECIMAL -> "number";
            case LOCAL_DATE -> "date";
            case LOCAL_DATE_TIME -> "datetime-local";
            case STRING -> f.email() ? "email" : "text";
            case MONEY -> "number";
            // Drawn by the AttachmentField component; no input of its own.
            case FILE, IMAGE -> "text";
            case LOCAL_TIME -> "time";
            // The browser's own zone; the form converts to and from UTC (format.ts).
            case INSTANT -> "datetime-local";
            // A textarea, a select — or a text box, for a UUID: no input type of their own.
            case TEXT, MARKDOWN, UUID, BOOLEAN, ENUM -> "text";
        };
    }

    /** The helpers of the generated {@code src/shared/lib/format.ts} a field's cell or detail calls. */
    static List<String> formatHelpers(FieldType type) {
        return switch (type) {
            case MONEY -> List.of("formatMoney");
            case INSTANT -> List.of("formatInstant");
            case FILE, IMAGE -> List.of("attachmentUrl");
            default -> List.of();
        };
    }

    /** What a list cell prints for a non-null value (the row is {@code r}). */
    /** A field view's {@link FieldType}, for the code that builds expressions from views (board
     *  and inbox cards). Not referenced by any template. */
    static final String FIELD_TYPE_KEY = "__type";

    static String multiFileTs(FieldDefinition f, String v, Display where) {
        return multiFileTs(f.type(), v, where);
    }

    /**
     * What a field that holds several files ({@code maxFiles}) shows: its value {@code v} is a list
     * of {@code {id, name}}, never null (the caller's null check stays harmless). A cell and a
     * card: the names, or the first picture's thumbnail and how many more; the details: every
     * file, each opening its original.
     */
    static String multiFileTs(FieldType type, String value, Display where) {
        boolean image = type.isImage();
        // The type lets the list be absent: drawn as none.
        String v = "(" + value + " ?? [])";
        String empty = "(" + v + ".length === 0 ? '—' : ";
        return switch (where) {
            case CELL, CARD -> image
                    ? empty + "<span className=\"inline-flex items-center gap-1\"><img src={attachmentUrl(" + v
                            + "[0].id, true)} alt={" + v + "[0].name ?? ''} loading=\"lazy\" className=\""
                            + (where == Display.CELL ? "h-8 w-8" : "h-10 w-10") + " rounded object-cover\" />{" + v
                            + ".length > 1 && <span className=\"text-xs text-muted\">+{" + v + ".length - 1}</span>}</span>)"
                    : where == Display.CARD
                            // A card is itself a link: the names alone.
                            ? empty + v + ".map(x => x.name ?? x.id).join(', '))"
                            // A row's names, each opening its file, as a single file's cell does.
                            : empty + "<span>{" + v + ".map((x, k) => <a key={x.id} href={attachmentUrl(x.id)} target=\"_blank\" "
                                    + "rel=\"noopener noreferrer\" onClick={e => e.stopPropagation()} "
                                    + "className=\"font-medium text-brand hover:underline\">{x.name ?? x.id}{k < " + v
                                    + ".length - 1 ? ', ' : ''}</a>)}</span>)";
            case DETAIL -> image
                    ? "<div className=\"flex flex-wrap gap-2\">{" + v + ".map(x => <a key={x.id} href={attachmentUrl(x.id)} "
                            + "target=\"_blank\" rel=\"noopener noreferrer\"><img src={attachmentUrl(x.id, true)} alt={x.name ?? ''} "
                            + "className=\"h-24 w-24 rounded-lg border border-border object-cover\" /></a>)}</div>"
                    : "<ul className=\"space-y-1\">{" + v + ".map(x => <li key={x.id}><a href={attachmentUrl(x.id)} "
                            + "target=\"_blank\" rel=\"noopener noreferrer\" className=\"font-medium text-brand hover:underline\">"
                            + "{x.name ?? x.id}</a></li>)}</ul>";
        };
    }

    /** Where a value is shown: a table cell, the details, or a board card / inbox row. */
    enum Display { CELL, DETAIL, CARD }

    static String cellTs(FieldDefinition f, String enumTypeName) {
        return displayTs(f.type(), "r." + f.name(), enumTypeName, f.currency(), Display.CELL);
    }

    static String detailTs(FieldDefinition f, String enumTypeName) {
        return displayTs(f.type(), "value." + f.name(), enumTypeName, f.currency(), Display.DETAIL);
    }

    /**
     * How a non-null value of a field reads, as a TS expression over {@code v} ({@code r.status},
     * {@code value.status}). One function for the table cell, the details and a card, so a value
     * reads the same wherever it is shown: an enum by its label, a boolean in the app's words, a
     * date for the app's locale, money in its currency, a file by its name. They used to be three
     * lists that had drifted apart — a table printed {@code true} and an ISO date where the
     * details worded and formatted them, and a card printed a file's id.
     *
     * <p>The switch has no {@code default}: a new {@link FieldType} does not compile until it
     * says how it is shown. The helpers it calls are imported per file — {@code t} from the i18n
     * module, the rest from {@code @shared/ui/format} (see {@code formatImports}).
     */
    static String displayTs(FieldType type, String v, String enumTypeName, String currency, Display where) {
        return switch (type) {
            case ENUM -> enumTypeName + "Labels[" + v + "]";
            case BOOLEAN -> "(" + v + " ? t('trueLabel') : t('falseLabel'))";
            case LOCAL_DATE -> "formatDate(" + v + ")";
            case LOCAL_DATE_TIME -> "formatDateTime(" + v + ")";
            case MONEY -> "formatMoney(" + v + ", '" + currency + "')";
            case INSTANT -> "formatInstant(" + v + ")";
            case TEXT -> where == Display.DETAIL
                    ? "<span className=\"whitespace-pre-wrap break-words\">{String(" + v + ")}</span>"
                    : "String(" + v + ")";
            case MARKDOWN -> where == Display.DETAIL ? "<Markdown source={String(" + v + ")} />" : "String(" + v + ")";
            // The file's name, opening the file (a card is itself a link: the name alone there).
            case FILE -> switch (where) {
                case CELL -> "<a href={attachmentUrl(" + v + ")} target=\"_blank\" rel=\"noopener noreferrer\" "
                        + "onClick={e => e.stopPropagation()} className=\"font-medium text-brand hover:underline\">{"
                        + v + "Name ?? String(" + v + ")}</a>";
                case DETAIL -> "<a href={attachmentUrl(" + v + ")} target=\"_blank\" rel=\"noopener noreferrer\" "
                        + "className=\"font-medium text-brand hover:underline\">{" + v + "Name ?? String(" + v + ")}</a>";
                case CARD -> "(" + v + "Name ?? '\u2014')";
            };
            // A small picture in a row, a larger one (opening the file) in the details — each drawn
            // from the server's thumbnail (attachmentUrl(id, true)), the link the original.
            case IMAGE -> switch (where) {
                case CELL -> "<img src={attachmentUrl(" + v + ", true)} alt={" + v + "Name ?? ''} "
                        + "loading=\"lazy\" className=\"h-8 w-8 rounded object-cover\" />";
                case DETAIL -> "<a href={attachmentUrl(" + v + ")} target=\"_blank\" rel=\"noopener noreferrer\">"
                        + "<img src={attachmentUrl(" + v + ", true)} alt={" + v + "Name ?? ''} "
                        + "className=\"max-h-48 rounded-lg border border-border\" /></a>";
                // A card is itself a link, so the picture alone — small, like a table row's.
                case CARD -> "<img src={attachmentUrl(" + v + ", true)} alt={" + v + "Name ?? ''} "
                        + "loading=\"lazy\" className=\"h-10 w-10 rounded object-cover\" />";
            };
            case STRING, LONG, INTEGER, LOCAL_TIME, BIG_DECIMAL, UUID -> "String(" + v + ")";
        };
    }

    /** How the generated CSV import reads a column of this type ({@code csv.ts} {@code coerce}). */
    static String importKind(FieldType type) {
        return switch (type) {
            case LONG, INTEGER -> "integer";
            case BIG_DECIMAL, MONEY -> "number";
            case BOOLEAN -> "boolean";
            case LOCAL_DATE -> "date";
            case LOCAL_DATE_TIME -> "datetime";
            case ENUM -> "enum";
            // A time and an instant are sent as typed; the server's own parse answers a bad one.
            case STRING, TEXT, MARKDOWN, UUID, LOCAL_TIME, INSTANT -> "string";
            // Never offered to an import (a CSV carries no files); the id's own type, for completeness.
            case FILE, IMAGE -> "string";
        };
    }

    /**
     * The Java expression that turns the bulk-update endpoint's raw JSON scalar ({@code value}, an
     * {@code Object} known not to be null) into the field's type.
     */
    static String bulkCoerceJava(FieldType type, String javaType, String entityPascal, String enumTypeName) {
        return switch (type) {
            case ENUM -> entityPascal + "." + enumTypeName + ".valueOf(value.toString())";
            case BOOLEAN -> "Boolean.valueOf(value.toString())";
            case BIG_DECIMAL, MONEY -> "new java.math.BigDecimal(value.toString())";
            case LOCAL_TIME -> "java.time.LocalTime.parse(value.toString())";
            case INSTANT -> "java.time.Instant.parse(value.toString())";
            case LONG, INTEGER -> javaType + ".valueOf(value.toString())";
            case UUID, FILE, IMAGE -> "java.util.UUID.fromString(value.toString())";
            case LOCAL_DATE -> "java.time.LocalDate.parse(value.toString())";
            case LOCAL_DATE_TIME -> "java.time.LocalDateTime.parse(value.toString())";
            case STRING, TEXT, MARKDOWN -> "value.toString()";
        };
    }

    static String defaultJavaExpression(FieldDefinition f, String enumRef) {
        return defaultJavaExpression(f, enumRef, false);
    }

    /**
     * A field's default as a Java expression. In the entity, where the field's type is imported, the
     * type is named bare; {@code qualified} spells it out for a file that imports none of the field
     * types — the demo-data loader, which did not compile for a decimal or date field with a default.
     */
    static String defaultJavaExpression(FieldDefinition f, String enumRef, boolean qualified) {
        String v = f.defaultValue();
        return switch (f.type()) {
            case STRING, TEXT, MARKDOWN -> "\"" + TemplateText.escapeJavaLiteral(v) + "\"";
            case LONG -> v + "L";
            case INTEGER -> v;
            case BIG_DECIMAL, MONEY -> "new " + (qualified ? "java.math." : "") + "BigDecimal(\"" + v + "\")";
            case LOCAL_TIME -> "java.time.LocalTime.parse(\"" + v + "\")";
            case INSTANT -> "java.time.Instant.parse(\"" + v + "\")";
            case BOOLEAN -> v;
            case LOCAL_DATE -> (qualified ? "java.time." : "") + "LocalDate.parse(\"" + v + "\")";
            case LOCAL_DATE_TIME -> (qualified ? "java.time." : "") + "LocalDateTime.parse(\"" + v + "\")";
            case UUID -> "java.util.UUID.fromString(\"" + v + "\")";
            // The validator refuses a default on an attachment; nothing to initialize.
            case FILE, IMAGE -> "null";
            case ENUM -> enumRef + "." + v;
        };
    }

    /** The TypeScript literal for a field's validated {@code defaultValue}: numbers and booleans
     *  bare, everything else (strings, temporal ISO forms, UUIDs, enum constants) single-quoted. */
    static String defaultTsLiteral(FieldDefinition f) {
        String v = f.defaultValue();
        return switch (f.type()) {
            case LONG, INTEGER, BIG_DECIMAL, MONEY, BOOLEAN -> v;
            default -> "'" + v.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
        };
    }

    /** See {@code seedExpr} in {@link #of}. The helper calls ({@code label}, {@code text},
     *  {@code bounded}) are static methods of the generated {@code DemoDataLoader}. */
    static String seedExpression(String entityPascal, FieldDefinition f, String label, String enumTypeName) {
        return seedExpression(entityPascal, f, label, enumTypeName, f.unique());
    }

    /** @param distinct every row must differ in it (a unique column, a member of a unique key) */
    static String seedExpression(String entityPascal, FieldDefinition f, String label, String enumTypeName,
                                 boolean distinct) {
        // A declared default is the natural demo value — except on keys and unique columns, where
        // every row must differ, so those keep the row-numbered expressions below.
        if (f.hasDefault() && !f.primaryKey() && !distinct) {
            return defaultJavaExpression(f, entityPascal + "." + enumTypeName, true);
        }
        String lbl = TemplateText.escapeStringLiteral(label == null ? f.name() : label);
        return switch (f.type()) {
            case ENUM -> entityPascal + "." + enumTypeName + ".values()[(i - 1) % "
                    + entityPascal + "." + enumTypeName + ".values().length]";
            case STRING -> f.email()
                    ? "\"user\" + i + \"@example.com\""
                    : "label(\"" + lbl + "\", i, " + (f.length() != null ? f.length() : "Integer.MAX_VALUE") + ")";
            case TEXT, MARKDOWN -> "text(\"" + lbl + "\", i)";
            case LONG -> "bounded(i, " + longLiteral(f.min()) + ", " + longLiteral(f.max()) + ")";
            case INTEGER -> "(int) bounded(i, " + longLiteral(f.min()) + ", " + longLiteral(f.max()) + ")";
            case BIG_DECIMAL, MONEY -> "java.math.BigDecimal.valueOf(bounded(i, " + longLiteral(f.min()) + ", "
                    + longLiteral(f.max()) + "))";
            case LOCAL_TIME -> "java.time.LocalTime.of(8 + (i % 10), 0)";
            case INSTANT -> "java.time.Instant.now().minus(java.time.Duration.ofHours(i))";
            case BOOLEAN -> "i % 2 == 0";
            case LOCAL_DATE -> "java.time.LocalDate.now().minusDays(i)";
            case LOCAL_DATE_TIME -> "java.time.LocalDateTime.now().minusHours(i)";
            case UUID -> "java.util.UUID.randomUUID()";
            // Demo rows carry no files — unless the field demands one, and then a placeholder
            // the loader stores (its sampleFile / sampleImage).
            case FILE -> f.required() ? "sampleFile(i)" : "null";
            case IMAGE -> f.required() ? "sampleImage(i)" : "null";
        };
    }

    private static String longLiteral(BigDecimal v) {
        return v == null ? "null" : v.setScale(0, RoundingMode.DOWN).toPlainString() + "L";
    }

    /** A bound in the form the templates splice into Java and TS source (see the fv.put above). */
    private static String boundLiteral(BigDecimal v, boolean decimal) {
        if (v == null) return null;
        return decimal ? v.toPlainString() : v.setScale(0, RoundingMode.DOWN).toPlainString();
    }
}
