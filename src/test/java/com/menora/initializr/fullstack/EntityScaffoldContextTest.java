package com.menora.initializr.fullstack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityScaffoldContextTest {

    @Test
    void buildEntityContext_exposesNamingVariants() {
        EntityDefinition e = new EntityDefinition(
                "OrderItem", null,
                List.of(new FieldDefinition("id", FieldType.LONG, true, true, false, false, null, null, null, null, false, List.of())));
        Map<String, Object> project = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", "com.menora.demo", "21", "jar", List.of(e));
        Map<String, Object> ctx = EntityScaffoldContext.buildEntityContext(project, e);

        assertThat(ctx).containsEntry("EntityName", "OrderItem");
        assertThat(ctx).containsEntry("entityName", "orderItem");
        assertThat(ctx).containsEntry("entity_name", "order_item");
        assertThat(ctx).containsEntry("entityNameKebab", "order-item");
        assertThat(ctx).containsEntry("EntityNamePlural", "OrderItems");
        assertThat(ctx).containsEntry("entityNamePluralKebab", "order-items");
        assertThat(ctx).containsEntry("tableName", "order_items");
    }

    @Test
    void buildEntityContext_emitsFieldFlags() {
        EntityDefinition e = new EntityDefinition(
                "User", null,
                List.of(
                        new FieldDefinition("id", FieldType.LONG, true, true, false, false, null, null, null, null, false, List.of()),
                        new FieldDefinition("name", FieldType.STRING, false, false, true, true, 64, null, null, null, false, List.of()),
                        new FieldDefinition("status", FieldType.ENUM, false, false, false, false, null, null, null, null, false,
                                List.of("ACTIVE", "DISABLED"))));
        Map<String, Object> project = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", "com.menora.demo", "21", "jar", List.of(e));
        Map<String, Object> ctx = EntityScaffoldContext.buildEntityContext(project, e);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) ctx.get("fields");
        assertThat(fields).hasSize(3);
        assertThat(fields.get(0)).containsEntry("isPrimaryKey", true);
        assertThat(fields.get(0)).containsEntry("isNumeric", true);
        assertThat(fields.get(1)).containsEntry("isString", true);
        assertThat(fields.get(1)).containsEntry("isRequired", true);
        assertThat(fields.get(1)).containsEntry("hasLength", true);
        assertThat(fields.get(1)).containsEntry("length", 64);
        assertThat(fields.get(2)).containsEntry("isEnum", true);
        assertThat(fields.get(2)).containsEntry("enumTypeName", "UserStatusType");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enumValues = (List<Map<String, Object>>) fields.get(2).get("enumValues");
        assertThat(enumValues).hasSize(2);
        assertThat(enumValues.get(0)).containsEntry("value", "ACTIVE").containsEntry("last", false);
        assertThat(enumValues.get(1)).containsEntry("value", "DISABLED").containsEntry("last", true);

        Object pk = ctx.get("pkField");
        assertThat(pk).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> pkMap = (Map<String, Object>) pk;
        assertThat(pkMap).containsEntry("name", "id");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) project.get("entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("EntityName", "User");
        assertThat(entities.get(0)).containsEntry("first", true);
        assertThat(entities.get(0)).containsEntry("last", true);
    }

    @Test
    void buildProjectContext_exposesPerLayerPackages() {
        EntityDefinition e = new EntityDefinition(
                "User", null,
                List.of(new FieldDefinition("id", FieldType.LONG, true, true, false, false, null, null, null, null, false, List.of())));
        Map<String, Object> ctx = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", "com.menora.demo.catalog", "21", "jar", List.of(e));

        assertThat(ctx).containsEntry("domainPackage", "com.menora.demo.catalog");
        assertThat(ctx).containsEntry("entityPackage", "com.menora.demo.catalog.entity");
        assertThat(ctx).containsEntry("entityPackagePath", "com/menora/demo/catalog/entity");
        assertThat(ctx).containsEntry("repositoryPackage", "com.menora.demo.catalog.repository");
        assertThat(ctx).containsEntry("dtoPackage", "com.menora.demo.catalog.dto");
        assertThat(ctx).containsEntry("servicePackage", "com.menora.demo.catalog.service");
        assertThat(ctx).containsEntry("controllerPackage", "com.menora.demo.catalog.controller");
        assertThat(ctx).containsEntry("controllerPackagePath", "com/menora/demo/catalog/controller");
    }

    @Test
    void buildProjectContext_domainPackageDefaultsToPackageNameWhenBlank() {
        EntityDefinition e = new EntityDefinition(
                "User", null,
                List.of(new FieldDefinition("id", FieldType.LONG, true, true, false, false, null, null, null, null, false, List.of())));
        Map<String, Object> ctx = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", null, "21", "jar", List.of(e));

        assertThat(ctx).containsEntry("domainPackage", "com.menora.demo");
        assertThat(ctx).containsEntry("entityPackage", "com.menora.demo.entity");
        assertThat(ctx).containsEntry("servicePackage", "com.menora.demo.service");
    }

    @Test
    void buildEntityContext_exposesStringFieldsAndHasStringFields() {
        EntityDefinition e = new EntityDefinition(
                "Product", null,
                List.of(
                        new FieldDefinition("id", FieldType.LONG, true, true, false, false, null, null, null, null, false, List.of()),
                        new FieldDefinition("sku", FieldType.STRING, false, false, true, true, 64, null, null, null, false, List.of()),
                        new FieldDefinition("name", FieldType.STRING, false, false, true, false, 255, null, null, null, false, List.of()),
                        new FieldDefinition("price", FieldType.BIG_DECIMAL, false, false, true, false, null, null, null, null, false, List.of())));
        Map<String, Object> project = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", "com.menora.demo", "21", "jar", List.of(e));
        Map<String, Object> ctx = EntityScaffoldContext.buildEntityContext(project, e);

        assertThat(ctx).containsEntry("hasStringFields", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stringFields = (List<Map<String, Object>>) ctx.get("stringFields");
        assertThat(stringFields).hasSize(2);
        assertThat(stringFields.get(0)).containsEntry("name", "sku").containsEntry("lastString", false);
        assertThat(stringFields.get(1)).containsEntry("name", "name").containsEntry("lastString", true);
    }

    @Test
    void buildEntityContext_hasStringFieldsFalseWhenNoStringField() {
        EntityDefinition e = new EntityDefinition(
                "Order", null,
                List.of(
                        new FieldDefinition("id", FieldType.LONG, true, true, false, false, null, null, null, null, false, List.of()),
                        new FieldDefinition("total", FieldType.BIG_DECIMAL, false, false, true, false, null, null, null, null, false, List.of()),
                        new FieldDefinition("paid", FieldType.BOOLEAN, false, false, false, false, null, null, null, null, false, List.of())));
        Map<String, Object> project = EntityScaffoldContext.buildProjectContext(
                "demo", "com.menora", "0.0.1", "com.menora.demo", "com.menora.demo", "21", "jar", List.of(e));
        Map<String, Object> ctx = EntityScaffoldContext.buildEntityContext(project, e);

        assertThat(ctx).containsEntry("hasStringFields", false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stringFields = (List<Map<String, Object>>) ctx.get("stringFields");
        assertThat(stringFields).isEmpty();
    }
}
