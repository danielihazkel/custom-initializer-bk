package com.menora.initializr.codegen;

import com.menora.initializr.extension.frontend.codegen.ErrorsTsRenderer;
import com.menora.initializr.extension.frontend.codegen.HooksTsRenderer;
import com.menora.initializr.extension.frontend.codegen.OpenApiTsGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the React-Query hooks renderer. Drives an already-parsed
 * model through {@link HooksTsRenderer} so the output shape is exercised
 * without depending on the full generator pipeline.
 */
class HooksTsRendererTests {

    private final OpenApiTsGenerator parser = new OpenApiTsGenerator(new ErrorsTsRenderer());
    private final HooksTsRenderer hooks = new HooksTsRenderer();

    @Test
    void getOperationProducesUseQueryHook() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets:
                    get:
                      operationId: listPets
                      responses:
                        '200':
                          content:
                            application/json:
                              schema: { type: array, items: { type: string } }
                """;
        String out = hooks.render(parser.parse(spec));
        assertThat(out).contains("import {");
        assertThat(out).contains("@tanstack/react-query");
        assertThat(out).contains("export function useListPets");
        assertThat(out).contains("useQuery({");
        assertThat(out).contains("queryFn: () => api.requestJson('/pets', 'get'");
    }

    @Test
    void postOperationProducesUseMutationHook() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets:
                    post:
                      operationId: createPet
                      responses:
                        '201': { description: created }
                """;
        String out = hooks.render(parser.parse(spec));
        assertThat(out).contains("export function useCreatePetMutation");
        assertThat(out).contains("useMutation({");
        assertThat(out).contains("mutationFn: (init) => api.requestJson('/pets', 'post'");
    }

    @Test
    void operationIdFallsBackToMethodAndPath() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets/{petId}/orders:
                    get:
                      responses:
                        '200': { description: ok }
                """;
        String out = hooks.render(parser.parse(spec));
        // method=get → "Get", path=/pets/{petId}/orders → "PetsByPetIdOrders"
        assertThat(out).contains("export function useGetPetsByPetIdOrders");
    }

    @Test
    void operationIdWithKebabCaseIsPascalCased() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets:
                    get:
                      operationId: list-all-pets
                      responses:
                        '200': { description: ok }
                """;
        String out = hooks.render(parser.parse(spec));
        assertThat(out).contains("export function useListAllPets");
    }

    @Test
    void emptyPathsProducesEmptyExport() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths: {}
                """;
        String out = hooks.render(parser.parse(spec));
        assertThat(out).contains("(no paths in spec)");
        assertThat(out).contains("export {}");
    }
}
