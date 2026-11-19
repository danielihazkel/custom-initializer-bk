package com.menora.initializr.openapi;

import java.util.List;
import java.util.Set;

/**
 * A parsed OpenAPI operation ready for controller and client rendering.
 *
 * @param requestBodyRequired whether the document says the body must be sent
 * @param successStatus       the status the operation declares for success (the first 2xx it
 *                            lists), or 200 when it declares none
 * @param bodyKind            what the request body is: JSON (a {@code requestBodyType}), or a form
 *                            whose properties are {@code formFields}
 * @param security            the security requirements, each an alternative of schemes used
 *                            together; the client sends the first. Empty: none.
 */
public record OperationModel(
        String httpMethod,
        String path,
        String operationId,
        String tag,
        String summary,
        List<ParamModel> params,
        String requestBodyType,
        Set<String> requestBodyImports,
        boolean requestBodyRequired,
        String responseType,
        Set<String> responseImports,
        int successStatus,
        BodyKind bodyKind,
        List<FormField> formFields,
        List<List<SecurityUse>> security) {

    public enum BodyKind { NONE, JSON, MULTIPART, FORM }

    /** An operation with a JSON body (or none) and no security, as before forms and schemes were read. */
    public OperationModel(String httpMethod, String path, String operationId, String tag, String summary,
                          List<ParamModel> params, String requestBodyType, Set<String> requestBodyImports,
                          boolean requestBodyRequired, String responseType, Set<String> responseImports,
                          int successStatus) {
        this(httpMethod, path, operationId, tag, summary, params, requestBodyType, requestBodyImports,
                requestBodyRequired, responseType, responseImports, successStatus,
                requestBodyType == null ? BodyKind.NONE : BodyKind.JSON, List.of(), List.of());
    }

    /** A multipart or form body. */
    public boolean isForm() {
        return bodyKind == BodyKind.MULTIPART || bodyKind == BodyKind.FORM;
    }

    /**
     * The requirements the client can meet, in the document's order: each an alternative of the
     * schemes it sends together. Up to the first that asks for nothing (a call may then be made
     * without credentials), and without an alternative it cannot send (mutual TLS alone).
     */
    public List<List<SecurityUse>> clientAlternatives() {
        List<List<SecurityUse>> out = new java.util.ArrayList<>();
        for (List<SecurityUse> alternative : security) {
            if (alternative.isEmpty()) break;
            List<SecurityUse> sendable = alternative.stream().filter(SecurityUse::sendable).toList();
            if (sendable.size() == alternative.size()) out.add(sendable);
        }
        return out;
    }

    /** Every scheme the client may send, once each: the credentials it reads. */
    public List<SecurityUse> clientSecurity() {
        java.util.Map<String, SecurityUse> out = new java.util.LinkedHashMap<>();
        for (List<SecurityUse> alternative : clientAlternatives()) {
            for (SecurityUse use : alternative) out.putIfAbsent(use.scheme(), use);
        }
        return List.copyOf(out.values());
    }
}
