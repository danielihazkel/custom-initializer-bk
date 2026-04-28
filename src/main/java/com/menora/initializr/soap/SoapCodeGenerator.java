package com.menora.initializr.soap;

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

    /** For the wizard's live preview — returns {@code "Service.Port: opA, opB"} style strings. */
    public List<String> detectServices(String wsdl) {
        if (wsdl == null || wsdl.isBlank()) return List.of();
        Definition def;
        try {
            def = parseOrThrow(wsdl);
        } catch (SoapParseException e) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (SoapServiceModel svc : buildServices(def)) {
            List<String> opNames = new ArrayList<>();
            for (SoapOperationModel op : svc.operations()) opNames.add(op.operationName());
            out.add(svc.serviceName() + "." + svc.portName() + ": " + String.join(", ", opNames));
        }
        return out;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private Definition parseOrThrow(String wsdl) {
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

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');
        sb.append("@Endpoint\n");
        sb.append("public class ").append(className).append(" {\n\n");

        for (SoapOperationModel op : svc.operations()) {
            sb.append("    @PayloadRoot(namespace = \"").append(escape(op.requestElementNamespace()))
                    .append("\", localPart = \"").append(escape(op.requestElementLocalPart())).append("\")\n");
            sb.append("    @ResponsePayload\n");
            sb.append("    public ").append(op.responseClassName()).append(' ').append(op.javaMethodName())
                    .append("(@RequestPayload ").append(op.requestClassName()).append(" request) {\n");
            sb.append("        throw new UnsupportedOperationException(\"TODO: implement ")
                    .append(op.javaMethodName()).append("\");\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + endpointPkg + "/" + className + ".java",
                sb.toString());
    }

    private GeneratedSoapFile renderEndpointConfig(List<SoapServiceModel> services, String packageName,
                                                   SoapWizardOptions opts, String wsdlBaseName) {
        String endpointPkg = opts.endpointPackageOrDefault();
        String fullPkg = packageName + "." + endpointPkg;
        String contextPath = opts.contextPathOrDefault();
        String mappingPattern = contextPath.endsWith("/") ? contextPath + "*" : contextPath + "/*";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        sb.append("import org.springframework.boot.web.servlet.ServletRegistrationBean;\n");
        sb.append("import org.springframework.context.ApplicationContext;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n");
        sb.append("import org.springframework.core.io.ClassPathResource;\n");
        sb.append("import org.springframework.ws.config.annotation.EnableWs;\n");
        sb.append("import org.springframework.ws.config.annotation.WsConfigurerAdapter;\n");
        sb.append("import org.springframework.ws.transport.http.MessageDispatcherServlet;\n");
        sb.append("import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;\n\n");

        sb.append("@EnableWs\n");
        sb.append("@Configuration\n");
        sb.append("public class WebServiceConfig extends WsConfigurerAdapter {\n\n");

        sb.append("    @Bean\n");
        sb.append("    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext applicationContext) {\n");
        sb.append("        MessageDispatcherServlet servlet = new MessageDispatcherServlet();\n");
        sb.append("        servlet.setApplicationContext(applicationContext);\n");
        sb.append("        servlet.setTransformWsdlLocations(true);\n");
        sb.append("        return new ServletRegistrationBean<>(servlet, \"").append(mappingPattern).append("\");\n");
        sb.append("    }\n\n");

        for (SoapServiceModel svc : services) {
            String beanName = toCamelCase(svc.serviceName());
            sb.append("    @Bean(name = \"").append(beanName).append("\")\n");
            sb.append("    public SimpleWsdl11Definition ").append(beanName).append("Wsdl() {\n");
            sb.append("        return new SimpleWsdl11Definition(new ClassPathResource(\"wsdl/")
                    .append(wsdlBaseName).append(".wsdl\"));\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + endpointPkg + "/WebServiceConfig.java",
                sb.toString());
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

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');
        sb.append("public class ").append(className).append(" extends WebServiceGatewaySupport {\n\n");

        for (SoapOperationModel op : svc.operations()) {
            sb.append("    public ").append(op.responseClassName()).append(' ').append(op.javaMethodName())
                    .append('(').append(op.requestClassName()).append(" request) {\n");
            sb.append("        return (").append(op.responseClassName())
                    .append(") getWebServiceTemplate().marshalSendAndReceive(request);\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/" + className + ".java",
                sb.toString());
    }

    private GeneratedSoapFile renderClientConfig(List<SoapServiceModel> services, String packageName,
                                                 SoapWizardOptions opts) {
        String clientPkg = opts.clientPackageOrDefault();
        String payloadPkg = opts.payloadPackageOrDefault();
        String fullPkg = packageName + "." + clientPkg;
        String payloadFullPkg = packageName + "." + payloadPkg;
        String baseUrlProp = opts.baseUrlPropertyOrDefault();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        sb.append("import org.springframework.beans.factory.annotation.Value;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n");
        sb.append("import org.springframework.oxm.jaxb.Jaxb2Marshaller;\n");
        for (SoapServiceModel svc : services) {
            // Each client class lives in the same package as this config — no import needed
        }
        sb.append('\n');

        sb.append("/**\n");
        sb.append(" * Wires the JAXB marshaller and one bean per generated SOAP client.\n");
        sb.append(" * The base URL is read from the {@code ").append(baseUrlProp).append("} property\n");
        sb.append(" * in application.yaml.\n");
        sb.append(" */\n");
        sb.append("@Configuration\n");
        sb.append("public class SoapClientConfig {\n\n");

        sb.append("    @Bean\n");
        sb.append("    public Jaxb2Marshaller soapMarshaller() {\n");
        sb.append("        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();\n");
        sb.append("        marshaller.setContextPath(\"").append(payloadFullPkg).append("\");\n");
        sb.append("        return marshaller;\n");
        sb.append("    }\n\n");

        for (SoapServiceModel svc : services) {
            String className = toPascalCase(svc.serviceName()) + "Client";
            String beanName = toCamelCase(svc.serviceName()) + "Client";
            sb.append("    @Bean\n");
            sb.append("    public ").append(className).append(' ').append(beanName)
                    .append("(Jaxb2Marshaller soapMarshaller, @Value(\"${").append(baseUrlProp).append("}\") String baseUrl) {\n");
            sb.append("        ").append(className).append(" client = new ").append(className).append("();\n");
            sb.append("        client.setDefaultUri(baseUrl);\n");
            sb.append("        client.setMarshaller(soapMarshaller);\n");
            sb.append("        client.setUnmarshaller(soapMarshaller);\n");
            sb.append("        return client;\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");

        return new GeneratedSoapFile(
                "src/main/java/{{packagePath}}/" + clientPkg + "/SoapClientConfig.java",
                sb.toString());
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

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unused")
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
