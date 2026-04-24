package com.menora.initializr.openapi;

import java.util.List;
import java.util.Set;

/** A parsed OpenAPI operation ready for controller rendering. */
public record OperationModel(
        String httpMethod,
        String path,
        String operationId,
        String tag,
        String summary,
        List<ParamModel> params,
        String requestBodyType,
        Set<String> requestBodyImports,
        String responseType,
        Set<String> responseImports) {
}
