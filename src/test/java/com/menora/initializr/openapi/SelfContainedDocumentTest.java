package com.menora.initializr.openapi;

import com.menora.initializr.events.AsyncApiReader;
import com.menora.initializr.events.EventsCodeGenerator;
import com.menora.initializr.extension.frontend.codegen.OpenApiCodegenException;
import com.menora.initializr.extension.frontend.codegen.ErrorsTsRenderer;
import com.menora.initializr.extension.frontend.codegen.OpenApiTsGenerator;
import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A pasted document must not make the server fetch anything. Every case here runs against a
 * real HTTP server on the loopback address that records what it is asked for: the assertion that
 * matters is that it is asked for nothing.
 */
class SelfContainedDocumentTest {

    private static final String HEAD_30 = "openapi: 3.0.3\ninfo: {title: t, version: '1'}\n";
    private static final String HEAD_31 = "openapi: 3.1.0\ninfo: {title: t, version: '1'}\n";

    private final List<String> fetched = new ArrayList<>();
    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            synchronized (fetched) {
                fetched.add(exchange.getRequestURI().toString());
            }
            byte[] body = "type: object\nproperties:\n  id:\n    type: string\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private List<String> fetched() {
        synchronized (fetched) {
            return new ArrayList<>(fetched);
        }
    }

    /** Each way a document can point at a URL or a file, and where the refusal says it is. */
    private Map<String, String> externalDocuments() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("components.schemas.A.$ref", HEAD_30
                + "paths: {}\ncomponents:\n  schemas:\n    A:\n      $ref: '" + base + "/a.yaml'\n");
        docs.put("components.schemas.A.properties.b.$ref", HEAD_31
                + "paths: {}\ncomponents:\n  schemas:\n    A:\n      type: object\n      properties:\n        b:\n"
                + "          $ref: '" + base + "/b.yaml'\n");
        docs.put("paths./x.$ref", HEAD_30 + "paths:\n  /x:\n    $ref: '" + base + "/path.yaml'\n");
        docs.put("paths./x.get.parameters[0].$ref", HEAD_30
                + "paths:\n  /x:\n    get:\n      parameters:\n        - $ref: '" + base + "/param.yaml'\n"
                + "      responses:\n        '200': {description: ok}\n");
        docs.put("paths./x.get.responses.200.$ref", HEAD_30
                + "paths:\n  /x:\n    get:\n      responses:\n        '200':\n          $ref: '" + base + "/response.yaml'\n");
        docs.put("paths./x.get.responses.200.content.application/json.examples.one.$ref", HEAD_30
                + "paths:\n  /x:\n    get:\n      responses:\n        '200':\n          description: ok\n          content:\n"
                + "            application/json:\n              examples:\n                one:\n"
                + "                  $ref: '" + base + "/example.yaml'\n");
        docs.put("paths./x.get.callbacks.cb.$ref", HEAD_30
                + "paths:\n  /x:\n    get:\n      callbacks:\n        cb:\n          $ref: '" + base + "/callback.yaml'\n"
                + "      responses:\n        '200': {description: ok}\n");
        docs.put("components.schemas.Pet.discriminator.mapping.dog", HEAD_30
                + "paths: {}\ncomponents:\n  schemas:\n    Dog: {type: object, properties: {kind: {type: string}}}\n"
                + "    Pet:\n      oneOf:\n        - $ref: '#/components/schemas/Dog'\n      discriminator:\n"
                + "        propertyName: kind\n        mapping:\n          dog: '" + base + "/mapping.yaml'\n");
        // A file beside the server's working directory, or anywhere above it.
        docs.put("components.schemas.B.$ref", HEAD_30
                + "paths: {}\ncomponents:\n  schemas:\n    B:\n      $ref: '../../some/file.yaml#/X'\n");
        // The same document as JSON, which the parser reads through another mapper.
        docs.put("components.schemas.J.$ref", "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                + "\"paths\":{},\"components\":{\"schemas\":{\"J\":{\"$ref\":\"" + base + "/j.yaml\"}}}}");
        return docs;
    }

    @Test
    void aReferenceOutOfTheDocumentIsRefusedWithItsPlaceAndNothingIsFetched() {
        for (Map.Entry<String, String> doc : externalDocuments().entrySet()) {
            assertThatThrownBy(() -> SelfContainedDocument.check(doc.getValue()))
                    .as(doc.getKey())
                    .isInstanceOf(SelfContainedDocument.ExternalReferenceException.class)
                    .hasMessageContaining(doc.getKey() + " refers to '")
                    .hasMessageContaining("not part of the document given");
        }
        assertThat(fetched()).isEmpty();
    }

    @Test
    void theOpenApiWizardRefusesSuchADocument() {
        OpenApiCodeGenerator generator = new OpenApiCodeGenerator();
        for (Map.Entry<String, String> doc : externalDocuments().entrySet()) {
            assertThatThrownBy(() -> generator.generate(doc.getValue(), "com.menora.demo", null, false))
                    .as(doc.getKey())
                    .isInstanceOf(OpenApiCodeGenerator.OpenApiParseException.class)
                    .satisfies(e -> assertThat(((OpenApiCodeGenerator.OpenApiParseException) e).messages())
                            .anyMatch(m -> m.startsWith(doc.getKey() + " refers to '")));
            // The drawer's path detection parses the document too.
            assertThatThrownBy(() -> generator.detectPaths(doc.getValue()))
                    .as(doc.getKey())
                    .isInstanceOf(OpenApiCodeGenerator.OpenApiParseException.class);
        }
        assertThat(fetched()).isEmpty();
    }

    @Test
    void theFrontendClientGeneratorRefusesSuchADocument() {
        OpenApiTsGenerator generator = new OpenApiTsGenerator(new ErrorsTsRenderer());
        for (Map.Entry<String, String> doc : externalDocuments().entrySet()) {
            assertThatThrownBy(() -> generator.parse(doc.getValue()))
                    .as(doc.getKey())
                    .isInstanceOf(OpenApiCodegenException.class)
                    .hasMessageContaining("not part of the document given");
        }
        assertThat(fetched()).isEmpty();
    }

    @Test
    void theEventsWizardRefusesAPayloadSchemaThatRefersOutOfTheDocument() {
        // The reader follows a payload's own $ref; one nested inside a schema reaches the parser.
        String document = """
                asyncapi: 3.0.0
                info: { title: Orders, version: 1.0.0 }
                channels:
                  orderCreated:
                    address: orders.created
                    messages:
                      created:
                        name: OrderCreated
                        payload:
                          type: object
                          properties:
                            customer:
                              $ref: '%s/customer.yaml'
                operations:
                  publishOrder: { action: send, channel: { $ref: '#/channels/orderCreated' } }
                """.formatted(base);
        assertThatThrownBy(() -> new EventsCodeGenerator().generate(document, "com.menora.demo", null,
                new EventsCodeGenerator.ProjectFacts(false)))
                .isInstanceOf(AsyncApiReader.AsyncApiParseException.class)
                .hasMessageContaining("properties.customer.$ref refers to '" + base + "/customer.yaml'");
        assertThat(fetched()).isEmpty();
    }

    @Test
    void aSchemasIdIsDroppedSoItsReferencesResolveInsideTheDocument() {
        // OpenAPI 3.1: the parser fetched the $id and looked for #/components/schemas/Dog in the
        // answer — so the request left the server and the reference was reported missing.
        String document = HEAD_31
                + "paths: {}\ncomponents:\n  schemas:\n    Dog: {type: object, properties: {name: {type: string}}}\n"
                + "    A:\n      $id: '" + base + "/schemas/a.yaml'\n      type: object\n      properties:\n"
                + "        d:\n          $ref: '#/components/schemas/Dog'\n";
        SelfContainedDocument.Checked checked = SelfContainedDocument.check(document);
        assertThat(checked.resolve()).isTrue();
        assertThat(checked.text()).doesNotContain("$id").contains("#/components/schemas/Dog");

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(checked.text(), null, checked.parseOptions());
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getComponents().getSchemas()).containsKeys("A", "Dog");
        assertThat(fetched()).isEmpty();

        // A property that happens to be called "$id" is a schema, not an identifier: kept.
        String property = HEAD_31 + "paths: {}\ncomponents:\n  schemas:\n    A:\n      type: object\n"
                + "      properties:\n        $id: {type: string}\n";
        assertThat(SelfContainedDocument.check(property).text()).isSameAs(property);
    }

    @Test
    void aSelfContainedDocumentIsHandedOnAsItWasPasted() throws IOException {
        String petstore = Files.readString(Path.of("src/test/resources/wizard-fixtures/petstore.yaml"));
        SelfContainedDocument.Checked checked = SelfContainedDocument.check(petstore);
        assertThat(checked.text()).isSameAs(petstore);
        assertThat(checked.resolve()).isTrue();
        // A bare name is a component of this document to the parser, not a file.
        String bare = HEAD_30 + "paths: {}\ncomponents:\n  schemas:\n    Dog: {type: object}\n    A:\n"
                + "      type: object\n      properties:\n        d:\n          $ref: 'Dog'\n";
        assertThat(SelfContainedDocument.check(bare).text()).isSameAs(bare);
    }

    @Test
    void aDocumentThatCannotBeReadIsParsedWithoutResolvingAnything() {
        for (String unreadable : List.of("", "   ", "this is not: valid: yaml: at: all: [", "- just\n- a list\n")) {
            SelfContainedDocument.Checked checked = SelfContainedDocument.check(unreadable);
            assertThat(checked.resolve()).as(unreadable).isFalse();
            assertThat(checked.parseOptions().isResolve()).as(unreadable).isFalse();
            assertThat(checked.text()).isSameAs(unreadable);
        }
    }

    @Test
    void whatCountsAsOutsideTheDocument() {
        assertThat(SelfContainedDocument.isExternal("#/components/schemas/Pet")).isFalse();
        assertThat(SelfContainedDocument.isExternal("#anchor")).isFalse();
        assertThat(SelfContainedDocument.isExternal("Pet")).isFalse();
        assertThat(SelfContainedDocument.isExternal("")).isFalse();
        assertThat(SelfContainedDocument.isExternal("http://host/x.yaml")).isTrue();
        assertThat(SelfContainedDocument.isExternal("https://host/x.yaml#/Pet")).isTrue();
        assertThat(SelfContainedDocument.isExternal("file:///etc/passwd")).isTrue();
        assertThat(SelfContainedDocument.isExternal("./pet.yaml")).isTrue();
        assertThat(SelfContainedDocument.isExternal("pet.yaml#/Pet")).isTrue();
        assertThat(SelfContainedDocument.isExternal("../pet")).isTrue();
        assertThat(SelfContainedDocument.isExternal("C:\\specs\\pet")).isTrue();
        assertThat(SelfContainedDocument.isExternal(" /etc/passwd")).isTrue();
    }
}
