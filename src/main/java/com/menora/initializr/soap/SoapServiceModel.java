package com.menora.initializr.soap;

import java.util.List;

/**
 * Lightweight, post-parse view of a single WSDL {@code <service>} entry —
 * just what the code emitter needs.
 */
public record SoapServiceModel(
        String serviceName,
        String portName,
        String portTypeName,
        String targetNamespace,
        String soapAddressLocation,
        List<SoapOperationModel> operations) {
}
