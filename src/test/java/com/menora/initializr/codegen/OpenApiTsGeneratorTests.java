package com.menora.initializr.codegen;

import com.menora.initializr.extension.frontend.codegen.ErrorsTsRenderer;
import com.menora.initializr.extension.frontend.codegen.OpenApiCodegenException;
import com.menora.initializr.extension.frontend.codegen.OpenApiTsGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure-Java OpenAPI → TypeScript generator. The integration
 * smoke (real spec → zip → tsc) lives in {@code FrontendProjectGenerationIntegrationTests}.
 */
class OpenApiTsGeneratorTests {

    private final OpenApiTsGenerator gen = new OpenApiTsGenerator(new ErrorsTsRenderer());

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
    void petstoreSpecProducesAllCoreFiles() {
        Map<String, String> files = gen.generate(PETSTORE);
        assertThat(files).containsKeys("schema.ts", "paths.ts", "errors.ts", "client.ts", "README.md");
    }

    @Test
    void errorsTsExposesSuccessBodyAndApiErrorTypes() {
        String errors = gen.generate(PETSTORE).get("errors.ts");
        assertThat(errors).contains("export type SuccessBody");
        assertThat(errors).contains("export type ErrorBody");
        assertThat(errors).contains("export interface ApiError");
        assertThat(errors).contains("export function isApiError");
    }

    @Test
    void clientTsExposesTypedRequestJson() {
        String client = gen.generate(PETSTORE).get("client.ts");
        assertThat(client).contains("import { type ApiError, type SuccessBody } from './errors'");
        assertThat(client).contains("requestJson");
        assertThat(client).contains("return { request, requestJson }");
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
        // GET /pets 200 returns Pets array via Schema namespace, under the success bucket
        assertThat(paths).contains("success: {");
        assertThat(paths).contains("'200': Schema.Pets");
    }

    @Test
    void responsesAreSplitIntoSuccessAndError() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets:
                    get:
                      responses:
                        '200':
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Pet' }
                        '404':
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Problem' }
                        default:
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Problem' }
                components:
                  schemas:
                    Pet: { type: object, properties: { id: { type: integer } } }
                    Problem: { type: object, properties: { message: { type: string } } }
                """;
        String paths = gen.generate(spec).get("paths.ts");
        // success bucket has 2xx
        assertThat(paths).contains("success: {");
        assertThat(paths).contains("'200': Schema.Pet");
        // error bucket has non-2xx + default
        assertThat(paths).contains("error: {");
        assertThat(paths).contains("'404': Schema.Problem");
        assertThat(paths).contains("'default': Schema.Problem");
    }

    @Test
    void deprecatedOperationGetsJsDoc() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets:
                    get:
                      summary: List pets
                      deprecated: true
                      responses:
                        '200': { description: ok }
                """;
        String paths = gen.generate(spec).get("paths.ts");
        assertThat(paths).contains("/**");
        assertThat(paths).contains("* List pets");
        assertThat(paths).contains("* @deprecated");
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
    void discriminatedOneOfBecomesTaggedUnion() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths: {}
                components:
                  schemas:
                    Cat:
                      type: object
                      required: [kind, meows]
                      properties:
                        kind: { type: string }
                        meows: { type: boolean }
                    Dog:
                      type: object
                      required: [kind, barks]
                      properties:
                        kind: { type: string }
                        barks: { type: boolean }
                    Pet:
                      oneOf:
                        - $ref: '#/components/schemas/Cat'
                        - $ref: '#/components/schemas/Dog'
                      discriminator:
                        propertyName: kind
                        mapping:
                          cat: '#/components/schemas/Cat'
                          dog: '#/components/schemas/Dog'
                """;
        String schema = gen.generate(spec).get("schema.ts");
        assertThat(schema).contains("export type Pet = ({ kind: 'cat' } & Cat) | ({ kind: 'dog' } & Dog)");
    }

    @Test
    void discriminatedOneOfWithoutMappingUsesRefName() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths: {}
                components:
                  schemas:
                    Cat:
                      type: object
                      properties: { kind: { type: string } }
                    Dog:
                      type: object
                      properties: { kind: { type: string } }
                    Pet:
                      oneOf:
                        - $ref: '#/components/schemas/Cat'
                        - $ref: '#/components/schemas/Dog'
                      discriminator:
                        propertyName: kind
                """;
        String schema = gen.generate(spec).get("schema.ts");
        assertThat(schema).contains("({ kind: 'Cat' } & Cat)");
        assertThat(schema).contains("({ kind: 'Dog' } & Dog)");
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
