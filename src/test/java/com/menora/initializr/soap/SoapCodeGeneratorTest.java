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
                        .anySatisfy(m -> assertThat(m).contains("'more.xsd' (xsd:include)")
                                .contains("not part of the document given").contains("Add that file")));
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

    private static String split(String path) throws IOException {
        try (var in = new ClassPathResource("wizard-fixtures/countries-split/" + path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /** The three files the split fixture's main document comes with. */
    private static Map<String, String> splitFiles() throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        for (String path : List.of("port/country-port.wsdl", "schemas/countries.xsd", "schemas/types.xsd")) {
            files.put(path, split(path));
        }
        return files;
    }

    private Map<String, String> generateSplit(SoapWizardOptions.GenerationMode mode, Map<String, String> files) throws IOException {
        return generator.generate(split("countries.wsdl"), files, "com.menora.demo",
                        new SoapWizardOptions(null, null, null, mode, null, null))
                .stream().collect(Collectors.toMap(GeneratedSoapFile::relativePath, GeneratedSoapFile::content));
    }

    @Test
    void aWsdlInSeveralFilesIsReadThroughItsImports() throws IOException {
        // The binding and the service are in the main document; the port type and its messages
        // in a WSDL it imports; the elements in a schema that one imports from a folder beside its
        // own; their types in a schema the schema includes.
        assertThat(generator.detectServices(split("countries.wsdl"), splitFiles()))
                .containsExactly("CountryService.CountryPort: getCountry, getCurrency");

        Map<String, String> files = generateSplit(SoapWizardOptions.GenerationMode.BOTH, splitFiles());
        // Every file is in the project where the main document's references find it.
        assertThat(files).containsEntry("src/main/resources/wsdl/country-service.wsdl", split("countries.wsdl"))
                .containsEntry("src/main/resources/wsdl/port/country-port.wsdl", split("port/country-port.wsdl"))
                .containsEntry("src/main/resources/wsdl/schemas/countries.xsd", split("schemas/countries.xsd"))
                .containsEntry("src/main/resources/wsdl/schemas/types.xsd", split("schemas/types.xsd"));
        // The payload is in the schema's namespace, not the main document's nor the port type's.
        assertThat(files.get("src/main/java/{{packagePath}}/endpoint/CountryServiceEndpoint.java"))
                .contains("@PayloadRoot(namespace = \"http://example.com/country\", localPart = \"getCountryRequest\")")
                .contains("public GetCurrencyResponse getCurrency(@RequestPayload GetCurrencyRequest request)");
        // And each file is served under its name, so whoever fetches the WSDL can follow it.
        assertThat(files.get("src/main/java/{{packagePath}}/endpoint/WebServiceConfig.java"))
                .contains("import org.springframework.xml.xsd.SimpleXsdSchema;")
                .contains("import org.springframework.xml.xsd.XsdSchema;")
                .contains("new SimpleWsdl11Definition(new ClassPathResource(\"wsdl/country-service.wsdl\"))")
                .contains("""
                            @Bean(name = "country-port")
                            public SimpleWsdl11Definition countryPortWsdl() {
                                return new SimpleWsdl11Definition(new ClassPathResource("wsdl/port/country-port.wsdl"));
                            }
                        """)
                .contains("""
                            @Bean(name = "countries")
                            public XsdSchema countriesSchema() {
                                return new SimpleXsdSchema(new ClassPathResource("wsdl/schemas/countries.xsd"));
                            }
                        """)
                .contains("public XsdSchema typesSchema()");
        // The plugin is given the main document alone.
        assertThat(generator.resolveWsdlFileNames(split("countries.wsdl"), splitFiles())).containsExactly("country-service");
        // A client-only project serves nothing.
        assertThat(generateSplit(SoapWizardOptions.GenerationMode.CLIENT, splitFiles()))
                .containsKey("src/main/resources/wsdl/schemas/types.xsd")
                .doesNotContainKey("src/main/java/{{packagePath}}/endpoint/WebServiceConfig.java");
    }

    @Test
    void aWsdlInOneDocumentIsWrittenAsBefore() throws IOException {
        Map<String, String> one = generate(SoapWizardOptions.GenerationMode.BOTH);
        assertThat(one.get("src/main/java/{{packagePath}}/endpoint/WebServiceConfig.java"))
                .doesNotContain("XsdSchema").doesNotContain("served under the name");
        // No files and an empty map are the same request.
        assertThat(generator.generate(wsdl(), Map.of(), "com.menora.demo",
                new SoapWizardOptions(null, null, null, SoapWizardOptions.GenerationMode.BOTH, null, null))
                .stream().collect(Collectors.toMap(GeneratedSoapFile::relativePath, GeneratedSoapFile::content))).isEqualTo(one);
    }

    @Test
    void whatTheFilesDoNotHoldIsNamedWithTheFileThatAsksForIt() throws IOException {
        // A file missing from the set: named, with the document that refers to it.
        Map<String, String> noTypes = splitFiles();
        noTypes.remove("schemas/types.xsd");
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), noTypes))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                        .singleElement().asString()
                        .contains("'schemas/countries.xsd' refers to 'types.xsd' (xsd:include)")
                        .contains("not among the files given"));
        // The reference is followed from the folder of the file that makes it.
        Map<String, String> flat = splitFiles();
        flat.put("countries.xsd", flat.remove("schemas/countries.xsd"));
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), flat))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                        .anySatisfy(m -> assertThat(m).contains("'port/country-port.wsdl' refers to '../schemas/countries.xsd' (xsd:import)"))
                        .anySatisfy(m -> assertThat(m).contains("'countries.xsd' refers to 'types.xsd' (xsd:include)")));

        // An address is never fetched, whatever files came along; nor is a path out of the folder followed.
        Map<String, String> remote = splitFiles();
        remote.put("schemas/countries.xsd", remote.get("schemas/countries.xsd")
                .replace("schemaLocation=\"types.xsd\"", "schemaLocation=\"http://127.0.0.1:9/types.xsd\""));
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), remote))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                        .singleElement().asString().contains("'http://127.0.0.1:9/types.xsd' (xsd:include), an address"));
        for (String address : List.of("file:///etc/hostname", "/etc/hostname", "//host/share/types.xsd", "C:\\types.xsd")) {
            Map<String, String> local = splitFiles();
            local.put("schemas/countries.xsd", local.get("schemas/countries.xsd")
                    .replace("schemaLocation=\"types.xsd\"", "schemaLocation=\"" + address + "\""));
            assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), local)).as(address)
                    .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                            .singleElement().asString().contains("an address"));
        }
        Map<String, String> outside = splitFiles();
        outside.put("schemas/countries.xsd", outside.get("schemas/countries.xsd")
                .replace("schemaLocation=\"types.xsd\"", "schemaLocation=\"../../types.xsd\""));
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), outside))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                        .singleElement().asString().contains("outside the folder of the main WSDL"));
    }

    @Test
    void aFileIsHeldToWhatTheMainDocumentIs() throws IOException {
        // A DOCTYPE in any of the files: refused before the WSDL parser sees it, naming the file.
        Map<String, String> entity = splitFiles();
        entity.put("schemas/types.xsd", "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>\n" + entity.get("schemas/types.xsd"));
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), entity))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                        .singleElement().asString().contains("'schemas/types.xsd' is not well-formed XML"));
        // Neither a WSDL nor a schema.
        Map<String, String> other = splitFiles();
        other.put("notes.xml", "<notes/>");
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), other))
                .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                        .singleElement().asString().contains("'notes.xml' is neither a WSDL"));
        // A name that is no path inside the project, a kind of file the wizard does not take, an empty file.
        for (String name : List.of("../types.xsd", "/etc/types.xsd", "C:/types.xsd", "schemas//types.xsd", "a b.xsd",
                "types.yaml", "types", ".hidden.xsd", "schemas/../../x.xsd")) {
            Map<String, String> bad = splitFiles();
            bad.put(name, split("schemas/types.xsd"));
            assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), bad)).as(name)
                    .isInstanceOfSatisfying(SoapCodeGenerator.SoapParseException.class, e -> assertThat(e.messages())
                            .singleElement().asString().contains("is not a file name the wizard takes"));
        }
        Map<String, String> empty = splitFiles();
        empty.put("extra.xsd", " ");
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), empty))
                .hasMessageContaining("'extra.xsd' is empty");
        // Windows separators and a leading ./ are the same path.
        Map<String, String> windows = new java.util.LinkedHashMap<>();
        windows.put("port\\country-port.wsdl", split("port/country-port.wsdl"));
        windows.put("./schemas/countries.xsd", split("schemas/countries.xsd"));
        windows.put("schemas\\types.xsd", split("schemas/types.xsd"));
        assertThat(generator.detectServices(split("countries.wsdl"), windows)).hasSize(1);
        // Two names one disk cannot tell apart.
        Map<String, String> cased = splitFiles();
        cased.put("schemas/Types.xsd", split("schemas/types.xsd"));
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), cased))
                .hasMessageContaining("the same file on a disk that ignores case");
        // More files than the wizard takes.
        Map<String, String> many = splitFiles();
        for (int i = 0; i < WsdlFiles.MAX_FILES; i++) many.put("extra/s" + i + ".xsd", split("schemas/types.xsd"));
        assertThatThrownBy(() -> generator.detectServices(split("countries.wsdl"), many))
                .hasMessageContaining("the wizard takes " + WsdlFiles.MAX_FILES);
    }

    @Test
    void filesThatCannotBeWrittenOrServedSideBySideAreRefused() throws IOException {
        // The main document is written under its first service's name.
        Map<String, String> taken = splitFiles();
        taken.put("Country-Service.wsdl",
                "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\" targetNamespace=\"urn:other\"/>");
        assertThatThrownBy(() -> generateSplit(SoapWizardOptions.GenerationMode.CLIENT, taken))
                .hasMessageContaining("'Country-Service.wsdl' has the name the main WSDL is written under");
        // Spring-WS serves a file by its name alone: two of one name, or one named like a service.
        Map<String, String> twice = splitFiles();
        twice.put("common/types.xsd", split("schemas/types.xsd"));
        assertThatThrownBy(() -> generateSplit(SoapWizardOptions.GenerationMode.ENDPOINTS, twice))
                .hasMessageContaining("'schemas/types.xsd' cannot be served beside 'common/types.xsd'");
        Map<String, String> likeTheService = splitFiles();
        likeTheService.put("countryService.xsd", split("schemas/types.xsd"));
        assertThatThrownBy(() -> generateSplit(SoapWizardOptions.GenerationMode.BOTH, likeTheService))
                .hasMessageContaining("cannot be served beside the service CountryService");
        // Nothing is served by a client: both are fine there.
        assertThat(generateSplit(SoapWizardOptions.GenerationMode.CLIENT, twice)).containsKey("src/main/resources/wsdl/common/types.xsd");
        // A file that is neither .wsdl nor .xsd is in the project and has no bean.
        Map<String, String> xml = splitFiles();
        xml.put("extra/more.xml", split("schemas/types.xsd"));
        Map<String, String> files = generateSplit(SoapWizardOptions.GenerationMode.ENDPOINTS, xml);
        assertThat(files).containsKey("src/main/resources/wsdl/extra/more.xml");
        assertThat(files.get("src/main/java/{{packagePath}}/endpoint/WebServiceConfig.java")).doesNotContain("more");
    }

    @Test
    void detectsServicesForTheWizardsPreview() throws IOException {
        assertThat(generator.detectServices(wsdl())).hasSize(2).allSatisfy(s -> assertThat(s).isNotBlank());
    }
}
