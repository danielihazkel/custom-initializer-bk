package com.menora.initializr.soap;

/**
 * One WSDL operation reduced to what the code emitter needs:
 * a Java method name, the request/response element local-parts (used as
 * {@code @PayloadRoot} keys), and the JAXB class names that the JAX-WS
 * Maven plugin will generate at build time.
 */
public record SoapOperationModel(
        String operationName,
        String javaMethodName,
        String requestElementNamespace,
        String requestElementLocalPart,
        String requestClassName,
        String responseElementLocalPart,
        String responseClassName) {
}
