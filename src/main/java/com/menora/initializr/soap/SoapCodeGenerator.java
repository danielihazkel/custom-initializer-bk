package com.menora.initializr.soap;

import com.menora.initializr.config.WizardTemplates;
import com.menora.initializr.gen.WizardMemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;

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
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses a WSDL via wsdl4j and emits Spring Web Services scaffolding —
 * {@code @Endpoint} server stubs, {@code WebServiceGatewaySupport} clients,
 * a {@code WebServiceConfig} bean, and the WSDL itself dropped into
 * {@code src/main/resources/wsdl/} — with the files it imports, when it comes in several
 * ({@link WsdlFiles}), at their own paths beside it.
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
        return generate(wsdl, null, packageName, options);
    }

    /**
     * @param files the documents the WSDL refers to, by their path relative to it; null or empty
     *              for a WSDL in one document
     */
    public List<GeneratedSoapFile> generate(String wsdl, Map<String, String> files, String packageName,
                                            SoapWizardOptions options) {
        if (wsdl == null || wsdl.isBlank()) return List.of();
        SoapWizardOptions opts = options != null ? options
                : new SoapWizardOptions(null, null, null, null, null, null);
        WsdlFiles documents = WsdlFiles.of(wsdl, files);
        // A wizard request checks its WSDL by generating it, then writes what the same inputs give.
        return WizardMemo.get(WizardMemo.Key.of("soap", documents, packageName, opts),
                () -> render(documents, packageName, opts));
    }

    private List<GeneratedSoapFile> render(WsdlFiles documents, String packageName, SoapWizardOptions opts) {
        List<SoapServiceModel> services = servicesOf(documents);
        if (services.isEmpty()) {
            throw new SoapParseException(List.of("WSDL contains no <service> definitions"));
        }

        String wsdlBaseName = pickWsdlFileName(services);
        String taken = documents.pathIgnoringCase(wsdlBaseName + ".wsdl");
        if (taken != null) {
            throw new SoapParseException(List.of("The file '" + taken + "' has the name the main WSDL is written "
                    + "under (it is named after its first service). Rename the file, and the references to it."));
        }
        List<GeneratedSoapFile> out = new ArrayList<>();
        out.add(new GeneratedSoapFile(WSDL_FOLDER + wsdlBaseName + ".wsdl", documents.main()));
        documents.files().forEach((path, text) -> out.add(new GeneratedSoapFile(WSDL_FOLDER + path, text)));

        if (opts.emitEndpoints()) {
            for (SoapServiceModel svc : services) {
                out.add(renderEndpoint(svc, packageName, opts));
            }
            out.add(renderEndpointConfig(services, packageName, opts, wsdlBaseName, documents));
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
        return detectServices(wsdl, null);
    }

    /** The same for a WSDL that comes with the {@code files} it refers to. */
    public List<String> detectServices(String wsdl, Map<String, String> files) {
        if (wsdl == null || wsdl.isBlank()) return List.of();
        List<SoapServiceModel> services = servicesOf(WsdlFiles.of(wsdl, files));
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

    /** Where a project keeps its WSDL and the files that come with it. */
    private static final String WSDL_FOLDER = "src/main/resources/wsdl/";

    /** The services of a WSDL, read once per request however many times they are asked for. */
    private List<SoapServiceModel> servicesOf(WsdlFiles documents) {
        return WizardMemo.get(WizardMemo.Key.of("soap-parse", documents), () -> buildServices(parseOrThrow(documents)));
    }

    private Definition parseOrThrow(WsdlFiles documents) {
        // Before wsdl4j sees them: given a schemaLocation and no locator, its reader goes and
        // opens the file — relative to this server's working directory — or the URL.
        List<String> problems = documents.problems();
        if (!problems.isEmpty()) {
            throw new SoapParseException(problems);
        }
        try {
            WSDLFactory factory = WSDLFactory.newInstance();
            WSDLReader reader = factory.newWSDLReader();
            reader.setFeature("javax.wsdl.verbose", false);
            Definition def;
            if (documents.alone()) {
                // Nothing to import: every reference to a file was a problem above.
                reader.setFeature("javax.wsdl.importDocuments", false);
                def = reader.readWSDL(null, new InputSource(new StringReader(documents.main())));
            } else {
                // Imports come from the files given, through a locator: the reader opens nothing.
                reader.setFeature("javax.wsdl.importDocuments", true);
                def = reader.readWSDL(documents.locator());
            }
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

    @SuppressWarnings("unchecked")
    private List<SoapServiceModel> buildServices(Definition def) {
        List<SoapServiceModel> out = new ArrayList<>();
        Map<QName, javax.wsdl.Service> services = def.getAllServices();
        if (services == null || services.isEmpty()) return out;

        for (var serviceEntry : services.entrySet()) {
            javax.wsdl.Service service = serviceEntry.getValue();
            // A service of an imported WSDL is in that document's namespace, not the main one's.
            String namespace = serviceEntry.getKey().getNamespaceURI() == null
                    || serviceEntry.getKey().getNamespaceURI().isEmpty()
                    ? def.getTargetNamespace() : serviceEntry.getKey().getNamespaceURI();
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
                    SoapOperationModel model = toOperationModel(op, namespace, usedJavaNames);
                    if (model != null) ops.add(model);
                }

                String soapLocation = soapAddressLocation(port);
                out.add(new SoapServiceModel(
                        serviceEntry.getKey().getLocalPart(),
                        port.getName(),
                        portType.getQName() == null ? port.getName() : portType.getQName().getLocalPart(),
                        namespace,
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
                                                   SoapWizardOptions opts, String wsdlBaseName, WsdlFiles documents) {
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
        List<Map<String, Object>> served = servedDocuments(services, documents);
        view.put("documents", served);
        view.put("hasSchemas", served.stream().anyMatch(d -> "XsdSchema".equals(d.get("type"))));

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + endpointPkg + "/WebServiceConfig.java",
                WizardTemplates.render("soap/WebServiceConfig.java", view));
    }

    /**
     * The files the application serves beside the WSDL, so that whoever fetches the WSDL can
     * follow what it refers to: Spring-WS answers {@code <name>.wsdl} with the WSDL definition
     * bean called {@code <name>} and {@code <name>.xsd} with the schema bean of that name —
     * whatever folder the request names, so {@code schemas/types.xsd} is the bean {@code types}.
     * That makes two files of one name, or a file named like a service, a clash: a 400 that says
     * which. A file that is neither {@code .wsdl} nor {@code .xsd} is in the project and not served.
     */
    private static List<Map<String, Object>> servedDocuments(List<SoapServiceModel> services, WsdlFiles documents) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (documents.alone()) return out;
        Map<String, String> beans = new LinkedHashMap<>();
        Set<String> methods = new LinkedHashSet<>();
        methods.add("messageDispatcherServlet");
        for (SoapServiceModel svc : services) {
            String bean = toCamelCase(svc.serviceName());
            beans.put(bean, "the service " + svc.serviceName());
            methods.add(bean + "Wsdl");
        }
        for (String path : documents.files().keySet()) {
            String lower = path.toLowerCase(Locale.ROOT);
            boolean schema = lower.endsWith(".xsd");
            if (!schema && !lower.endsWith(".wsdl")) continue;
            String file = path.substring(path.lastIndexOf('/') + 1);
            String bean = file.substring(0, file.lastIndexOf('.'));
            String other = beans.putIfAbsent(bean, "'" + path + "'");
            if (other != null) {
                throw new SoapParseException(List.of("'" + path + "' cannot be served beside " + other
                        + ": Spring-WS serves a WSDL or a schema by its file name alone, and both are '" + bean
                        + "'. Rename one of them (and the references to it), or generate the client only."));
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("beanName", escape(bean));
            view.put("path", escape(path));
            view.put("type", schema ? "XsdSchema" : "SimpleWsdl11Definition");
            view.put("implementation", schema ? "SimpleXsdSchema" : "SimpleWsdl11Definition");
            view.put("method", uniqueName(javaName(bean) + (schema ? "Schema" : "Wsdl"), methods));
            out.add(view);
        }
        return out;
    }

    /** A file's name as the start of a Java method name. */
    private static String javaName(String raw) {
        String name = toCamelCase(raw);
        return Character.isJavaIdentifierStart(name.charAt(0)) ? name : "file" + name;
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
        return resolveWsdlFileNames(wsdl, null);
    }

    /**
     * The same for a WSDL in several files: still the main document alone — the plugin follows
     * its imports to the files beside it.
     */
    public List<String> resolveWsdlFileNames(String wsdl, Map<String, String> files) {
        if (wsdl == null || wsdl.isBlank()) return List.of();
        List<SoapServiceModel> services;
        try {
            services = servicesOf(WsdlFiles.of(wsdl, files));
        } catch (SoapParseException e) {
            return List.of();
        }
        if (services.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(pickWsdlFileName(services));
        return new ArrayList<>(seen);
    }
}
