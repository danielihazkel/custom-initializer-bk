package com.menora.initializr.openapi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI wizard's interface style ({@code apiStyle: interfaces}), its form bodies (multipart
 * and form-urlencoded) and its security schemes, on {@code wizard-fixtures/secure-forms.yaml}.
 */
class OpenApiInterfacesTest {

    private final OpenApiCodeGenerator generator = new OpenApiCodeGenerator();

    private static String fixture(String name) throws Exception {
        return new String(OpenApiInterfacesTest.class.getResourceAsStream("/wizard-fixtures/" + name).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static OpenApiWizardOptions options(OpenApiWizardOptions.GenerationMode mode, OpenApiWizardOptions.ClientKind kind,
                                                OpenApiWizardOptions.ApiStyle style) {
        return new OpenApiWizardOptions(null, null, null, mode, null, kind, false, style);
    }

    private Map<String, String> generate(String fixture, OpenApiWizardOptions options, boolean validation, boolean springdoc)
            throws Exception {
        List<GeneratedOpenApiFile> files = generator.generate(fixture(fixture), "com.menora.demo", options, validation, springdoc);
        Map<String, String> out = files.stream().collect(Collectors.toMap(
                f -> f.relativePath().replace("src/main/java/{{packagePath}}/", ""), GeneratedOpenApiFile::content,
                (a, b) -> a + "\n---\n" + b, java.util.LinkedHashMap::new));
        String dir = System.getProperty("oa.dump");
        if (dir != null) {
            for (var e : out.entrySet()) {
                Path target = Path.of(dir, fixture + "-" + options.modeOrDefault() + "-" + options.interfaces()
                        + "-" + springdoc, e.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue());
            }
        }
        return out;
    }

    @Test
    void anInterfaceCarriesTheMappingsAndTheClassIsTheTeams() throws Exception {
        Map<String, String> files = generate("petstore.yaml", options(OpenApiWizardOptions.GenerationMode.CONTROLLERS, null,
                OpenApiWizardOptions.ApiStyle.INTERFACES), true, false);
        assertThat(files).containsKeys("api/PetsApi.java", "api/PetsController.java");
        String api = files.get("api/PetsApi.java");
        assertThat(api).contains("public interface PetsApi {")
                .contains("import org.springframework.web.server.ResponseStatusException;")
                .contains("    @GetMapping(\"/pets/{petId}\")")
                .contains("throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, \"getPetById is not implemented\");")
                .doesNotContain("@RestController").doesNotContain("{{");
        assertThat(api).containsPattern("default \\w+ getPetById\\(@PathVariable\\(\"petId\"\\)");
        // The class says nothing the document says but the tag: an update never touches it.
        String impl = files.get("api/PetsController.java");
        assertThat(impl).contains("@RestController\n@Validated\npublic class PetsController implements PetsApi {\n}")
                .doesNotContain("getPetById");
        // Classes stay what a request without the style gets.
        Map<String, String> classes = generate("petstore.yaml", options(OpenApiWizardOptions.GenerationMode.CONTROLLERS, null,
                null), true, false);
        assertThat(classes).doesNotContainKey("api/PetsApi.java");
        assertThat(classes.get("api/PetsController.java")).contains("@RestController").contains("TODO: implement getPetById");
    }

    @Test
    void aMultipartBodyIsItsPartsAndAFormItsFields() throws Exception {
        Map<String, String> files = generate("secure-forms.yaml", options(OpenApiWizardOptions.GenerationMode.CONTROLLERS, null,
                OpenApiWizardOptions.ApiStyle.INTERFACES), true, false);
        String api = files.get("api/ClaimsApi.java");
        assertThat(api)
                .contains("@PostMapping(value = \"/claims\", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)")
                .contains("@Size(max = 20) @RequestParam(name = \"policyNumber\", required = true) String policyNumber")
                .contains("@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @RequestParam(name = \"incidentDate\", required = false) LocalDate incidentDate")
                .contains("@RequestPart(name = \"receipt\", required = true) MultipartFile receipt")
                .contains("@RequestPart(name = \"photos\", required = false) List<MultipartFile> photos")
                .contains("@Valid @RequestPart(name = \"details\", required = false) ClaimDetails details")
                .contains("@PostMapping(value = \"/claims/{claimId}/notes\", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)")
                .contains("@Size(min = 1) @RequestParam(name = \"text\", required = true) String text")
                .contains("import org.springframework.web.multipart.MultipartFile;")
                .doesNotContain("@RequestBody");
        // No record is made of a form body: its properties are the parameters.
        assertThat(files.keySet()).noneMatch(p -> p.contains("FileClaimRequest") || p.contains("AddNoteRequest"));
    }

    @Test
    void withoutSpringdocTheSecurityIsSaidInTheJavadoc() throws Exception {
        Map<String, String> files = generate("secure-forms.yaml", options(OpenApiWizardOptions.GenerationMode.CONTROLLERS, null,
                null), false, false);
        String claims = files.get("api/ClaimsController.java");
        assertThat(claims)
                .contains("    /**\n     * Every claim, newest first\n     *\n"
                        + "     * <p>Security: bearerAuth (HTTP bearer token).\n"
                        + "     * Not checked here: the gateway, or the application's own security, enforces it.\n     */")
                .contains("Security: apiKey (API key in header X-API-Key) or basicAuth (HTTP basic).")
                .doesNotContain("@SecurityRequirement");
        assertThat(files.get("api/PartnersController.java"))
                .contains("Security: partnerKey (API key in query partner_key) and oauth (OAuth2 access token).");
        // "security: []" — open to anyone.
        assertThat(files.get("api/HealthController.java")).doesNotContain("Security:");
        assertThat(files).doesNotContainKey("api/OpenApiSecuritySchemes.java");
    }

    @Test
    void withSpringdocTheSchemesAreDeclaredAndRequired() throws Exception {
        Map<String, String> files = generate("secure-forms.yaml", options(OpenApiWizardOptions.GenerationMode.CONTROLLERS, null,
                OpenApiWizardOptions.ApiStyle.INTERFACES), true, true);
        assertThat(files.get("api/ClaimsApi.java"))
                .contains("    @SecurityRequirement(name = \"bearerAuth\")\n    @GetMapping(\"/claims\")")
                .contains("    @SecurityRequirement(name = \"apiKey\")\n    @SecurityRequirement(name = \"basicAuth\")\n")
                .contains("import io.swagger.v3.oas.annotations.security.SecurityRequirement;")
                .doesNotContain("Not checked here");
        assertThat(files.get("api/HealthApi.java")).doesNotContain("SecurityRequirement");
        assertThat(files.get("api/OpenApiSecuritySchemes.java"))
                .contains("@SecurityScheme(name = \"bearerAuth\", type = SecuritySchemeType.HTTP, scheme = \"bearer\", bearerFormat = \"JWT\")")
                .contains("@SecurityScheme(name = \"apiKey\", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = \"X-API-Key\")")
                .contains("@SecurityScheme(name = \"partnerKey\", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.QUERY, paramName = \"partner_key\")")
                .contains("@SecurityScheme(name = \"oauth\", type = SecuritySchemeType.OAUTH2, description = \"The partners' identity provider\")")
                .contains("public class OpenApiSecuritySchemes {");
    }

    @Test
    void theRestTemplateClientSendsItsFormsAndItsCredentials() throws Exception {
        Map<String, String> files = generate("secure-forms.yaml", options(OpenApiWizardOptions.GenerationMode.CLIENT,
                OpenApiWizardOptions.ClientKind.REST_TEMPLATE, null), false, false);
        String claims = files.get("client/ClaimsClient.java");
        assertThat(claims)
                .contains("public ClaimsClient(RestTemplate restTemplate, @Value(\"${openapi.client.base-url}\") String baseUrl,\n"
                        + "            OpenApiCredentials credentials) {")
                .contains("public Claim fileClaim(String policyNumber, LocalDate incidentDate, Resource receipt, List<Resource> photos, ClaimDetails details) {")
                .contains("        headers.setContentType(MediaType.MULTIPART_FORM_DATA);\n"
                        + "        if (OpenApiCredentials.present(credentials.bearerAuth())) headers.setBearerAuth(credentials.bearerAuth());\n")
                .contains("        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();\n"
                        + "        if (policyNumber != null) form.add(\"policyNumber\", String.valueOf(policyNumber));\n"
                        + "        if (incidentDate != null) form.add(\"incidentDate\", String.valueOf(incidentDate));\n"
                        + "        if (receipt != null) form.add(\"receipt\", receipt);\n")
                .contains("        if (details != null) form.add(\"details\", details);\n")
                .contains("        if (photos != null) photos.forEach(v -> form.add(\"photos\", v));\n")
                .contains("restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(form, headers), Claim.class);")
                // The first requirement's scheme: the API key, not basic.
                .contains("if (OpenApiCredentials.present(credentials.apiKey())) headers.add(\"X-API-Key\", credentials.apiKey());")
                .contains("        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();\n"
                        + "        if (text != null) form.add(\"text\", String.valueOf(text));\n"
                        + "        if (visibleTo != null) visibleTo.forEach(v -> form.add(\"visibleTo\", String.valueOf(v)));\n")
                .doesNotContain("basicAuth").doesNotContain("{{");
        assertThat(files.get("client/PartnersClient.java"))
                .contains("if (OpenApiCredentials.present(credentials.partnerKey())) builder.queryParam(\"partner_key\", credentials.partnerKey());")
                .contains("if (OpenApiCredentials.present(credentials.oauth())) headers.setBearerAuth(credentials.oauth());");
        // Open to anyone: no credentials asked for.
        assertThat(files.get("client/HealthClient.java")).doesNotContain("OpenApiCredentials");

        String credentials = files.get("client/OpenApiCredentials.java");
        assertThat(credentials)
                .contains("            @Value(\"${openapi.client.security.bearer-auth.token:}\") String bearerAuth,\n")
                .contains("            @Value(\"${openapi.client.security.oauth.token:}\") String oauth) {\n")
                .contains("    public String apiKey() {")
                .doesNotContain("basicAuth").doesNotContain("{{");
        assertThat(files.get("src/main/resources/application.yaml"))
                .isEqualTo("openapi:\n  client:\n    base-url: http://localhost:8080\n    security:\n"
                        + "      bearer-auth:\n        token: \"\"\n      api-key:\n        api-key: \"\"\n"
                        + "      partner-key:\n        api-key: \"\"\n      oauth:\n        token: \"\"\n");
    }

    @Test
    void theRestClientSendsThemInItsOneExpression() throws Exception {
        Map<String, String> files = generate("secure-forms.yaml", options(OpenApiWizardOptions.GenerationMode.CLIENT,
                OpenApiWizardOptions.ClientKind.REST_CLIENT, null), false, false);
        assertThat(files.get("client/ClaimsClient.java"))
                .contains("public ClaimsClient(RestClient openApiRestClient, OpenApiCredentials credentials) {")
                .contains("        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();\n")
                .contains("                    if (OpenApiCredentials.present(credentials.bearerAuth())) httpHeaders.setBearerAuth(credentials.bearerAuth());\n")
                .contains("                .contentType(MediaType.MULTIPART_FORM_DATA)\n                .body(form)\n");
        assertThat(files.get("client/PartnersClient.java"))
                .contains("                        .queryParamIfPresent(\"partner_key\", OpenApiCredentials.optional(credentials.partnerKey()))\n");
    }

    @Test
    void aDocumentWithoutSecurityOrFormsIsWrittenAsBefore() throws Exception {
        Map<String, String> files = generate("petstore.yaml", options(OpenApiWizardOptions.GenerationMode.BOTH,
                OpenApiWizardOptions.ClientKind.REST_CLIENT, null), false, true);
        assertThat(files).doesNotContainKeys("client/OpenApiCredentials.java", "api/OpenApiSecuritySchemes.java");
        assertThat(files.get("src/main/resources/application.yaml")).isEqualTo("openapi:\n  client:\n    base-url: http://localhost:8080\n");
    }

    @Test
    void aStyleIsOneOfTwo() {
        assertThat(OpenApiWizardOptions.ApiStyle.parse(null)).isNull();
        assertThat(OpenApiWizardOptions.ApiStyle.parse(" Interfaces ")).isEqualTo(OpenApiWizardOptions.ApiStyle.INTERFACES);
        assertThat(OpenApiWizardOptions.ApiStyle.parse("CLASSES")).isEqualTo(OpenApiWizardOptions.ApiStyle.CLASSES);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenApiWizardOptions.ApiStyle.parse("delegates"))
                .isInstanceOf(com.menora.initializr.config.WizardArgumentException.class)
                .hasMessageContaining("Valid values: classes, interfaces");
    }

    @Test
    void namesAreKebabbedForProperties() {
        assertThat(OpenApiCodeGenerator.kebab("petstore_auth")).isEqualTo("petstore-auth");
        assertThat(OpenApiCodeGenerator.kebab("bearerAuth")).isEqualTo("bearer-auth");
        assertThat(OpenApiCodeGenerator.kebab("__")).isEqualTo("scheme");
        assertThat(OpenApiCodeGenerator.securityPrefix("shop.url")).isEqualTo("shop.security");
        assertThat(OpenApiCodeGenerator.securityPrefix("url")).isEqualTo("openapi.client.security");
    }
}
