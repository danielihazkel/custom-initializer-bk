package com.menora.initializr.fullstack;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The values the generator's own rows take so that an entity's rules accept them. */
class RuleRowsTest {

    private static FieldDefinition field(String name, FieldType type, boolean required, String... enumValues) {
        return new FieldDefinition(name, type, false, false, required, false, null, null, null, null, false,
                List.of(enumValues), true, true);
    }

    private static final FieldDefinition ID = new FieldDefinition("id", FieldType.LONG, true, true, false, false,
            null, null, null, null, false, List.of(), true, true);

    private static EntityDefinition claim(List<EntityDefinition.Rule> rules) {
        List<FieldDefinition> fields = List.of(ID,
                field("amount", FieldType.MONEY, true),
                field("approvedAmount", FieldType.MONEY, false),
                field("status", FieldType.ENUM, true, "OPEN", "APPROVED"));
        EntityDefinition.Workflow wf = new EntityDefinition.Workflow("status", "OPEN", null, null, List.of(
                new EntityDefinition.Transition("approve", null, List.of("OPEN"), "APPROVED", List.of(), List.of())));
        return new EntityDefinition("Claim", null, null, fields, List.of(), false, null, null, List.of(), null, null,
                null, List.of(), wf, List.of(), List.of(), null, null, rules);
    }

    private static List<FieldDefinition> sent(EntityDefinition e) {
        return e.fields().stream().filter(f -> !f.primaryKey() && !f.name().equals("status")).toList();
    }

    /** An order whose lines are saved with it: each line has a quantity (at least one), a price and their product. */
    private static EntityDefinition order(List<EntityDefinition.Rule> rules, List<EntityDefinition.Rule> lineRules, String totalFormula) {
        FieldDefinition quantity = new FieldDefinition("quantity", FieldType.INTEGER, false, false, true, false,
                null, BigDecimal.ONE, null, null, false, List.of(), true, true);
        FieldDefinition lineTotal = computed("lineTotal", "quantity * unitPrice");
        List<FieldDefinition> lineFields = List.of(ID, quantity, field("unitPrice", FieldType.MONEY, false), lineTotal);
        java.util.Map<String, EntityDefinition.LinkedEntity> links = java.util.Map.of(
                "lines", new EntityDefinition.LinkedEntity("OrderLine", lineFields, true, lineRules));
        List<FieldDefinition> fields = new java.util.ArrayList<>(List.of(ID, field("reference", FieldType.STRING, true)));
        if (totalFormula != null) fields.add(computed("total", totalFormula));
        return new EntityDefinition("Order", null, null, fields, List.of(), false, null, null, List.of(), null, null,
                null, List.of(), null, List.of(), List.of(), null, null, rules, links);
    }

    private static FieldDefinition computed(String name, String formula) {
        return new FieldDefinition(name, FieldType.BIG_DECIMAL, false, false, false, false, null, null, null, null, false,
                List.of(), true, true, null, false, null, null, null, null, null, null, formula, false, null);
    }

    @Test
    void aRecordWhoseRulesReadItsRowsIsMadeWithOneThatPassesThem() throws Exception {
        // The order needs a line, and its lines must add up to something: one line, with a price.
        EntityDefinition e = order(List.of(
                        new EntityDefinition.Rule(null, "count(lines) > 0", "An order needs a line", "lines"),
                        new EntityDefinition.Rule(null, "total >= 10", "Too small an order", "total")),
                List.of(), "sum(lines.lineTotal)");
        RuleRows.Solution s = RuleRows.solve(e, sent(e));
        assertThat(s.lists()).containsExactly("lines");
        BigDecimal quantity = (BigDecimal) s.picks().get("lines.quantity").value();
        BigDecimal price = (BigDecimal) s.picks().get("lines.unitPrice").value();
        assertThat(quantity.multiply(price)).isGreaterThanOrEqualTo(BigDecimal.TEN);
        assertThat(s.picks().get("lines.quantity").java()).isEqualTo(quantity.toPlainString());
        // Nothing of the order itself had to be chosen.
        assertThat(s.picks().keySet()).allMatch(name -> name.startsWith("lines."));
    }

    @Test
    void theRowsOwnRulesHoldForTheRowTheRecordIsMadeWith() throws Exception {
        // The lines' own rule caps a line at 5; the order wants 4 or more in all: one line of 4 or 5.
        EntityDefinition e = order(List.of(new EntityDefinition.Rule(null, "sum(lines.quantity) >= 4", "Too few", "lines")),
                List.of(new EntityDefinition.Rule(null, "quantity <= 5", "At most five to a line", "quantity")), null);
        RuleRows.Solution s = RuleRows.solve(e, sent(e));
        assertThat((BigDecimal) s.picks().get("lines.quantity").value()).isBetween(BigDecimal.valueOf(4), BigDecimal.valueOf(5));

        // One row cannot be two: the rule is named, and the tests are left to the team.
        EntityDefinition two = order(List.of(new EntityDefinition.Rule(null, "count(lines) >= 2", "Two lines", "lines")), List.of(), null);
        assertThatThrownBy(() -> RuleRows.solve(two, sent(two))).isInstanceOf(RuleRows.Unsolvable.class)
                .satisfies(u -> assertThat(((RuleRows.Unsolvable) u).rule).isZero());
        // A record whose rules read no rows is made without one, as before.
        EntityDefinition plain = order(List.of(new EntityDefinition.Rule(null, "length(reference) > 0", "Named", "reference")), List.of(), null);
        assertThat(RuleRows.solve(plain, sent(plain)).lists()).isEmpty();
    }

    @Test
    void aRuleMustHoldInTheStateTheTestMovesTheRowTo() throws Exception {
        // Empty would do while the claim is open, but the generated test approves it.
        EntityDefinition e = claim(List.of(new EntityDefinition.Rule("status == 'APPROVED'", "not isEmpty(approvedAmount)",
                "Say how much", "approvedAmount")));
        assertThat(RuleRows.states(e)).containsExactly(null, "OPEN", "APPROVED");
        RuleRows.Solution s = RuleRows.solve(e, sent(e));
        assertThat(s.picks().get("approvedAmount").value()).isEqualTo(BigDecimal.ONE);
        assertThat(s.picks().get("approvedAmount").java()).isEqualTo("new java.math.BigDecimal(\"1\")");
    }

    @Test
    void valuesAreFoundTogether_andEmptyIsOneOfThem() throws Exception {
        EntityDefinition e = claim(List.of(new EntityDefinition.Rule(null, "approvedAmount <= amount - 5", "No more than asked", null)));
        RuleRows.Solution s = RuleRows.solve(e, sent(e));
        // amount 1 leaves nothing an approved amount could be but empty — or a larger amount.
        BigDecimal amount = (BigDecimal) s.picks().get("amount").value();
        Object approved = s.picks().get("approvedAmount").value();
        assertThat(approved == null || ((BigDecimal) approved).compareTo(amount.subtract(BigDecimal.valueOf(5))) <= 0).isTrue();
    }

    @Test
    void noCombinationNamesTheRuleThatStoppedIt() {
        EntityDefinition e = claim(List.of(
                new EntityDefinition.Rule(null, "amount > 0", "Positive", null),
                new EntityDefinition.Rule(null, "amount > 1000000", "Huge", null)));
        assertThatThrownBy(() -> RuleRows.solve(e, sent(e)))
                .isInstanceOfSatisfying(RuleRows.Unsolvable.class, u -> assertThat(u.rule).isEqualTo(1));
    }
}
