package com.menora.initializr.codegen;

import com.menora.initializr.extension.frontend.codegen.ErrorsTsRenderer;
import com.menora.initializr.extension.frontend.codegen.MswHandlersRenderer;
import com.menora.initializr.extension.frontend.codegen.OpenApiTsGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MswHandlersRendererTests {

    private final OpenApiTsGenerator parser = new OpenApiTsGenerator(new ErrorsTsRenderer());
    private final MswHandlersRenderer msw = new MswHandlersRenderer();

    @Test
    void handlerUsesExampleWhenPresent() {
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
                              example:
                                id: 42
                                name: Fido
                              schema:
                                $ref: '#/components/schemas/Pet'
                components:
                  schemas:
                    Pet: { type: object, properties: { id: { type: integer }, name: { type: string } } }
                """;
        String out = msw.render(parser.parse(spec));
        assertThat(out).contains("import { http, HttpResponse } from 'msw'");
        assertThat(out).contains("http.get('/pets'");
        assertThat(out).contains("\"id\":42");
        assertThat(out).contains("\"name\":\"Fido\"");
        assertThat(out).contains("status: 200");
    }

    @Test
    void handlerSynthesisesBodyFromSchemaWhenNoExample() {
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
                              schema:
                                type: object
                                properties:
                                  id: { type: integer }
                                  name: { type: string }
                                  active: { type: boolean }
                """;
        String out = msw.render(parser.parse(spec));
        assertThat(out).contains("\"id\":0");
        assertThat(out).contains("\"name\":\"string\"");
        assertThat(out).contains("\"active\":false");
    }

    @Test
    void arrayResponseProducesArrayBody() {
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
                              schema:
                                type: array
                                items:
                                  type: object
                                  properties:
                                    name: { type: string }
                """;
        String out = msw.render(parser.parse(spec));
        assertThat(out).contains("[{\"name\":\"string\"}]");
    }

    @Test
    void postOperationKeepsCreatedStatus() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets:
                    post:
                      responses:
                        '201':
                          content:
                            application/json:
                              schema: { type: object, properties: { id: { type: integer } } }
                """;
        String out = msw.render(parser.parse(spec));
        assertThat(out).contains("http.post('/pets'");
        assertThat(out).contains("status: 201");
    }

    @Test
    void pathTemplatesAreConvertedToMswSyntax() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /pets/{petId}:
                    get:
                      responses:
                        '200': { description: ok }
                """;
        String out = msw.render(parser.parse(spec));
        assertThat(out).contains("http.get('/pets/:petId'");
    }

    @Test
    void responseWithoutContentProducesNullBody() {
        String spec = """
                openapi: 3.0.0
                info: { title: t, version: 1 }
                paths:
                  /ping:
                    get:
                      responses:
                        '204': { description: no content }
                """;
        String out = msw.render(parser.parse(spec));
        assertThat(out).contains("http.get('/ping', () => HttpResponse.json(null, { status: 204 }))");
    }
}
