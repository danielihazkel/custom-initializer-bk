package com.menora.initializr.soap;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SoapCodeGenerator} — it had none. The whole output is pinned byte for
 * byte by {@code WizardGoldenOutputTests}; these say what the bytes mean.
 */
class SoapCodeGeneratorTest {

    private final SoapCodeGenerator generator = new SoapCodeGenerator();

    private static String wsdl() throws IOException {
        try (var in = new ClassPathResource("wizard-fixtures/countries.wsdl").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private Map<String, String> generate(SoapWizardOptions.GenerationMode mode) throws IOException {
        return generator.generate(wsdl(), "com.menora.demo", new SoapWizardOptions(null, null, null, mode, null, null))
                .stream().collect(Collectors.toMap(GeneratedSoapFile::relativePath, GeneratedSoapFile::content));
    }

    @Test
    void endpointsGetOneStubPerOperationAndOneWsdlBeanPerService() throws IOException {
        Map<String, String> files = generate(SoapWizardOptions.GenerationMode.ENDPOINTS);
        assertThat(files.keySet()).containsExactlyInAnyOrder(
                "src/main/java/{{packagePath}}/endpoint/CountryServiceEndpoint.java",
                "src/main/java/{{packagePath}}/endpoint/CurrencyServiceEndpoint.java",
                "src/main/java/{{packagePath}}/endpoint/WebServiceConfig.java",
                // The WSDL itself, for the JAXB classes the build generates from it.
                "src/main/resources/wsdl/country-service.wsdl");
        assertThat(files.get("src/main/java/{{packagePath}}/endpoint/CountryServiceEndpoint.java"))
                .contains("package com.menora.demo.endpoint;")
                .contains("import com.menora.demo.generated.GetCountryRequest;")
                // The payload root is the request element, as the WSDL spells it.
                .contains("@PayloadRoot(namespace = \"http://example.com/country\", localPart = \"getCountryRequest\")")
                .contains("public GetCountryResponse getCountry(@RequestPayload GetCountryRequest request) {")
                // A hyphenated operation and element become Java names.
                .contains("@PayloadRoot(namespace = \"http://example.com/country\", localPart = \"list-countries-request\")")
                .contains("public ListCountriesResponse listCountries(@RequestPayload ListCountriesRequest request) {")
                .contains("throw new UnsupportedOperationException(\"TODO: implement listCountries\");");
        assertThat(files.get("src/main/java/{{packagePath}}/endpoint/WebServiceConfig.java"))
                .contains("return new ServletRegistrationBean<>(servlet, \"/ws/*\");")
                .contains("@Bean(name = \"countryService\")")
                .contains("@Bean(name = \"currencyService\")")
                // Both services are read from the one WSDL the wizard writes (named after the first).
                .contains("new ClassPathResource(\"wsdl/country-service.wsdl\")");
    }

    @Test
    void clientsGetAGatewayPerServiceAndABaseUrlProperty() throws IOException {
        Map<String, String> files = generate(SoapWizardOptions.GenerationMode.CLIENT);
        assertThat(files.keySet()).containsExactlyInAnyOrder(
                "src/main/java/{{packagePath}}/client/CountryServiceClient.java",
                "src/main/java/{{packagePath}}/client/CurrencyServiceClient.java",
                "src/main/java/{{packagePath}}/client/SoapClientConfig.java",
                "src/main/resources/application.yaml",
                "src/main/resources/wsdl/country-service.wsdl");
        assertThat(files.get("src/main/java/{{packagePath}}/client/CountryServiceClient.java"))
                .contains("public class CountryServiceClient extends WebServiceGatewaySupport {")
                .contains("return (GetCountryResponse) getWebServiceTemplate().marshalSendAndReceive(request);");
        assertThat(files.get("src/main/java/{{packagePath}}/client/SoapClientConfig.java"))
                .contains("marshaller.setContextPath(\"com.menora.demo.generated\");")
                .contains("public CurrencyServiceClient currencyServiceClient(Jaxb2Marshaller soapMarshaller, "
                        + "@Value(\"${soap.client.base-url}\") String baseUrl) {");
        assertThat(files.get("src/main/resources/application.yaml"))
                .isEqualTo("soap:\n  client:\n    base-url: http://localhost:8080/ws\n");
    }

    @Test
    void optionsMoveThePackagesTheContextPathAndTheProperty() throws IOException {
        List<GeneratedSoapFile> files = generator.generate(wsdl(), "com.menora.demo", new SoapWizardOptions(
                "ws", "remote", "ws.model", SoapWizardOptions.GenerationMode.BOTH, "countries.url", "/services"));
        Map<String, String> byPath = files.stream().collect(Collectors.toMap(GeneratedSoapFile::relativePath, GeneratedSoapFile::content));
        assertThat(byPath.get("src/main/java/{{packagePath}}/ws/WebServiceConfig.java")).contains("\"/services/*\"");
        assertThat(byPath.get("src/main/java/{{packagePath}}/ws/CountryServiceEndpoint.java"))
                .contains("import com.menora.demo.ws.model.GetCountryRequest;");
        assertThat(byPath.get("src/main/java/{{packagePath}}/remote/SoapClientConfig.java"))
                .contains("@Value(\"${countries.url}\")").contains("{@code countries.url}");
        assertThat(byPath.get("src/main/resources/application.yaml"))
                .isEqualTo("countries:\n  url: http://localhost:8080/services\n");
    }

    @Test
    void aWsdlThatDoesNotParseSaysSo() {
        assertThatThrownBy(() -> generator.generate("<not-wsdl>", "com.menora.demo", null))
                .isInstanceOf(SoapCodeGenerator.SoapParseException.class);
        assertThat(generator.generate("  ", "com.menora.demo", null)).isEmpty();
    }

    @Test
    void detectsServicesForTheWizardsPreview() throws IOException {
        assertThat(generator.detectServices(wsdl())).hasSize(2).allSatisfy(s -> assertThat(s).isNotBlank());
    }
}
