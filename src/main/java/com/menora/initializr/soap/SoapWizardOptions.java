package com.menora.initializr.soap;

import java.util.Locale;

/**
 * Per-dependency SOAP wizard options. The wizard can emit Spring-WS
 * {@code @Endpoint} server stubs, a {@code WebServiceGatewaySupport} client,
 * or both. The actual JAXB payload classes are generated at build time by
 * the JAX-WS Maven plugin from the WSDL we drop into the project.
 */
public record SoapWizardOptions(
        String endpointSubPackage,
        String clientSubPackage,
        String payloadSubPackage,
        GenerationMode mode,
        String baseUrlProperty,
        String contextPath) {

    public static final String DEFAULT_ENDPOINT_PKG = "endpoint";
    public static final String DEFAULT_CLIENT_PKG = "client";
    public static final String DEFAULT_PAYLOAD_PKG = "generated";
    public static final String DEFAULT_BASE_URL_PROPERTY = "soap.client.base-url";
    public static final String DEFAULT_CONTEXT_PATH = "/ws";

    public enum GenerationMode {
        ENDPOINTS, CLIENT, BOTH;

        public static GenerationMode parse(String raw) {
            if (raw == null || raw.isBlank()) return ENDPOINTS;
            try {
                return GenerationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ENDPOINTS;
            }
        }
    }

    public String endpointPackageOrDefault() {
        return (endpointSubPackage == null || endpointSubPackage.isBlank())
                ? DEFAULT_ENDPOINT_PKG : endpointSubPackage;
    }

    public String clientPackageOrDefault() {
        return (clientSubPackage == null || clientSubPackage.isBlank())
                ? DEFAULT_CLIENT_PKG : clientSubPackage;
    }

    public String payloadPackageOrDefault() {
        return (payloadSubPackage == null || payloadSubPackage.isBlank())
                ? DEFAULT_PAYLOAD_PKG : payloadSubPackage;
    }

    public GenerationMode modeOrDefault() {
        return mode == null ? GenerationMode.ENDPOINTS : mode;
    }

    public String baseUrlPropertyOrDefault() {
        return (baseUrlProperty == null || baseUrlProperty.isBlank())
                ? DEFAULT_BASE_URL_PROPERTY : baseUrlProperty;
    }

    public String contextPathOrDefault() {
        return (contextPath == null || contextPath.isBlank())
                ? DEFAULT_CONTEXT_PATH : contextPath;
    }

    public boolean emitEndpoints() {
        GenerationMode m = modeOrDefault();
        return m == GenerationMode.ENDPOINTS || m == GenerationMode.BOTH;
    }

    public boolean emitClient() {
        GenerationMode m = modeOrDefault();
        return m == GenerationMode.CLIENT || m == GenerationMode.BOTH;
    }
}
