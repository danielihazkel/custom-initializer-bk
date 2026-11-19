package com.menora.initializr.fullstack.expr;

import com.menora.initializr.fullstack.FieldDefinition;
import com.menora.initializr.fullstack.FieldType;
import com.menora.initializr.fullstack.expr.Expr.ExprException;
import com.menora.initializr.fullstack.expr.Expr.Type;
import com.menora.initializr.fullstack.expr.ExprCheck.Checked;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The model's expression language: what it reads, what it refuses, what it writes, what it means. */
class ExprTest {

    private static FieldDefinition field(String name, FieldType type, String... enumValues) {
        return new FieldDefinition(name, type, false, false, false, false, null, null, null, null, false,
                List.of(enumValues), true, true);
    }

    private static final List<FieldDefinition> FIELDS = List.of(
            field("startsOn", FieldType.LOCAL_DATE),
            field("endsOn", FieldType.LOCAL_DATE),
            field("quantity", FieldType.INTEGER),
            field("unitPrice", FieldType.MONEY),
            field("title", FieldType.STRING),
            field("urgent", FieldType.BOOLEAN),
            field("status", FieldType.ENUM, "OPEN", "REJECTED"),
            field("reason", FieldType.TEXT),
            field("receipt", FieldType.FILE),
            field("placedAt", FieldType.LOCAL_DATE_TIME));

    private static Checked check(String text) {
        return ExprCheck.check(text, FIELDS, Type.BOOL);
    }

    private static Object eval(String text, Map<String, Object> values) {
        return ExprEval.eval(ExprCheck.check(text, FIELDS, null), values, LocalDate.of(2026, 3, 10));
    }

    @Test
    void precedenceIsTheUsualOne() {
        Checked c = ExprCheck.check("quantity * unitPrice + 1 > 10 and not urgent or title == 'x'", FIELDS, Type.BOOL);
        assertThat(c.refs()).containsExactly("quantity", "unitPrice", "urgent", "title");
        assertThat(ExprCode.java(c, "row")).isEqualTo("Expr.or(Expr.and(Expr.gt(Expr.add(Expr.mul(Expr.num(row.getQuantity()), "
                + "Expr.num(row.getUnitPrice())), new java.math.BigDecimal(\"1\")), new java.math.BigDecimal(\"10\")), "
                + "Expr.not(row.getUrgent())), Expr.eq(row.getTitle(), \"x\"))");
        assertThat(ExprCode.ts(c, "value")).isEqualTo("rule.or(rule.and(rule.gt(rule.add(rule.mul(rule.num(value.quantity), "
                + "rule.num(value.unitPrice)), 1), 10), rule.not(rule.bool(value.urgent))), rule.eq(rule.text(value.title), 'x'))");
    }

    @Test
    void dates_enums_andFunctionsAreWrittenForEachSide() {
        Checked c = check("endsOn <= startsOn + 30 && status = 'rejected' && daysBetween(startsOn, endsOn) < 7 && !isEmpty(receipt)");
        String java = ExprCode.java(c, "row");
        assertThat(java).contains("Expr.le(row.getEndsOn(), Expr.plusDays(row.getStartsOn(), new java.math.BigDecimal(\"30\")))")
                // An enum literal is spelled as its constant.
                .contains("Expr.eq(Expr.name(row.getStatus()), \"REJECTED\")")
                .contains("Expr.daysBetween(row.getStartsOn(), row.getEndsOn())")
                .contains("Expr.isEmpty(row.getReceipt())");
        String ts = ExprCode.ts(c, "value");
        assertThat(ts).contains("rule.le(rule.date(value.endsOn), rule.plusDays(rule.date(value.startsOn), 30))")
                .contains("rule.eq(rule.text(value.status), 'REJECTED')")
                .contains("rule.daysBetweenDates(rule.date(value.startsOn), rule.date(value.endsOn))")
                .contains("rule.isEmpty(value.receipt)");
        assertThat(ExprCode.java(check("placedAt < now() and endsOn > date('2025-01-31')"), "row"))
                .contains("Expr.lt(row.getPlacedAt(), Expr.now())").contains("java.time.LocalDate.parse(\"2025-01-31\")");
    }

    /** A claim's cost center: what its link leads to. */
    private static final Map<String, com.menora.initializr.fullstack.EntityDefinition.LinkedEntity> LINKS = Map.of(
            "costCenter", new com.menora.initializr.fullstack.EntityDefinition.LinkedEntity("CostCenter", List.of(
                    field("budget", FieldType.MONEY), field("kind", FieldType.ENUM, "OPEX", "CAPEX"),
                    field("notes", FieldType.TEXT), field("logo", FieldType.IMAGE))));

    private static String linkedMessage(String text) {
        try {
            ExprCheck.check(text, FIELDS, LINKS, Type.BOOL);
            return null;
        } catch (ExprException e) {
            return e.getMessage();
        }
    }

    @Test
    void aLinkedRowsFieldIsReadOneStepAway() {
        Checked c = ExprCheck.check("unitPrice <= costCenter.budget and costCenter.kind == 'capex'", FIELDS, LINKS, Type.BOOL);
        // The row's own fields and the linked ones are kept apart.
        assertThat(c.refs()).containsExactly("unitPrice");
        assertThat(c.paths()).containsOnlyKeys("costCenter.budget", "costCenter.kind");
        assertThat(c.paths().get("costCenter.budget").relation()).isEqualTo("costCenter");
        // Java: the loaded row, null-safe. TS: what the form fetched for the picked id.
        assertThat(ExprCode.java(c, "row"))
                .contains("Expr.num((row.getCostCenter() == null ? null : row.getCostCenter().getBudget()))")
                // An enum literal of the linked entity is spelled as its constant, too.
                .contains("Expr.eq(Expr.name((row.getCostCenter() == null ? null : row.getCostCenter().getKind())), \"CAPEX\")");
        assertThat(ExprCode.ts(c, "value"))
                .contains("rule.num(linked.value('/api/cost-centers', value.costCenterId, 'budget'))")
                .contains("rule.text(linked.value('/api/cost-centers', value.costCenterId, 'kind'))");
        // In the generator an unknown linked value is empty: the rule holds.
        Map<String, Object> values = new HashMap<>(Map.of("unitPrice", new BigDecimal("50")));
        assertThat(ExprEval.holds(c, null, values, LocalDate.of(2026, 3, 10))).isTrue();
        values.put("costCenter.budget", new BigDecimal("40"));
        values.put("costCenter.kind", "CAPEX");
        assertThat(ExprEval.holds(c, null, values, LocalDate.of(2026, 3, 10))).isFalse();

        assertThat(linkedMessage("costcenter.budget > 1")).contains("'costcenter' is not a link of this entity").contains("did you mean 'costCenter'?");
        assertThat(linkedMessage("title.budget > 1")).contains("its links are costCenter");
        assertThat(linkedMessage("costCenter.budgt > 1")).isEqualTo("'budgt' is not a field of CostCenter");
        assertThat(linkedMessage("costCenter.notes == 'x'")).contains("cannot be read through a link");
        assertThat(linkedMessage("isEmpty(costCenter.logo)")).contains("cannot be read through a link");
        assertThat(linkedMessage("costCenter.owner.name == 'x'")).contains("one step only");
        assertThat(linkedMessage("costCenter.kind == 'other'")).contains("is not one of kind's values");
        // Without the links (an entity with none) a path is no field at all.
        assertThat(message("costCenter.budget > 1")).contains("'costCenter' is not a link of this entity");
    }

    @Test
    void whatDoesNotFitIsRefusedWithWhereItIs() {
        assertThatThrownBy(() -> check("ends >= startsOn")).isInstanceOfSatisfying(ExprException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("'ends' is not a field of this entity");
            assertThat(e.describe()).endsWith("(at character 1)");
        });
        assertThat(message("Endson >= startsOn")).contains("did you mean 'endsOn'?");
        assertThat(message("endsOn >= quantity")).isEqualTo("Cannot compare a date with a number");
        assertThat(message("status == 'CLOSED'")).isEqualTo("'CLOSED' is not one of status's values (OPEN, REJECTED)");
        assertThat(message("status < 'OPEN'")).contains("not ordered");
        assertThat(message("receipt == 'x'")).contains("can only be tested with isEmpty()");
        assertThat(message("quantity + 1")).isEqualTo("This must be a yes/no condition, but it is a number");
        assertThat(message("1 < quantity < 5")).contains("do not chain");
        assertThat(message("title + 1 == 'x'")).contains("'+' adds two numbers");
        assertThat(message("frobnicate(title)")).contains("'frobnicate' is not a function");
        assertThat(message("isEmpty()")).isEqualTo("isEmpty takes one argument");
        assertThat(message("round(unitPrice, quantity) > 1")).contains("whole number of decimal places");
        assertThat(message("endsOn > date('2025-02-30')")).contains("is not a date");
        assertThat(message("title == 'open")).contains("not closed");
        assertThat(message("title == ")).contains("ends too early");
        assertThat(message("title == and")).contains("Expected a value");
        assertThat(message("quantity > 1 $")).contains("'$' is not part of the language");
        assertThat(message("")).isEqualTo("The expression is empty");
    }

    private static String message(String text) {
        try {
            check(text);
            return null;
        } catch (ExprException e) {
            return e.getMessage();
        }
    }

    @Test
    void anEmptyValueMakesARuleHold() {
        Checked when = check("status == 'REJECTED'");
        Checked rule = check("not isEmpty(reason)");
        LocalDate today = LocalDate.of(2026, 3, 10);
        Map<String, Object> row = new HashMap<>();
        row.put("status", "REJECTED");
        assertThat(ExprEval.holds(rule, when, row, today)).isFalse();
        row.put("reason", "too expensive");
        assertThat(ExprEval.holds(rule, when, row, today)).isTrue();
        row.put("status", "OPEN");
        row.remove("reason");
        assertThat(ExprEval.holds(rule, when, row, today)).isTrue();

        // A comparison with an empty value is unknown, and unknown holds.
        Checked order = check("endsOn >= startsOn");
        assertThat(ExprEval.holds(order, null, Map.of("endsOn", LocalDate.of(2026, 1, 1)), today)).isTrue();
        assertThat(ExprEval.holds(order, null, Map.of("endsOn", LocalDate.of(2026, 1, 1), "startsOn", LocalDate.of(2026, 2, 1)), today)).isFalse();
        // Three-valued logic: false and unknown is false; true or unknown is true.
        assertThat(eval("quantity > 1 and urgent", Map.of("urgent", false))).isEqualTo(false);
        assertThat(eval("quantity > 1 or urgent", Map.of("urgent", true))).isEqualTo(true);
        assertThat(eval("quantity > 1 or urgent", Map.of("urgent", false))).isNull();
    }

    @Test
    void numbersAreDecimalsAndDatesCountDays() {
        assertThat(eval("quantity * unitPrice", Map.of("quantity", new BigDecimal("3"), "unitPrice", new BigDecimal("2.50"))))
                .isEqualTo(new BigDecimal("7.50"));
        assertThat(eval("unitPrice / 0", Map.of("unitPrice", BigDecimal.ONE))).isNull();
        assertThat(eval("round(unitPrice / 3, 2)", Map.of("unitPrice", BigDecimal.ONE))).isEqualTo(new BigDecimal("0.33"));
        assertThat(eval("daysBetween(startsOn, endsOn)", Map.of("startsOn", LocalDate.of(2026, 1, 1), "endsOn", LocalDate.of(2026, 1, 31))))
                .isEqualTo(BigDecimal.valueOf(30));
        assertThat(eval("startsOn - 1", Map.of("startsOn", LocalDate.of(2026, 1, 1)))).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(eval("today() + 1", Map.of())).isEqualTo(LocalDate.of(2026, 3, 11));
        assertThat(eval("length(title) + abs(-2)", Map.of("title", "abc"))).isEqualTo(new BigDecimal("5"));
        assertThat(eval("unitPrice == 2", Map.of("unitPrice", new BigDecimal("2.00")))).isEqualTo(true);
    }

    // ── The rows saved with a record (a composition) ────────────────────────────────────────

    /** An order's own fields, its link to a customer and the lines saved with it. */
    private static final Map<String, com.menora.initializr.fullstack.EntityDefinition.LinkedEntity> ORDER_LINKS = Map.of(
            "customer", new com.menora.initializr.fullstack.EntityDefinition.LinkedEntity("Customer",
                    List.of(field("name", FieldType.STRING))),
            "lines", new com.menora.initializr.fullstack.EntityDefinition.LinkedEntity("OrderLine",
                    List.of(field("lineTotal", FieldType.MONEY), field("quantity", FieldType.INTEGER), field("note", FieldType.STRING)), true));

    private static Checked rows(String text, Type expected) {
        return ExprCheck.check(text, FIELDS, ORDER_LINKS, expected);
    }

    @Test
    void aRecordReadsItsRowsThroughSumCountMinMaxAndAvg() {
        Checked c = rows("sum(lines.lineTotal) > 10 and count(lines) > 0", Type.BOOL);
        assertThat(c.lists()).containsExactly("lines");
        assertThat(c.refs()).isEmpty();
        assertThat(c.paths()).isEmpty();
        assertThat(ExprCode.java(c, "row")).isEqualTo("Expr.and("
                + "Expr.gt(Expr.sum(row.getLines(), each -> Expr.num(each.getLineTotal())), new java.math.BigDecimal(\"10\")), "
                + "Expr.gt(Expr.count(row.getLines()), new java.math.BigDecimal(\"0\")))");
        assertThat(ExprCode.ts(c, "value")).isEqualTo(
                "rule.and(rule.gt(rule.sum(value.lines, 'lineTotal'), 10), rule.gt(rule.count(value.lines), 0))");
        assertThat(ExprCode.ts(rows("quantity <= max(lines.quantity) - min(lines.quantity) + avg(lines.quantity)", Type.BOOL), "value"))
                .contains("rule.max(value.lines, 'quantity')").contains("rule.min(value.lines, 'quantity')").contains("rule.avg(value.lines, 'quantity')");
    }

    @Test
    void rowsMeanWhatTheRuntimesMean() {
        LocalDate today = LocalDate.of(2026, 3, 10);
        Map<String, Object> empty = new HashMap<>();
        empty.put("lineTotal", null);
        Map<String, Object> order = Map.of("lines", List.of(
                Map.of("lineTotal", new BigDecimal("5")), Map.of("lineTotal", new BigDecimal("7.5")), empty));
        java.util.function.Function<String, Object> on = text -> ExprEval.eval(rows(text, null), order, today);
        // An empty value is left out; the rows are still counted.
        assertThat(on.apply("sum(lines.lineTotal)")).isEqualTo(new BigDecimal("12.5"));
        assertThat(on.apply("count(lines)")).isEqualTo(BigDecimal.valueOf(3));
        assertThat(on.apply("min(lines.lineTotal)")).isEqualTo(new BigDecimal("5"));
        assertThat(on.apply("max(lines.lineTotal)")).isEqualTo(new BigDecimal("7.5"));
        assertThat((BigDecimal) on.apply("avg(lines.lineTotal)")).isEqualByComparingTo("6.25");
        // No rows: nothing adds up to zero, and there is no smallest, largest or average.
        Map<String, Object> none = Map.of("lines", List.of());
        assertThat(ExprEval.eval(rows("sum(lines.lineTotal)", null), none, today)).isEqualTo(BigDecimal.ZERO);
        assertThat(ExprEval.eval(rows("count(lines)", null), none, today)).isEqualTo(BigDecimal.ZERO);
        assertThat(ExprEval.eval(rows("avg(lines.lineTotal)", null), none, today)).isNull();
        assertThat(ExprEval.holds(rows("count(lines) > 0", Type.BOOL), null, none, today)).isFalse();
        // Rows that were not read at all are unknown, so a rule over them holds.
        assertThat(ExprEval.eval(rows("sum(lines.lineTotal)", null), Map.of(), today)).isNull();
        assertThat(ExprEval.holds(rows("count(lines) > 0", Type.BOOL), null, Map.of(), today)).isTrue();
    }

    @Test
    void rowsAreReadThroughTheirFunctionsOnly() {
        assertThatThrownBy(() -> rows("lines.lineTotal > 0", Type.BOOL)).isInstanceOf(ExprException.class)
                .hasMessageContaining("'lines.lineTotal' is one value in each of the rows");
        assertThatThrownBy(() -> rows("lines == 0", Type.BOOL)).hasMessageContaining("'lines' is the rows saved with this entity: count(lines)");
        assertThatThrownBy(() -> rows("count(customer) > 0", Type.BOOL)).hasMessage("count takes the rows saved with this entity — here: lines");
        assertThatThrownBy(() -> rows("count(lines.quantity) > 0", Type.BOOL)).hasMessageContaining("count takes the rows saved with this entity");
        assertThatThrownBy(() -> rows("sum(quantity) > 0", Type.BOOL))
                .hasMessage("sum takes a field of the rows saved with this entity, like sum(lines.total) — here: lines");
        assertThatThrownBy(() -> rows("sum(customer.name) > 0", Type.BOOL)).hasMessageContaining("sum takes a field of the rows");
        assertThatThrownBy(() -> rows("avg(lines.linetotal) > 0", Type.BOOL))
                .hasMessage("'linetotal' is not a field of OrderLine (did you mean 'lineTotal'?)");
        assertThatThrownBy(() -> rows("max(lines.note) > 0", Type.BOOL)).hasMessage("max takes a number field of the rows; 'lines.note' is a text");
        assertThatThrownBy(() -> rows("sum(lines.lineTotal, 2) > 0", Type.BOOL)).hasMessage("sum takes one argument");
        // An entity with no rows of its own says so.
        assertThatThrownBy(() -> check("count(lines) > 0"))
                .hasMessage("count takes the rows saved with this entity — this entity has none (a ONE_TO_MANY with composition)");
    }
}
