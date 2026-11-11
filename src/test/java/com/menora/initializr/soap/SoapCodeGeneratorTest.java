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
    void aWsdlThatImportsAFileItDoesNotContainIsRefusedByName() {
        // The wizard is given one document. A reference to another file reached the WSDL parser,
        // which went and opened it — relative to the server's working directory, or by URL. It is
        // refused before the parser sees it, each reference named.
        String wsdl = """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                                  xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/" xmlns:tns="urn:orders"
                                  targetNamespace="urn:orders">
                  <wsdl:import namespace="urn:common" location="common/faults.wsdl"/>
                  <wsdl:types>
                    <xsd:schema targetNamespace="urn:orders">
                      <xsd:import namespace="urn:types" schemaLocation="types.xsd"/>
                      <xsd:import namespace="urn:inline"/>
                      <xsd:include schemaLocation="more.xsd"/>
                      <xsd:element name="PlaceOrder" type="xsd:string"/>
                    </xsd:schema>
                    <xsd:schema targetNamespace="urn:inline"/>
                  </wsdl:types>
                  <wsdl:message name="in"><wsdl:part name="p" element="tns:PlaceOrder"/></wsdl:message>
                  <wsdl:portType name="Orders">
                    <wsdl:operation name="PlaceOrder"><wsdl:input message="tns:in"/></wsdl:operation>
                  </wsdl:portType>
                  <wsdl:binding name="OrdersBinding" type="tns:Orders">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="PlaceOrder"><soap:operation soapAction=""/><wsdl:input><soap:body use="literal"/></wsdl:input></wsdl:operation>
                  </wsdl:binding>
                  <wsdl:service name="OrderService">
                    <wsdl:port name="OrdersPort" binding="tns:OrdersBinding"><soap:address location="http://localhost/orders"/></wsdl:port>
                  </wsdl:service>
                </wsdl:definitions>
                """;
        assertThatThrownBy(() -> generator.generate(wsdl, "com.menora.demo", null))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                        .hasSize(3)
                        .anySatisfy(m -> assertThat(m).contains("'common/faults.wsdl' (wsdl:import)"))
                        .anySatisfy(m -> assertThat(m).contains("'types.xsd' (xsd:import)"))
                        .anySatisfy(m -> assertThat(m).contains("'more.xsd' (xsd:include)").contains("self-contained")));
        // The preview says so too, instead of listing services whose build would fail.
        assertThatThrownBy(() -> generator.detectServices(wsdl)).isInstanceOf(SoapCodeGenerator.SoapParseException.class);
        // An import of a namespace alone is a schema in the same document: fine.
        String selfContained = wsdl.replace("<wsdl:import namespace=\"urn:common\" location=\"common/faults.wsdl\"/>", "")
                .replace(" schemaLocation=\"types.xsd\"", "").replace("<xsd:include schemaLocation=\"more.xsd\"/>", "");
        assertThat(generator.detectServices(selfContained)).containsExactly("OrderService.OrdersPort: PlaceOrder");
        // A DOCTYPE is how a document makes a parser read files: a WSDL has none.
        assertThatThrownBy(() -> generator.detectServices(
                "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>\n" + selfContained))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class,
                        e -> assertThat(e.messages()).singleElement().asString().contains("not well-formed XML"));
    }

    @Test
    void detectsServicesForTheWizardsPreview() throws IOException {
        assertThat(generator.detectServices(wsdl())).hasSize(2).allSatisfy(s -> assertThat(s).isNotBlank());
    }
}
