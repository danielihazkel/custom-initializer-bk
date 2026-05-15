package com.menora.initializr.codegen;

import com.menora.initializr.extension.frontend.codegen.OpenApiCodegenException;
import com.menora.initializr.extension.frontend.codegen.OpenApiTsGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure-Java OpenAPI → TypeScript generator. The integration
 * smoke (real spec → zip → tsc) lives in {@code PairedStarterIntegrationTests}.
 */
class OpenApiTsGeneratorTests {

    private final OpenApiTsGenerator gen = new OpenApiTsGenerator();

    @Test
    void emptySpecThrows() {
        assertThatThrownBy(() -> gen.generate(""))
                .isInstanceOf(OpenApiCodegenException.class);
        assertThatThrownBy(() -> gen.generate(null))
                .isInstanceOf(OpenApiCodegenException.class);
    }

    @Test
    void unparseableSpecThrows() {
        assertThatThrownBy(() -> gen.generate("not openapi: nope nope nope: ..."))
                .isInstanceOf(OpenApiCodegenException.class);
    }

    @Test
    void petstoreSpecProducesAllFourFiles() {
        Map<String, String> files = gen.generate(PETSTORE);
        assertThat(files).containsKeys("schema.ts", "paths.ts", "client.ts", "README.md");
    }

    @Test
    void schemaTsExportsTopLevelTypes() {
        String schema = gen.generate(PETSTORE).get("schema.ts");
        assertThat(schema).contains("export type Pet");
        assertThat(schema).contains("export type Pets");
        // Required field has no ?, optional does
        assertThat(schema).contains("name: string");
        assertThat(schema).contains("id?: number");
        // Array references the named type
        assertThat(schema).contains("export type Pets = Pet[]");
    }

    @Test
    void pathsTsTypesParametersAndResponses() {
        String paths = gen.generate(PETSTORE).get("paths.ts");
        assertThat(paths).contains("import type * as Schema from './schema'");
        assertThat(paths).contains("'/pets': {");
        assertThat(paths).contains("get: {");
        assertThat(paths).contains("post: {");
        // GET /pets has an optional limit query param
        assertThat(paths).contains("query?:");
        assertThat(paths).contains("'limit'?: number");
        // POST /pets requestBody refs the Pet schema
        assertThat(paths).contains("requestBody: Schema.Pet");
        // GET /pets 200 returns Pets array via Schema namespace
        assertThat(paths).contains("'200': Schema.Pets");
    }

    @Test
    void clientTsExposesTypedRequestFunction() {
        String client = gen.generate(PETSTORE).get("client.ts");
        assertThat(client).contains("import type { paths } from './paths'");
        assertThat(client).contains("export function createApiClient");
        assertThat(client).contains("request<P extends PathKey, M extends MethodOf<P>>");
    }

    @Test
    void enumsBecomeUnionsOfLiterals() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths: {}
                components:
                  schemas:
                    Status:
                      type: string
                      enum: [pending, active, archived]
                """;
        String schema = gen.generate(spec).get("schema.ts");
        assertThat(schema).contains("export type Status = 'pending' | 'active' | 'archived'");
    }

    @Test
    void nullableFieldsBecomeUnionWithNull() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths: {}
                components:
                  schemas:
                    Box:
                      type: object
                      required: [id]
                      properties:
                        id:
                          type: string
                        note:
                          type: string
                          nullable: true
                """;
        String schema = gen.generate(spec).get("schema.ts");
        assertThat(schema).contains("note?: string | null");
    }

    @Test
    void allOfBecomesIntersection() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths: {}
                components:
                  schemas:
                    Base:
                      type: object
                      properties: { id: { type: string } }
                    Extra:
                      type: object
                      properties: { extra: { type: number } }
                    Combined:
                      allOf:
                        - $ref: '#/components/schemas/Base'
                        - $ref: '#/components/schemas/Extra'
                """;
        String schema = gen.generate(spec).get("schema.ts");
        assertThat(schema).contains("export type Combined = ");
        assertThat(schema).contains("Base");
        assertThat(schema).contains("Extra");
        assertThat(schema).contains("&");
    }

    @Test
    void specWithoutComponentsStillProducesValidOutput() {
        String spec = """
                openapi: 3.0.0
                info: { title: empty, version: 1 }
                paths:
                  /health:
                    get:
                      responses:
                        '200': { description: ok }
                """;
        Map<String, String> files = gen.generate(spec);
        assertThat(files.get("schema.ts")).contains("export {}");
        assertThat(files.get("paths.ts")).contains("'/health': {");
    }

    private static final String PETSTORE = """
            openapi: 3.0.0
            info:
              title: Pet Store
              version: 1.0.0
            paths:
              /pets:
                get:
                  parameters:
                    - name: limit
                      in: query
                      schema:
                        type: integer
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pets'
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Pet'
                  responses:
                    '201':
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  required:
                    - name
                  properties:
                    id:
                      type: integer
                      format: int64
                    name:
                      type: string
                    tag:
                      type: string
                Pets:
                  type: array
                  items:
                    $ref: '#/components/schemas/Pet'
            """;
}
