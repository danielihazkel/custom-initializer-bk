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
