package com.menora.initializr.openapi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenApiCodeGenerator} controller emission, focused on the
 * {@code hasValidation} gate: {@code @Valid} / {@code jakarta.validation.Valid} must only
 * be emitted when the validation starter is present, otherwise the generated controller
 * fails to compile (the dependency isn't on the build).
 */
class OpenApiCodeGeneratorTest {

    private final OpenApiCodeGenerator generator = new OpenApiCodeGenerator();

    // Minimal spec with a POST request body — the trigger for @Valid emission.
    private static final String SPEC = """
            openapi: 3.0.0
            info: { title: Pets, version: "1.0" }
            paths:
              /pets:
                post:
                  tags: [pets]
                  operationId: createPet
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema: { $ref: '#/components/schemas/Pet' }
                  responses:
                    '201': { description: created }
            components:
              schemas:
                Pet:
                  type: object
                  properties:
                    id: { type: integer, format: int64 }
                    name: { type: string }
            """;

    private static final OpenApiWizardOptions CONTROLLERS =
            new OpenApiWizardOptions(null, null, null, OpenApiWizardOptions.GenerationMode.CONTROLLERS, null);

    @Test
    void withoutValidationDepNoValidAnnotationOrImport() {
        // Note: @Validated (class-level, from spring-context) is fine and stays — only the
        // validation-starter-dependent @Valid / jakarta.validation.Valid must be absent.
        String controller = controllerSource(generator.generate(SPEC, "com.menora.demo", CONTROLLERS, false));
        assertThat(controller)
                .contains("@RequestBody Pet body")
                .doesNotContain("@Valid @RequestBody")
                .doesNotContain("jakarta.validation.Valid");
    }

    @Test
    void withValidationDepEmitsValidAnnotationAndImport() {
        String controller = controllerSource(generator.generate(SPEC, "com.menora.demo", CONTROLLERS, true));
        assertThat(controller)
                .contains("import jakarta.validation.Valid;")
                .contains("@Valid @RequestBody");
    }

    @Test
    void threeArgGenerateDefaultsToNoValidation() {
        String controller = controllerSource(generator.generate(SPEC, "com.menora.demo", CONTROLLERS));
        assertThat(controller).doesNotContain("@Valid @RequestBody");
    }

    private static String controllerSource(List<GeneratedOpenApiFile> files) {
        return files.stream()
                .filter(f -> f.relativePath().endsWith("Controller.java"))
                .map(GeneratedOpenApiFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no controller generated"));
    }
}
