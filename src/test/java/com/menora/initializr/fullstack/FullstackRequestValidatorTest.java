package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FullstackRequestValidatorTest {

    private static FullstackStarterRequest req(List<FullstackStarterRequest.EntityDefinitionDto> entities) {
        return new FullstackStarterRequest(
                null, "demo", null, null, "com.menora.demo", null,
                null, null, null, null, null, null, null,
                null, null, "spring-jpa-crud", "react-tailwind-crud", null, entities);
    }

    private static FullstackStarterRequest.EntityDefinitionDto entity(String name,
                                                                       List<FullstackStarterRequest.FieldDefinitionDto> fields) {
        return new FullstackStarterRequest.EntityDefinitionDto(name, null, null, fields, null);
    }

    private static FullstackStarterRequest.FieldDefinitionDto field(String name, String type) {
        return new FullstackStarterRequest.FieldDefinitionDto(name, type, null, null, null, null, null, null, null, null, null, null);
    }

    private static FullstackStarterRequest.FieldDefinitionDto pk() {
        return new FullstackStarterRequest.FieldDefinitionDto("id", "Long", true, true, null, null, null, null, null, null, null, null);
    }

    @Test
    void happyPath_convertsCleanly() {
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), field("name", "String"))))));
        assertThat(result).hasSize(1);
        EntityDefinition u = result.get(0);
        assertThat(u.name()).isEqualTo("User");
        assertThat(u.fields()).hasSize(2);
        assertThat(u.fields().get(0).primaryKey()).isTrue();
    }

    @Test
    void rejects_missingEntities() {
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of())))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("At least one entity");
    }

    @Test
    void rejects_duplicateEntityNamesCaseInsensitive() {
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk())),
                entity("user", List.of(pk()))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("Duplicate entity name");
    }

    @Test
    void rejects_reservedKeywordEntityName() {
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Class", List.of(pk()))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("reserved keyword");
    }

    @Test
    void rejects_missingPrimaryKey() {
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(field("name", "String")))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("no primary key");
    }

    @Test
    void accepts_compositePrimaryKey() {
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("OrderLine", List.of(
                        new FullstackStarterRequest.FieldDefinitionDto("orderId", "Long", true, false, null, null, null, null, null, null, null, null),
                        new FullstackStarterRequest.FieldDefinitionDto("lineNo", "Integer", true, false, null, null, null, null, null, null, null, null),
                        field("qty", "Integer"))))));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).fields().stream().filter(FieldDefinition::primaryKey)).hasSize(2);
    }

    @Test
    void rejects_generatedCompositePrimaryKey() {
        // pk() is a generated id; adding a second PK makes it a composite key, which cannot be generated.
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(),
                        new FullstackStarterRequest.FieldDefinitionDto("alt", "Long", true, false, null, null, null, null, null, null, null, null)))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("generated primary key combined with a composite key");
    }

    @Test
    void rejects_manyToOneTargetingCompositePkEntity() {
        var parent = entity("OrderLine", List.of(
                new FullstackStarterRequest.FieldDefinitionDto("orderId", "Long", true, false, null, null, null, null, null, null, null, null),
                new FullstackStarterRequest.FieldDefinitionDto("lineNo", "Integer", true, false, null, null, null, null, null, null, null, null)));
        var child = new FullstackStarterRequest.EntityDefinitionDto("Shipment", null, null, List.of(pk()),
                List.of(new FullstackStarterRequest.RelationDefinitionDto("MANY_TO_ONE", "line", "OrderLine", false)));
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(parent, child))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("composite-PK entity");
    }

    @Test
    void rejects_enumWithoutValues() {
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), field("status", "ENUM")))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("ENUM");
    }

    @Test
    void rejects_lengthOnNonString() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("count", "Long", null, null, null, null, 10, null, null, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("length only allowed on STRING");
    }

    @Test
    void rejects_duplicateFieldName() {
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), field("name", "String"), field("name", "String")))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("Duplicate field");
    }

    @Test
    void rejects_generatedOnNonPrimaryKey() {
        var genNonPk = new FullstackStarterRequest.FieldDefinitionDto("code", "Long", false, true, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), genNonPk))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("'generated' only applies to the primary key");
    }

    @Test
    void rejects_generatedOnNonIntegralPrimaryKey() {
        var stringGenPk = new FullstackStarterRequest.FieldDefinitionDto("id", "String", true, true, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(stringGenPk))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("must be of type LONG, INTEGER, or UUID");
    }

    @Test
    void rejects_minMaxOnNonNumeric() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("name", "String", null, null, null, null, null, 1L, 10L, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("min/max only allowed on numeric");
    }

    @Test
    void rejects_minGreaterThanMax() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("age", "Integer", null, null, null, null, null, 10L, 1L, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("min must be <= max");
    }

    @Test
    void rejects_patternOnNonString() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("age", "Integer", null, null, null, null, null, null, null, "\\d+", null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("pattern only allowed on STRING");
    }

    @Test
    void rejects_emailOnNonString() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("age", "Integer", null, null, null, null, null, null, null, null, true, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("email only allowed on STRING");
    }

    @Test
    void rejects_invalidRegexPattern() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("name", "String", null, null, null, null, null, null, null, "[unclosed", null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("not a valid regex");
    }

    private static FullstackStarterRequest.EntityDefinitionDto entityWithRelations(
            String name,
            List<FullstackStarterRequest.FieldDefinitionDto> fields,
            List<FullstackStarterRequest.RelationDefinitionDto> relations) {
        return new FullstackStarterRequest.EntityDefinitionDto(name, null, null, fields, relations);
    }

    @Test
    void resolvesRelationTargetCanonically() {
        var rel = new FullstackStarterRequest.RelationDefinitionDto("ManyToOne", "customer", "customer", true);
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Customer", List.of(pk())),
                entityWithRelations("Order", List.of(pk()), List.of(rel)))));
        EntityDefinition order = result.get(1);
        assertThat(order.relations()).hasSize(1);
        RelationDefinition r = order.relations().get(0);
        assertThat(r.type()).isEqualTo(RelationType.MANY_TO_ONE);
        assertThat(r.fieldName()).isEqualTo("customer");
        // Target lowercase "customer" resolves to the declared spelling "Customer".
        assertThat(r.targetEntity()).isEqualTo("Customer");
        assertThat(r.required()).isTrue();
    }

    @Test
    void rejects_relationToUnknownEntity() {
        var rel = new FullstackStarterRequest.RelationDefinitionDto("MANY_TO_ONE", "ghost", "Ghost", false);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entityWithRelations("Order", List.of(pk()), List.of(rel))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("unknown entity");
    }

    @Test
    void rejects_unsupportedRelationType() {
        var rel = new FullstackStarterRequest.RelationDefinitionDto("MANY_TO_MANY", "tags", "Tag", false);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Tag", List.of(pk())),
                entityWithRelations("Order", List.of(pk()), List.of(rel))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("not supported yet");
    }

    @Test
    void rejects_relationFieldCollidingWithField() {
        var rel = new FullstackStarterRequest.RelationDefinitionDto("MANY_TO_ONE", "name", "Customer", false);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Customer", List.of(pk())),
                entityWithRelations("Order", List.of(pk(), field("name", "String")), List.of(rel))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("collides");
    }

    @Test
    void happyPath_carriesConstraintsThrough() {
        var email = new FullstackStarterRequest.FieldDefinitionDto("email", "String", null, null, true, null, 200, null, null, "^.+@.+$", true, null);
        var age = new FullstackStarterRequest.FieldDefinitionDto("age", "Integer", null, null, null, null, null, 0L, 120L, null, null, null);
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), email, age)))));
        FieldDefinition emailField = result.get(0).fields().get(1);
        assertThat(emailField.email()).isTrue();
        assertThat(emailField.pattern()).isEqualTo("^.+@.+$");
        FieldDefinition ageField = result.get(0).fields().get(2);
        assertThat(ageField.min()).isEqualTo(0L);
        assertThat(ageField.max()).isEqualTo(120L);
    }
}
