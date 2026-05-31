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
                null, null, "spring-jpa-crud", "react-tailwind-crud", entities);
    }

    private static FullstackStarterRequest.EntityDefinitionDto entity(String name,
                                                                       List<FullstackStarterRequest.FieldDefinitionDto> fields) {
        return new FullstackStarterRequest.EntityDefinitionDto(name, null, fields);
    }

    private static FullstackStarterRequest.FieldDefinitionDto field(String name, String type) {
        return new FullstackStarterRequest.FieldDefinitionDto(name, type, null, null, null, null, null, null);
    }

    private static FullstackStarterRequest.FieldDefinitionDto pk() {
        return new FullstackStarterRequest.FieldDefinitionDto("id", "Long", true, true, null, null, null, null);
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
    void rejects_multiplePrimaryKeys() {
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(),
                        new FullstackStarterRequest.FieldDefinitionDto("alt", "Long", true, false, null, null, null, null)))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("multiple primary key");
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
        var f = new FullstackStarterRequest.FieldDefinitionDto("count", "Long", null, null, null, null, 10, null);
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
        var genNonPk = new FullstackStarterRequest.FieldDefinitionDto("code", "Long", false, true, null, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(pk(), genNonPk))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("'generated' only applies to the primary key");
    }

    @Test
    void rejects_generatedOnNonIntegralPrimaryKey() {
        var stringGenPk = new FullstackStarterRequest.FieldDefinitionDto("id", "String", true, true, null, null, null, null);
        assertThatThrownBy(() -> FullstackRequestValidator.validateAndConvert(req(List.of(
                entity("User", List.of(stringGenPk))))))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("must be of type LONG or INTEGER");
    }
}
