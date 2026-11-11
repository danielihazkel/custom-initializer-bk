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

    // ── Schemas → types ──────────────────────────────────────────────────────

    private static final String HEADER = """
            openapi: 3.0.3
            info: { title: T, version: "1" }
            """;

    private List<GeneratedOpenApiFile> generate(String body, boolean validation) {
        return generator.generate(HEADER + body, "com.menora.demo", BOTH, validation);
    }

    private static final OpenApiWizardOptions BOTH =
            new OpenApiWizardOptions(null, null, null, OpenApiWizardOptions.GenerationMode.BOTH, null);

    @Test
    void aStringEnumBecomesAnEnumThatKeepsItsWireValues() {
        // An enum was a String: nothing said which values the property takes.
        List<GeneratedOpenApiFile> files = generate("""
                paths:
                  /orders:
                    get:
                      parameters:
                        - { name: state, in: query, schema: { $ref: '#/components/schemas/State' } }
                        - { name: sort, in: query, schema: { type: string, enum: [asc, desc] } }
                      responses:
                        '200': { description: ok, content: { application/json: { schema: { $ref: '#/components/schemas/Order' } } } }
                components:
                  schemas:
                    State:
                      type: string
                      enum: [open, in-progress, onHold, 2nd-check, 'ON HOLD', open]
                    Order:
                      type: object
                      properties:
                        state: { $ref: '#/components/schemas/State' }
                        priority: { type: string, enum: [low, high] }
                        level: { type: integer, enum: [1, 2, 3] }
                """, false);
        assertThat(source(files, "dto/State.java"))
                .contains("public enum State {")
                // A constant is a Java name; the value on the wire stays what the document says.
                .contains("    OPEN(\"open\"),\n    IN_PROGRESS(\"in-progress\"),\n    ON_HOLD(\"onHold\"),\n    _2ND_CHECK(\"2nd-check\"),\n"
                        + "    ON_HOLD2(\"ON HOLD\"),\n    OPEN2(\"open\");")
                .contains("    @JsonValue\n    public String getValue() {")
                .contains("    @JsonCreator\n    public static State fromValue(String value) {");
        // Written inside a property, it is named after where it is. A numeric enum stays its number.
        assertThat(source(files, "dto/Order.java"))
                .contains("        State state,\n        OrderPriority priority,\n        Integer level\n");
        assertThat(source(files, "dto/OrderPriority.java")).contains("    LOW(\"low\"),\n    HIGH(\"high\");");
        // A parameter stays a String: Spring binds an enum by its constant's name, not by "in-progress".
        assertThat(source(files, "api/DefaultController.java"))
                .contains("@RequestParam(name = \"state\", required = false) String state, "
                        + "@RequestParam(name = \"sort\", required = false) String sort");
    }

    @Test
    void allOfIsMergedAndANamedValueIsNotAType() {
        List<GeneratedOpenApiFile> files = generate("""
                paths: {}
                components:
                  schemas:
                    Base:
                      type: object
                      required: [id]
                      properties:
                        id: { type: integer, format: int64 }
                        note: { type: string }
                    Invoice:
                      allOf:
                        - $ref: '#/components/schemas/Base'
                        - type: object
                          required: [total]
                          properties:
                            total: { $ref: '#/components/schemas/Amount' }
                            note: { type: integer }
                            lines:
                              type: array
                              items: { $ref: '#/components/schemas/Line' }
                            tags: { $ref: '#/components/schemas/Tags' }
                            meta: { type: object, additionalProperties: { $ref: '#/components/schemas/Line' } }
                            anything: {}
                            wrapped:
                              description: A reference wrapped to carry this description
                              allOf: [ { $ref: '#/components/schemas/Line' } ]
                    Line:
                      type: object
                      properties:
                        sku: { type: string }
                        shipping:
                          type: object
                          properties:
                            city: { type: string }
                    Amount: { type: number, format: double, minimum: 0 }
                    Tags: { type: array, items: { type: string } }
                    Loose: { type: object }
                    List: { type: object, properties: { size: { type: integer } } }
                """, true);
        // allOf used to give an empty record with a TODO. The parts are merged; a later part overrides.
        assertThat(source(files, "dto/Invoice.java"))
                .contains("        @NotNull Long id,\n"
                        + "        Integer note,\n"
                        // Amount and Tags are names for a number and a list: the reference is that type,
                        // and the name's constraints come with it.
                        + "        @NotNull @DecimalMin(value = \"0\") Double total,\n"
                        + "        @Valid List<Line> lines,\n"
                        + "        List<String> tags,\n"
                        + "        @Valid Map<String, Line> meta,\n"
                        + "        Object anything,\n"
                        + "        @Valid Line wrapped\n");
        // An object written in place gets a record named after its place.
        assertThat(source(files, "dto/Line.java")).contains("        String sku,\n        @Valid LineShipping shipping\n");
        assertThat(source(files, "dto/LineShipping.java")).contains("        String city\n");
        // A schema named like a type the generated code uses is renamed.
        assertThat(source(files, "dto/ListDto.java")).contains("public record ListDto(");
        assertThat(files).extracting(GeneratedOpenApiFile::relativePath)
                .noneMatch(p -> p.endsWith("/Amount.java") || p.endsWith("/Tags.java") || p.endsWith("/Loose.java")
                        || p.endsWith("/List.java"));
    }

    @Test
    void oneOfBecomesASealedInterfaceOverItsRecords() {
        List<GeneratedOpenApiFile> files = generate("""
                paths: {}
                components:
                  schemas:
                    Payment:
                      oneOf:
                        - $ref: '#/components/schemas/Card'
                        - $ref: '#/components/schemas/Transfer'
                      discriminator:
                        propertyName: method
                        mapping: { wire: '#/components/schemas/Transfer' }
                    Party:
                      anyOf:
                        - $ref: '#/components/schemas/Card'
                        - $ref: '#/components/schemas/Person'
                    Tagged:
                      oneOf:
                        - $ref: '#/components/schemas/Person'
                        - $ref: '#/components/schemas/Transfer'
                      discriminator: { propertyName: type }
                    Card: { type: object, properties: { method: { type: string }, pan: { type: string } } }
                    Transfer: { type: object, properties: { method: { type: string }, iban: { type: string } } }
                    Person: { type: object, properties: { name: { type: string } } }
                    Scalar:
                      oneOf: [ { type: string }, { type: integer } ]
                    Maybe:
                      oneOf: [ { $ref: '#/components/schemas/Person' }, { type: 'null' } ]
                    Holder:
                      type: object
                      properties:
                        payment: { $ref: '#/components/schemas/Payment' }
                        scalar: { $ref: '#/components/schemas/Scalar' }
                        maybe: { $ref: '#/components/schemas/Maybe' }
                """, false);
        // The records declare the discriminator themselves: Jackson reads it where it is.
        assertThat(source(files, "dto/Payment.java"))
                .contains("@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = \"method\", visible = true)")
                // A value the mapping names, else the schema's own name.
                .contains("        @JsonSubTypes.Type(value = Card.class, name = \"Card\"),\n"
                        + "        @JsonSubTypes.Type(value = Transfer.class, name = \"wire\")\n")
                .contains("public sealed interface Payment permits Card, Transfer {");
        // No discriminator: the properties present say which.
        assertThat(source(files, "dto/Party.java"))
                .contains("@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)")
                .contains("        @JsonSubTypes.Type(Card.class),\n        @JsonSubTypes.Type(Person.class)\n");
        // A discriminator the records do not declare is Jackson's to add.
        assertThat(source(files, "dto/Tagged.java")).contains("@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = \"type\")");
        // A record says what it is one of — and, read on its own, is just its properties.
        assertThat(source(files, "dto/Card.java"))
                .contains("@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)\npublic record Card(")
                .contains(") implements Payment, Party {");
        assertThat(source(files, "dto/Holder.java"))
                // A choice between values that are not objects has no type to be; "this or null" is this.
                .contains("        Payment payment,\n        Object scalar,\n        Person maybe\n")
                .doesNotContain("JsonTypeInfo");
        assertThat(files).extracting(GeneratedOpenApiFile::relativePath)
                .noneMatch(p -> p.endsWith("/Scalar.java") || p.endsWith("/Maybe.java"));
    }

    private static final String CONSTRAINED = """
            paths:
              /items/{item-id}:
                put:
                  parameters:
                    - { name: item-id, in: path, required: true, schema: { type: integer, format: int64, minimum: 1 } }
                    - { name: since, in: query, schema: { type: string, format: date } }
                    - { name: q, in: query, required: true, schema: { type: string, minLength: 2, maxLength: 30 } }
                  requestBody:
                    content: { application/json: { schema: { $ref: '#/components/schemas/Item' } } }
                  responses:
                    '200': { description: ok }
            components:
              schemas:
                Item:
                  type: object
                  required: [name, qty, code]
                  properties:
                    name: { type: string, minLength: 1, maxLength: 40 }
                    code: { type: string, pattern: '^[A-Z]{2}\\d+$', nullable: true }
                    mail: { type: string, format: email }
                    qty: { type: integer, minimum: 0, maximum: 99 }
                    big: { type: integer, format: int64, maximum: 5000000000 }
                    ratio: { type: number, minimum: 0, exclusiveMinimum: true, maximum: 1.5 }
                    parts: { type: array, minItems: 1, maxItems: 3, items: { type: string } }
            """;

    @Test
    void withTheValidationStarterTheSchemasConstraintsAreWritten() {
        List<GeneratedOpenApiFile> files = generate(CONSTRAINED, true);
        assertThat(source(files, "dto/Item.java"))
                .contains("        @NotNull @Size(min = 1, max = 40) String name,\n")
                // Required but nullable: null is allowed. The pattern is a Java string.
                .contains("        @Pattern(regexp = \"^[A-Z]{2}\\\\d+$\") String code,\n")
                .contains("        @Email String mail,\n")
                .contains("        @NotNull @Min(0) @Max(99) Integer qty,\n")
                .contains("        @Max(5000000000L) Long big,\n")
                .contains("        @DecimalMin(value = \"0\", inclusive = false) @DecimalMax(value = \"1.5\") Double ratio,\n")
                .contains("        @Size(min = 1, max = 3) List<String> parts\n")
                .contains("import jakarta.validation.constraints.NotNull;").contains("import jakarta.validation.constraints.Pattern;");
        assertThat(source(files, "api/DefaultController.java"))
                .contains("@Min(1) @PathVariable(\"item-id\") Long itemId")
                // Spring would read the date in the server locale's short style.
                .contains("@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @RequestParam(name = \"since\", required = false) LocalDate since")
                .contains("@Size(min = 2, max = 30) @RequestParam(name = \"q\", required = true) String q")
                .contains("@Valid @RequestBody Item body");
    }

    @Test
    void withoutTheValidationStarterNothingNamesIt() {
        List<GeneratedOpenApiFile> files = generate(CONSTRAINED, false);
        assertThat(files).allSatisfy(f -> assertThat(f.content()).as(f.relativePath())
                .doesNotContain("jakarta.validation").doesNotContain("@NotNull").doesNotContain("@Size").doesNotContain("@Valid "));
        assertThat(source(files, "dto/Item.java")).contains("        String name,\n        String code,\n");
        // The date format is Spring's own: it stays.
        assertThat(source(files, "api/DefaultController.java")).contains("@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)");
    }

    // ── Operations ───────────────────────────────────────────────────────────

    private static final String OPERATIONS = """
            paths:
              /shops/{shop_id}/items/{item-id}:
                parameters:
                  - { name: item-id, in: path, required: true, schema: { type: string } }
                  - { name: shop_id, in: path, required: true, schema: { type: integer } }
                get:
                  operationId: find
                  parameters:
                    - { name: X-Trace-Id, in: header, required: true, schema: { type: string } }
                    - { name: sid, in: cookie, schema: { type: string } }
                    - { name: page_size, in: query, schema: { type: integer } }
                    - { name: body, in: query, schema: { type: string } }
                    - { name: class, in: query, schema: { type: string } }
                  responses:
                    '200': { description: ok, content: { application/json: { schema: { $ref: '#/components/schemas/Thing' } } } }
                head:
                  operationId: exists
                  responses: { '200': { description: ok } }
                options:
                  responses: { '204': { description: ok } }
                delete:
                  operationId: remove
                  responses:
                    '204': { description: gone }
                    default: { description: error, content: { application/json: { schema: { $ref: '#/components/schemas/Thing' } } } }
                post:
                  operationId: new
                  requestBody:
                    required: false
                    content:
                      application/json:
                        schema: { type: object, properties: { first_name: { type: string }, class: { type: string }, 2fa: { type: boolean } } }
                  responses:
                    '201':
                      description: created
                      content:
                        application/json:
                          schema: { type: object, additionalProperties: { $ref: '#/components/schemas/Thing' } }
            components:
              schemas:
                Thing: { type: object, properties: { at: { type: string, format: date-time } } }
            """;

    @Test
    void aControllerUsesTheRequestsNamesAndTheDeclaredStatus() {
        String controller = source(generate(OPERATIONS, false), "api/DefaultController.java");
        assertThat(controller)
                // The names the request carries — "X-Trace-Id" was bound as "xTraceId", "page_size" as
                // "pageSize" — and a cookie as a cookie (it was read as a query parameter).
                .contains("@PathVariable(\"item-id\") String itemId, @PathVariable(\"shop_id\") Integer shopId, "
                        + "@RequestHeader(name = \"X-Trace-Id\", required = true) String xTraceId, "
                        + "@CookieValue(name = \"sid\", required = false) String sid, "
                        + "@RequestParam(name = \"page_size\", required = false) Integer pageSize, "
                        // A name the method uses itself, and a Java keyword.
                        + "@RequestParam(name = \"body\", required = false) String bodyParam, "
                        + "@RequestParam(name = \"class\", required = false) String class_)")
                // HEAD and OPTIONS were listed by the preview and left out of the code.
                .contains("    @RequestMapping(value = \"/shops/{shop_id}/items/{item-id}\", method = RequestMethod.HEAD)\n    public void exists(")
                .contains("method = RequestMethod.OPTIONS)\n    @ResponseStatus(HttpStatus.NO_CONTENT)\n    public void optionsShopsShopIdItemsItemId(")
                // 204 is the success; "default" (the error) used to give the method its return type.
                .contains("    @DeleteMapping(\"/shops/{shop_id}/items/{item-id}\")\n    @ResponseStatus(HttpStatus.NO_CONTENT)\n    public void remove(")
                // An operation id that is a keyword; a body the document says is optional; a map response.
                .contains("    @ResponseStatus(HttpStatus.CREATED)\n    public Map<String, Thing> new_(")
                .contains("@RequestBody(required = false) NewRequest body)")
                .contains("import org.springframework.web.bind.annotation.CookieValue;")
                .contains("import com.menora.demo.dto.Thing;").contains("import java.util.Map;");
        List<GeneratedOpenApiFile> files = generate(OPERATIONS, false);
        // A body written in place is a record named after the operation; its properties keep their wire names.
        assertThat(source(files, "dto/NewRequest.java"))
                .contains("        @JsonProperty(\"first_name\") String firstName,\n"
                        + "        @JsonProperty(\"class\") String class_,\n"
                        + "        @JsonProperty(\"2fa\") Boolean _2fa\n");
        // RFC 3339: a date-time says its offset.
        assertThat(source(files, "dto/Thing.java")).contains("        OffsetDateTime at\n").contains("import java.time.OffsetDateTime;");
    }

    @Test
    void theClientsSendTheRequestsNamesInThePathsOrder() {
        String template = source(generate(OPERATIONS, false), "client/DefaultClient.java");
        assertThat(template)
                .contains("UriComponentsBuilder.fromUriString(baseUrl)")
                .contains("        if (pageSize != null) builder.queryParam(\"page_size\", pageSize);\n")
                // Expanded by position: the path names shop_id first, the document lists item-id first.
                .contains("        String uri = builder.buildAndExpand(shopId, itemId).toUriString();\n")
                .contains("        headers.add(\"X-Trace-Id\", String.valueOf(xTraceId));\n")
                .contains("        if (sid != null) headers.add(HttpHeaders.COOKIE, \"sid=\" + sid);\n")
                .contains("restTemplate.exchange(uri, HttpMethod.HEAD, HttpEntity.EMPTY, Void.class);")
                .contains("new ParameterizedTypeReference<Map<String, Thing>>() {}");

        OpenApiWizardOptions restClient = new OpenApiWizardOptions(null, null, null,
                OpenApiWizardOptions.GenerationMode.CLIENT, "shop.url", OpenApiWizardOptions.ClientKind.REST_CLIENT, false);
        List<GeneratedOpenApiFile> files = generator.generate(HEADER + OPERATIONS, "com.menora.demo", restClient, false);
        assertThat(source(files, "client/DefaultClient.java"))
                .contains("    public DefaultClient(RestClient openApiRestClient) {")
                .contains("        return restClient.method(HttpMethod.GET)\n"
                        + "                .uri(uriBuilder -> uriBuilder.path(\"/shops/{shop_id}/items/{item-id}\")\n"
                        + "                        .queryParamIfPresent(\"page_size\", Optional.ofNullable(pageSize))\n")
                .contains("                        .build(shopId, itemId))\n                .headers(httpHeaders -> {\n"
                        + "                    httpHeaders.add(\"X-Trace-Id\", String.valueOf(xTraceId));\n"
                        + "                    if (sid != null) httpHeaders.add(HttpHeaders.COOKIE, \"sid=\" + sid);\n                })\n"
                        + "                .retrieve()\n                .body(Thing.class);")
                .contains("        restClient.method(HttpMethod.DELETE)\n").contains("                .toBodilessEntity();")
                .contains("                .body(body)\n                .retrieve()\n"
                        + "                .body(new ParameterizedTypeReference<Map<String, Thing>>() {});")
                .doesNotContain("RestTemplate");
        assertThat(source(files, "client/OpenApiClientConfig.java"))
                .contains("public RestClient openApiRestClient(RestClient.Builder builder, @Value(\"${shop.url}\") String baseUrl) {")
                .contains("return builder.baseUrl(baseUrl).build();");
        // Controllers only: no client of either kind.
        assertThat(generator.generate(HEADER + OPERATIONS, "com.menora.demo", CONTROLLERS, false))
                .extracting(GeneratedOpenApiFile::relativePath).noneMatch(p -> p.contains("/client/"));
    }

    @Test
    void theClientKindIsParsedAndAnUnknownOneRefused() {
        assertThat(OpenApiWizardOptions.ClientKind.parse(null)).isEqualTo(OpenApiWizardOptions.ClientKind.REST_TEMPLATE);
        assertThat(OpenApiWizardOptions.ClientKind.parse("restClient")).isEqualTo(OpenApiWizardOptions.ClientKind.REST_CLIENT);
        assertThat(OpenApiWizardOptions.ClientKind.parse(" REST_TEMPLATE ")).isEqualTo(OpenApiWizardOptions.ClientKind.REST_TEMPLATE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenApiWizardOptions.ClientKind.parse("feign"))
                .isInstanceOf(com.menora.initializr.config.WizardArgumentException.class)
                .hasMessageContaining("Unknown OpenAPI wizard clientKind: 'feign'");
    }

    @Test
    void anOpenApi31DocumentIsReadByItsTypesArray() {
        // 3.1 writes "type: [string, 'null']" and the parser keeps it apart from "type".
        List<GeneratedOpenApiFile> files = generator.generate("""
                openapi: 3.1.0
                info: { title: T, version: "1" }
                paths: {}
                components:
                  schemas:
                    Note:
                      type: object
                      required: [text, count]
                      properties:
                        text: { type: [string, 'null'], maxLength: 10 }
                        count: { type: integer }
                        when: { type: string, format: date }
                        flags: { type: array, items: { type: boolean } }
                """, "com.menora.demo", CONTROLLERS, true);
        assertThat(source(files, "dto/Note.java"))
                .contains("        @Size(max = 10) String text,\n        @NotNull Integer count,\n        LocalDate when,\n        List<Boolean> flags\n");
    }

    private static String source(List<GeneratedOpenApiFile> files, String suffix) {
        return files.stream()
                .filter(f -> f.relativePath().endsWith(suffix))
                .map(GeneratedOpenApiFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No file ending with " + suffix + " in: "
                        + files.stream().map(GeneratedOpenApiFile::relativePath).toList()));
    }

    private static String controllerSource(List<GeneratedOpenApiFile> files) {
        return files.stream()
                .filter(f -> f.relativePath().endsWith("Controller.java"))
                .map(GeneratedOpenApiFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no controller generated"));
    }
}
