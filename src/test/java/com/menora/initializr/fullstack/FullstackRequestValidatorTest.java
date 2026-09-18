package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    void labelAndReadOnly_convertThroughWithDefaults() {
        // labelled + read-only field, a blank-label field, and a plain field (defaults)
        var labelled = new FullstackStarterRequest.FieldDefinitionDto(
                "name", "String", null, null, null, null, null, null, null, null, null, null,
                null, null, "  שם  ", true);
        var blankLabel = new FullstackStarterRequest.FieldDefinitionDto(
                "note", "String", null, null, null, null, null, null, null, null, null, null,
                null, null, "   ", null);
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), labelled, blankLabel)))));
        List<FieldDefinition> fields = result.get(0).fields();
        // pk() — default: no label, editable
        assertThat(fields.get(0).label()).isNull();
        assertThat(fields.get(0).readOnly()).isFalse();
        // labelled — trimmed label preserved, read-only true
        assertThat(fields.get(1).label()).isEqualTo("שם");
        assertThat(fields.get(1).readOnly()).isTrue();
        // blank label normalizes to null; readOnly defaults false when omitted
        assertThat(fields.get(2).label()).isNull();
        assertThat(fields.get(2).readOnly()).isFalse();
    }

    @Test
    void entityLabels_normalizeAndConvertThrough() {
        // explicit singular + plural labels (trimmed) via the full DTO:
        // (name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listView, listViews, label, labelPlural)
        var withLabels = new FullstackStarterRequest.EntityDefinitionDto(
                "User", null, null, List.of(pk()), null,
                null, null, null, null, null, "  משתמש  ", "  משתמשים  ");
        // blank plural normalizes to null; singular preserved
        var blankPlural = new FullstackStarterRequest.EntityDefinitionDto(
                "Order", null, null, List.of(pk()), null,
                null, null, null, null, null, "Order label", "   ");
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(withLabels, blankPlural)));

        assertThat(result.get(0).label()).isEqualTo("משתמש");
        assertThat(result.get(0).labelPlural()).isEqualTo("משתמשים");
        assertThat(result.get(1).label()).isEqualTo("Order label");
        assertThat(result.get(1).labelPlural()).isNull();

        // default (no labels supplied) → both null (templates fall back to the name)
        var plain = FullstackRequestValidator.validateAndConvert(req(List.of(entity("Item", List.of(pk())))));
        assertThat(plain.get(0).label()).isNull();
        assertThat(plain.get(0).labelPlural()).isNull();
    }

    @Test
    void entityOpts_knownKeysConvertThroughAndUnknownKeyIsRejected() {
        // (name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listView,
        //  listViews, label, labelPlural, opts)
        var overridden = new FullstackStarterRequest.EntityDefinitionDto(
                "User", null, null, List.of(pk()), null,
                null, null, null, null, null, null, null,
                java.util.Map.of("audit", false, "csvExport", true));
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(overridden)));
        assertThat(result.get(0).opts()).containsEntry("audit", false).containsEntry("csvExport", true);

        // absent / empty opts → empty map (never null)
        var plain = FullstackRequestValidator.validateAndConvert(req(List.of(entity("Item", List.of(pk())))));
        assertThat(plain.get(0).opts()).isEmpty();

        var bogus = new FullstackStarterRequest.EntityDefinitionDto(
                "Order", null, null, List.of(pk()), null,
                null, null, null, null, null, null, null,
                java.util.Map.of("bogus", true));
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(bogus))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("Unknown scaffold option 'bogus' on entity Order");
    }

    private static FullstackStarterRequest.FieldDefinitionDto withDefault(String name, String type, String dflt) {
        return new FullstackStarterRequest.FieldDefinitionDto(name, type, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, dflt);
    }

    private static FieldDefinition convertField(FullstackStarterRequest.FieldDefinitionDto f) {
        return FullstackRequestValidator.validateAndConvert(req(List.of(entity("Item", List.of(pk(), f)))))
                .get(0).fields().get(1);
    }

    @Test
    void defaultValue_typeCheckedAndCanonicalized() {
        assertThat(convertField(withDefault("status", "String", "draft")).defaultValue()).isEqualTo("draft");
        assertThat(convertField(withDefault("qty", "Integer", " 7 ")).defaultValue()).isEqualTo("7");
        assertThat(convertField(withDefault("big", "Long", "9000000000")).defaultValue()).isEqualTo("9000000000");
        assertThat(convertField(withDefault("price", "BigDecimal", "1.50")).defaultValue()).isEqualTo("1.50");
        assertThat(convertField(withDefault("open", "Boolean", "TRUE")).defaultValue()).isEqualTo("true");
        assertThat(convertField(withDefault("due", "LocalDate", "2024-01-01")).defaultValue()).isEqualTo("2024-01-01");
        assertThat(convertField(withDefault("at", "LocalDateTime", "2024-01-01T10:15:30")).defaultValue())
                .isEqualTo("2024-01-01T10:15:30");
        assertThat(convertField(withDefault("ref", "UUID", "123E4567-E89B-12D3-A456-426614174000")).defaultValue())
                .isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        var enumField = new FullstackStarterRequest.FieldDefinitionDto("stage", "ENUM", null, null, null, null,
                null, null, null, null, null, List.of("active", "closed"), null, null, null, null, "Active");
        assertThat(convertField(enumField).defaultValue()).isEqualTo("ACTIVE");
        // blank → no default; omitted → no default
        assertThat(convertField(withDefault("note", "String", "   ")).defaultValue()).isNull();
        assertThat(convertField(field("plain", "String")).defaultValue()).isNull();
        assertThat(convertField(field("plain", "String")).hasDefault()).isFalse();
    }

    @Test
    void rejects_wrongTypedDefaults() {
        assertThatThrownBy(() -> convertField(withDefault("qty", "Integer", "abc")))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessage("defaultValue 'abc' is not a valid INTEGER (field 'qty' on entity 'Item')");
        assertThatThrownBy(() -> convertField(withDefault("open", "Boolean", "yes")))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("is not a valid BOOLEAN (field 'open'");
        assertThatThrownBy(() -> convertField(withDefault("due", "LocalDate", "01/02/2024")))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("is not a valid LOCAL_DATE (field 'due'");
        assertThatThrownBy(() -> convertField(withDefault("ref", "UUID", "not-a-uuid")))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("is not a valid UUID (field 'ref'");
        assertThatThrownBy(() -> convertField(withDefault("price", "BigDecimal", "1,5")))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("is not a valid BIG_DECIMAL (field 'price'");
        var enumField = new FullstackStarterRequest.FieldDefinitionDto("stage", "ENUM", null, null, null, null,
                null, null, null, null, null, List.of("ACTIVE", "CLOSED"), null, null, null, null, "GONE");
        assertThatThrownBy(() -> convertField(enumField))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("defaultValue 'GONE' is not one of the enumValues [ACTIVE, CLOSED] (field 'stage'");
        var tooLong = new FullstackStarterRequest.FieldDefinitionDto("code", "String", null, null, null, null,
                3, null, null, null, null, null, null, null, null, null, "toolong");
        assertThatThrownBy(() -> convertField(tooLong))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("defaultValue exceeds length 3 (field 'code'");
    }

    @Test
    void rejects_defaultOnGeneratedPrimaryKey() {
        var genPk = new FullstackStarterRequest.FieldDefinitionDto("id", "Long", true, true, null, null, null,
                null, null, null, null, null, null, null, null, null, "1");
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(entity("Item", List.of(genPk))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("defaultValue is not allowed on a generated primary key (field 'id' on entity 'Item')");
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
    void rejects_duplicateFieldNameCaseInsensitive() {
        // 'name' and 'Name' both render getName() in the generated entity, so they must collide
        // server-side exactly as the UI's validation.ts already rejects them.
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), field("name", "String"), field("Name", "String")))))))
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
        var f = new FullstackStarterRequest.FieldDefinitionDto("name", "String", null, null, null, null, null, new BigDecimal("1"), new BigDecimal("10"), null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("min/max only allowed on numeric");
    }

    @Test
    void rejects_minGreaterThanMax() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("age", "Integer", null, null, null, null, null, new BigDecimal("10"), new BigDecimal("1"), null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("min must be <= max");
    }

    @Test
    void rejects_fractionalBoundOnIntegralField() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("qty", "Integer", null, null, null, null, null, new BigDecimal("0.5"), null, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Item", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("must be whole numbers on integral fields");
    }

    @Test
    void rejects_integerBoundOutsideIntRange() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("qty", "Integer", null, null, null, null, null, null, new BigDecimal("3000000000"), null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Item", List.of(pk(), f))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("out of range for INTEGER");
    }

    @Test
    void keepsDecimalBoundsOnBigDecimalField() {
        var f = new FullstackStarterRequest.FieldDefinitionDto("price", "BigDecimal", null, null, null, null, null, new BigDecimal("0.5"), new BigDecimal("99.99"), null, null, null);
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Item", List.of(pk(), f)))));
        FieldDefinition price = result.get(0).fields().get(1);
        assertThat(price.min()).isEqualByComparingTo("0.5");
        assertThat(price.max()).isEqualByComparingTo("99.99");
    }

    @Test
    void enumConstantKeywordCheckIsCaseSensitive() {
        // NEW / DEFAULT are ordinary upper-case constants; a literal `new` would not compile.
        var ok = new FullstackStarterRequest.FieldDefinitionDto("status", "Enum", null, null, null, null, null, null, null, null, null, List.of("NEW", "DEFAULT", "DONE"));
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(entity("Order", List.of(pk(), ok)))));
        assertThat(result.get(0).fields().get(1).enumValues()).containsExactly("NEW", "DEFAULT", "DONE");

        var bad = new FullstackStarterRequest.FieldDefinitionDto("status", "Enum", null, null, null, null, null, null, null, null, null, List.of("new"));
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Order", List.of(pk(), bad))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("reserved keyword");
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
    void rejects_relationFieldCollidingWithFieldCaseInsensitive() {
        var rel = new FullstackStarterRequest.RelationDefinitionDto("MANY_TO_ONE", "Name", "Customer", false);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("Customer", List.of(pk())),
                entityWithRelations("Order", List.of(pk(), field("name", "String")), List.of(rel))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("collides");
    }

    @Test
    void happyPath_carriesConstraintsThrough() {
        var email = new FullstackStarterRequest.FieldDefinitionDto("email", "String", null, null, true, null, 200, null, null, "^.+@.+$", true, null);
        var age = new FullstackStarterRequest.FieldDefinitionDto("age", "Integer", null, null, null, null, null, new BigDecimal("0"), new BigDecimal("120"), null, null, null);
        var result = FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), email, age)))));
        FieldDefinition emailField = result.get(0).fields().get(1);
        assertThat(emailField.email()).isTrue();
        assertThat(emailField.pattern()).isEqualTo("^.+@.+$");
        FieldDefinition ageField = result.get(0).fields().get(2);
        assertThat(ageField.min()).isEqualByComparingTo("0");
        assertThat(ageField.max()).isEqualByComparingTo("120");
    }
}
