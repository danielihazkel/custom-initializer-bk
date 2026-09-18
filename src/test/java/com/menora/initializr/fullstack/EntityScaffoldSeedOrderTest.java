package com.menora.initializr.fullstack;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the demo-data seeding order and per-field seed expressions (optScaffoldSeedData). */
class EntityScaffoldSeedOrderTest {

    private static final FieldDefinition ID = new FieldDefinition("id", FieldType.LONG, true, true, false, false,
            null, null, null, null, false, List.of(), true, true);

    private static EntityDefinition entity(String name, List<RelationDefinition> relations, boolean readOnly, String viewQuery) {
        return new EntityDefinition(name, null, null, List.of(ID), relations, readOnly, viewQuery, null,
                List.of("table"), null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedEntities_parentsFirst_readOnlySkipped_selfReferenceUnseeded() {
        // Order is declared first but depends on Customer; Report is a read-only view; Node points at itself.
        EntityDefinition order = entity("Order",
                List.of(new RelationDefinition(RelationType.MANY_TO_ONE, "customer", "Customer", true)), false, null);
        EntityDefinition customer = entity("Customer", List.of(), false, null);
        EntityDefinition report = entity("Report", List.of(), true, "select 1 as id");
        EntityDefinition node = entity("Node",
                List.of(new RelationDefinition(RelationType.MANY_TO_ONE, "parent", "Node", false)), false, null);

        Map<String, Object> ctx = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", "com.menora.demo", "21", "jar",
                List.of(order, customer, report, node));

        assertThat(ctx).containsEntry("hasSeedEntities", true);
        List<Map<String, Object>> seeds = (List<Map<String, Object>>) ctx.get("seedEntities");
        assertThat(seeds).extracting(m -> m.get("EntityName")).containsExactly("Customer", "Order", "Node");
        assertThat(seeds.get(0)).containsEntry("seedFirst", true).containsEntry("seedLast", false);
        assertThat(seeds.get(2)).containsEntry("seedFirst", false).containsEntry("seedLast", true);

        List<Map<String, Object>> orderRels = (List<Map<String, Object>>) seeds.get(1).get("relations");
        assertThat(orderRels.get(0)).containsEntry("targetSeeded", true).containsEntry("targetEntityCamel", "customer");
        List<Map<String, Object>> nodeRels = (List<Map<String, Object>>) seeds.get(2).get("relations");
        assertThat(nodeRels.get(0)).containsEntry("targetSeeded", false);

        // The plain `entities` list keeps declaration order and is not mutated by the seed copies.
        List<Map<String, Object>> entities = (List<Map<String, Object>>) ctx.get("entities");
        assertThat(entities).extracting(m -> m.get("EntityName")).containsExactly("Order", "Customer", "Report", "Node");
        assertThat(((List<Map<String, Object>>) entities.get(0).get("relations")).get(0)).doesNotContainKey("targetSeeded");
    }

    @Test
    void seedEntities_cycleFallsBackToDeclarationOrder() {
        EntityDefinition a = entity("Alpha",
                List.of(new RelationDefinition(RelationType.MANY_TO_ONE, "beta", "Beta", false)), false, null);
        EntityDefinition b = entity("Beta",
                List.of(new RelationDefinition(RelationType.MANY_TO_ONE, "alpha", "Alpha", false)), false, null);
        Map<String, Object> ctx = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", "com.menora.demo", "21", "jar", List.of(a, b));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seeds = (List<Map<String, Object>>) ctx.get("seedEntities");
        assertThat(seeds).extracting(m -> m.get("EntityName")).containsExactly("Alpha", "Beta");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alphaRels = (List<Map<String, Object>>) seeds.get(0).get("relations");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> betaRels = (List<Map<String, Object>>) seeds.get(1).get("relations");
        assertThat(alphaRels.get(0)).containsEntry("targetSeeded", false);   // back edge → left null
        assertThat(betaRels.get(0)).containsEntry("targetSeeded", true);
    }

    @Test
    void seedExpression_perFieldType() {
        FieldDefinition code = new FieldDefinition("code", FieldType.STRING, true, false, true, true,
                5, null, null, null, false, List.of(), true, true);
        FieldDefinition email = new FieldDefinition("email", FieldType.STRING, false, false, false, false,
                null, null, null, null, true, List.of(), true, true);
        FieldDefinition qty = new FieldDefinition("qty", FieldType.INTEGER, false, false, false, false,
                null, new BigDecimal("1"), new BigDecimal("9"), null, false, List.of(), true, true);
        FieldDefinition status = new FieldDefinition("status", FieldType.ENUM, false, false, false, false,
                null, null, null, null, false, List.of("OPEN"), true, true);

        assertThat(EntityScaffoldContext.seedExpression("Coupon", code, "Code", null))
                .isEqualTo("label(\"Code\", i, 5)");
        assertThat(EntityScaffoldContext.seedExpression("Customer", email, "Email", null))
                .isEqualTo("\"user\" + i + \"@example.com\"");
        assertThat(EntityScaffoldContext.seedExpression("Order", qty, "Qty", null))
                .isEqualTo("(int) bounded(i, 1L, 9L)");
        assertThat(EntityScaffoldContext.seedExpression("Order", status, "Status", "OrderStatusType"))
                .isEqualTo("Order.OrderStatusType.values()[(i - 1) % Order.OrderStatusType.values().length]");
    }
}
