package com.menora.initializr.soap;

import com.menora.initializr.config.WizardTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.Message;
import javax.wsdl.Operation;
import javax.wsdl.Part;
import javax.wsdl.Port;
import javax.wsdl.PortType;
import javax.wsdl.WSDLException;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses a WSDL via wsdl4j and emits Spring Web Services scaffolding —
 * {@code @Endpoint} server stubs, {@code WebServiceGatewaySupport} clients,
 * a {@code WebServiceConfig} bean, and the WSDL itself dropped into
 * {@code src/main/resources/wsdl/}.
 *
 * <p>JAXB payload classes are <em>not</em> emitted here — they are generated
 * at build time by the JAX-WS Maven plugin (configured by
 * {@code DynamicProjectGenerationConfiguration#soapBuildCustomizer}). The
 * generated package is conventionally
 * {@code <projectPackage>.<payloadSubPackage>}; emitted Java references that
 * package by import.
 */
@Service
public class SoapCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SoapCodeGenerator.class);

    /** Raised to the controller layer; converted to HTTP 400. */
    public static class SoapParseException extends RuntimeException {
        private final List<String> messages;
        public SoapParseException(List<String> messages) {
            super(String.join("; ", messages == null ? List.of() : messages));
            this.messages = messages == null ? List.of() : List.copyOf(messages);
        }
        public List<String> messages() { return messages; }
    }

    public List<GeneratedSoapFile> generate(String wsdl, String packageName, SoapWizardOptions options) {
        if (wsdl == null || wsdl.isBlank()) return List.of();
        Definition def = parseOrThrow(wsdl);
        SoapWizardOptions opts = options != null ? options
                : new SoapWizardOptions(null, null, null, null, null, null);

        List<SoapServiceModel> services = buildServices(def);
        if (services.isEmpty()) {
            throw new SoapParseException(List.of("WSDL contains no <service> definitions"));
        }

        String wsdlBaseName = pickWsdlFileName(services);
        List<GeneratedSoapFile> out = new ArrayList<>();
        out.add(new GeneratedSoapFile("src/main/resources/wsdl/" + wsdlBaseName + ".wsdl", wsdl));

        if (opts.emitEndpoints()) {
            for (SoapServiceModel svc : services) {
                out.add(renderEndpoint(svc, packageName, opts));
            }
            out.add(renderEndpointConfig(services, packageName, opts, wsdlBaseName));
        }
        if (opts.emitClient()) {
            for (SoapServiceModel svc : services) {
                out.add(renderClient(svc, packageName, opts));
            }
            out.add(renderClientConfig(services, packageName, opts));
            out.add(renderApplicationYmlStub(opts));
        }
        return out;
    }

    /**
     * For the wizard's live preview — returns {@code "Service.Port: opA, opB"} style strings.
     * Throws {@link SoapParseException} (a 400 from the wizard controller) for a document that
     * does not parse or declares no service — the same WSDL {@link #generate} would refuse.
     */
    public List<String> detectServices(String wsdl) {
        if (wsdl == null || wsdl.isBlank()) return List.of();
        List<SoapServiceModel> services = buildServices(parseOrThrow(wsdl));
        if (services.isEmpty()) {
            throw new SoapParseException(List.of("WSDL contains no <service> definitions"));
        }
        List<String> out = new ArrayList<>();
        for (SoapServiceModel svc : services) {
            List<String> opNames = new ArrayList<>();
            for (SoapOperationModel op : svc.operations()) opNames.add(op.operationName());
            out.add(svc.serviceName() + "." + svc.portName() + ": " + String.join(", ", opNames));
        }
        return out;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private Definition parseOrThrow(String wsdl) {
        // Before wsdl4j sees it: given a schemaLocation, its reader goes and opens the file —
        // relative to this server's working directory — or the URL.
        List<String> outside = unresolvedImports(wsdl);
        if (!outside.isEmpty()) {
            throw new SoapParseException(outside);
        }
        try {
            WSDLFactory factory = WSDLFactory.newInstance();
            WSDLReader reader = factory.newWSDLReader();
            reader.setFeature("javax.wsdl.verbose", false);
            // Imports are resolved relative to the document URI, which we don't have
            // for inline WSDLs — disable to avoid spurious lookup errors.
            reader.setFeature("javax.wsdl.importDocuments", false);
            Definition def = reader.readWSDL(null, new InputSource(new StringReader(wsdl)));
            if (def == null) {
                throw new SoapParseException(List.of("Empty or unrecognised WSDL document"));
            }
            return def;
        } catch (WSDLException e) {
            log.debug("WSDL parse failed", e);
            String msg = e.getMessage() == null ? "WSDL parse failed" : e.getMessage();
            throw new SoapParseException(List.of(msg));
        }
    }

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    /**
     * What the document refers to and does not contain. The wizard is given one document: a
     * {@code wsdl:import} or an {@code xsd:import}/{@code include}/{@code redefine} that names a
     * file points at something it never sees. Such a WSDL used to reach the parser, which tried
     * to open the file on this server; where that happened to succeed, the generated project
     * failed at its first build, when the JAX-WS plugin went looking for the file. An
     * {@code xsd:import} of a namespace alone is fine: that schema is inline, in the same
     * {@code <types>}.
     *
     * <p>Read with a parser that takes no DOCTYPE (a WSDL has none; entities are how a document
     * makes a parser read files). A document that is not XML at all is the WSDL parser's to report.
     */
    private static List<String> unresolvedImports(String wsdl) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            document = builder.parse(new InputSource(new StringReader(wsdl)));
        } catch (SAXParseException e) {
            throw new SoapParseException(List.of("The WSDL is not well-formed XML (line " + e.getLineNumber() + "): "
                    + e.getMessage()));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new SoapParseException(List.of("The WSDL could not be read: " + e.getMessage()));
        }
        List<String> out = new ArrayList<>();
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String name = element.getLocalName();
            if (WSDL_NS.equals(element.getNamespaceURI()) && "import".equals(name)) {
                addReference(out, "wsdl:import", element.getAttribute("location"));
            } else if (XSD_NS.equals(element.getNamespaceURI())
                    && ("import".equals(name) || "include".equals(name) || "redefine".equals(name))) {
                addReference(out, "xsd:" + name, element.getAttribute("schemaLocation"));
            }
        }
        return out;
    }

    private static void addReference(List<String> out, String kind, String location) {
        if (location == null || location.isBlank()) return;
        out.add("The WSDL refers to '" + location + "' (" + kind + "), which is not part of the document given. "
                + "Give one self-contained WSDL: put the imported definitions or schema inline.");
    }

    @SuppressWarnings("unchecked")
    private List<SoapServiceModel> buildServices(Definition def) {
        List<SoapServiceModel> out = new ArrayList<>();
        Map<QName, javax.wsdl.Service> services = def.getAllServices();
        if (services == null || services.isEmpty()) return out;

        for (var serviceEntry : services.entrySet()) {
            javax.wsdl.Service service = serviceEntry.getValue();
            Map<String, Port> ports = service.getPorts();
            if (ports == null) continue;
            for (Port port : ports.values()) {
                Binding binding = port.getBinding();
                if (binding == null) continue;
                PortType portType = binding.getPortType();
                if (portType == null) continue;

                List<SoapOperationModel> ops = new ArrayList<>();
                Set<String> usedJavaNames = new LinkedHashSet<>();
                for (BindingOperation bop : (List<BindingOperation>) binding.getBindingOperations()) {
                    Operation op = bop.getOperation();
                    if (op == null) continue;
                    SoapOperationModel model = toOperationModel(op, def.getTargetNamespace(), usedJavaNames);
                    if (model != null) ops.add(model);
                }

                String soapLocation = soapAddressLocation(port);
                out.add(new SoapServiceModel(
                        serviceEntry.getKey().getLocalPart(),
                        port.getName(),
                        portType.getQName() == null ? port.getName() : portType.getQName().getLocalPart(),
                        def.getTargetNamespace(),
                        soapLocation,
                        ops));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String soapAddressLocation(Port port) {
        if (port.getExtensibilityElements() == null) return null;
        for (Object ext : port.getExtensibilityElements()) {
            String cls = ext.getClass().getSimpleName();
            // Avoid hard imports on javax.wsdl.extensions.soap.* — match by name so
            // we transparently support both SOAP 1.1 and 1.2 address bindings.
            if (cls.equals("SOAPAddressImpl") || cls.equals("SOAP12AddressImpl")) {
                try {
                    return (String) ext.getClass().getMethod("getLocationURI").invoke(ext);
                } catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private SoapOperationModel toOperationModel(Operation op, String fallbackNs, Set<String> usedJavaNames) {
        String opName = op.getName();
        if (opName == null || opName.isBlank()) return null;
        String javaName = uniqueName(toCamelCase(opName), usedJavaNames);

        QName reqElement = firstElementName(op.getInput() == null ? null : op.getInput().getMessage());
        QName respElement = firstElementName(op.getOutput() == null ? null : op.getOutput().getMessage());

        String reqLocal = reqElement != null ? reqElement.getLocalPart() : opName;
        String reqNs = reqElement != null && reqElement.getNamespaceURI() != null
                ? reqElement.getNamespaceURI() : fallbackNs;
        String reqClass = toPascalCase(reqLocal);

        String respLocal = respElement != null ? respElement.getLocalPart() : (opName + "Response");
        String respClass = toPascalCase(respLocal);

        return new SoapOperationModel(
                opName, javaName, reqNs, reqLocal, reqClass, respLocal, respClass);
    }

    @SuppressWarnings("unchecked")
    private static QName firstElementName(Message msg) {
        if (msg == null) return null;
        List<Part> parts = msg.getOrderedParts(null);
        if (parts == null || parts.isEmpty()) return null;
        for (Part p : parts) {
            QName el = p.getElementName();
            if (el != null) return el;
        }
        QName typeName = parts.get(0).getTypeName();
        return typeName;
    }

    private static String pickWsdlFileName(List<SoapServiceModel> services) {
        if (services.isEmpty()) return "service";
        return toKebabCase(services.get(0).serviceName());
    }

    // ── Endpoint emission ─────────────────────────────────────────────────────

    private GeneratedSoapFile renderEndpoint(SoapServiceModel svc, String packageName, SoapWizardOptions opts) {
        String endpointPkg = opts.endpointPackageOrDefault();
        String payloadPkg = opts.payloadPackageOrDefault();
        String fullPkg = packageName + "." + endpointPkg;
        String payloadFullPkg = packageName + "." + payloadPkg;
        String className = toPascalCase(svc.serviceName()) + "Endpoint";

        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.ws.server.endpoint.annotation.Endpoint");
        imports.add("org.springframework.ws.server.endpoint.annotation.PayloadRoot");
        imports.add("org.springframework.ws.server.endpoint.annotation.RequestPayload");
        imports.add("org.springframework.ws.server.endpoint.annotation.ResponsePayload");
        for (SoapOperationModel op : svc.operations()) {
            imports.add(payloadFullPkg + "." + op.requestClassName());
            imports.add(payloadFullPkg + "." + op.responseClassName());
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("imports", imports);
        view.put("className", className);
        view.put("operations", operationViews(svc));

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + endpointPkg + "/" + className + ".java",
                WizardTemplates.render("soap/Endpoint.java", view));
    }

    /** A service's operations as the endpoint and client templates read them. */
    private static List<Map<String, Object>> operationViews(SoapServiceModel svc) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SoapOperationModel op : svc.operations()) {
            Map<String, Object> ov = new LinkedHashMap<>();
            ov.put("namespace", escape(op.requestElementNamespace()));
            ov.put("localPart", escape(op.requestElementLocalPart()));
            ov.put("responseClass", op.responseClassName());
            ov.put("requestClass", op.requestClassName());
            ov.put("method", op.javaMethodName());
            out.add(ov);
        }
        return out;
    }

    private GeneratedSoapFile renderEndpointConfig(List<SoapServiceModel> services, String packageName,
                                                   SoapWizardOptions opts, String wsdlBaseName) {
        String endpointPkg = opts.endpointPackageOrDefault();
        String fullPkg = packageName + "." + endpointPkg;
        String contextPath = opts.contextPathOrDefault();
        String mappingPattern = contextPath.endsWith("/") ? contextPath + "*" : contextPath + "/*";

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("mappingPattern", mappingPattern);
        List<Map<String, Object>> beans = new ArrayList<>();
        for (SoapServiceModel svc : services) {
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("beanName", toCamelCase(svc.serviceName()));
            sv.put("wsdlBaseName", wsdlBaseName);
            beans.add(sv);
        }
        view.put("services", beans);

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + endpointPkg + "/WebServiceConfig.java",
                WizardTemplates.render("soap/WebServiceConfig.java", view));
    }

    // ── Client emission ───────────────────────────────────────────────────────

    private GeneratedSoapFile renderClient(SoapServiceModel svc, String packageName, SoapWizardOptions opts) {
        String clientPkg = opts.clientPackageOrDefault();
        String payloadPkg = opts.payloadPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String payloadFullPkg = packageName + "." + payloadPkg;
        String className = toPascalCase(svc.serviceName()) + "Client";

        Set<String> imports = new TreeSet<>();
        imports.add("org.springframework.ws.client.core.support.WebServiceGatewaySupport");
        for (SoapOperationModel op : svc.operations()) {
            imports.add(payloadFullPkg + "." + op.requestClassName());
            imports.add(payloadFullPkg + "." + op.responseClassName());
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("imports", imports);
        view.put("className", className);
        view.put("operations", operationViews(svc));

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/" + className + ".java",
                WizardTemplates.render("soap/Client.java", view));
    }

    private GeneratedSoapFile renderClientConfig(List<SoapServiceModel> services, String packageName,
                                                 SoapWizardOptions opts) {
        String clientPkg = opts.clientPackageOrDefault();
        String payloadPkg = opts.payloadPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String payloadFullPkg = packageName + "." + payloadPkg;
        String baseUrlProp = opts.baseUrlPropertyOrDefault();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("baseUrlProperty", baseUrlProp);
        view.put("payloadPackage", payloadFullPkg);
        List<Map<String, Object>> clients = new ArrayList<>();
        for (SoapServiceModel svc : services) {
            // Each client class lives in this config's own package: no import needed.
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("className", toPascalCase(svc.serviceName()) + "Client");
            cv.put("beanName", toCamelCase(svc.serviceName()) + "Client");
            // Written whole: "${" beside a Mustache tag would read as a triple brace.
            cv.put("baseUrlPlaceholder", "${" + baseUrlProp + "}");
            clients.add(cv);
        }
        view.put("clients", clients);

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/SoapClientConfig.java",
                WizardTemplates.render("soap/ClientConfig.java", view));
    }

    private GeneratedSoapFile renderApplicationYmlStub(SoapWizardOptions opts) {
        String prop = opts.baseUrlPropertyOrDefault();
        String[] parts = prop.split("\\.");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            for (int s = 0; s < i * 2; s++) sb.append(' ');
            sb.append(parts[i]);
            if (i == parts.length - 1) {
                sb.append(": http://localhost:8080").append(opts.contextPathOrDefault()).append('\n');
            } else {
                sb.append(":\n");
            }
        }
        return new GeneratedSoapFile("src/main/resources/application.yaml", sb.toString());
    }

    // ── Naming helpers ────────────────────────────────────────────────────────

    private static String uniqueName(String base, Set<String> used) {
        if (used.add(base)) return base;
        int i = 2;
        while (!used.add(base + "_" + i)) i++;
        return base + "_" + i;
    }

    private static String toPascalCase(String raw) {
        if (raw == null || raw.isEmpty()) return "Unnamed";
        String[] parts = raw.split("[_\\-\\s.]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        String out = sb.length() == 0 ? "Unnamed" : sb.toString();
        return out.replaceAll("[^A-Za-z0-9]", "");
    }

    private static String toCamelCase(String raw) {
        String pascal = toPascalCase(raw);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static String toKebabCase(String raw) {
        if (raw == null || raw.isEmpty()) return "service";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('-');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Convenience for callers that want the conventional payload package path. */
    public static String payloadFullPackage(String projectPackage, SoapWizardOptions opts) {
        SoapWizardOptions o = opts != null ? opts : new SoapWizardOptions(null, null, null, null, null, null);
        return projectPackage + "." + o.payloadPackageOrDefault();
    }

    /**
     * Returns one entry per WSDL we'd hand to the JAX-WS plugin. The plugin
     * config built around this is mode-aware, but the file dropped on disk is
     * the same for both modes — both endpoint mode and client mode unmarshal
     * the same JAXB classes generated from the WSDL.
     */
    public List<String> resolveWsdlFileNames(String wsdl) {
        if (wsdl == null || wsdl.isBlank()) return List.of();
        Definition def;
        try {
            def = parseOrThrow(wsdl);
        } catch (SoapParseException e) {
            return List.of();
        }
        List<SoapServiceModel> services = buildServices(def);
        if (services.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(pickWsdlFileName(services));
        return new ArrayList<>(seen);
    }
}
