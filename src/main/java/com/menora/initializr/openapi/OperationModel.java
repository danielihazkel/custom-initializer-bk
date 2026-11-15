package com.menora.initializr.openapi;

import java.util.List;
import java.util.Set;

/**
 * A parsed OpenAPI operation ready for controller and client rendering.
 *
 * @param requestBodyRequired whether the document says the body must be sent
 * @param successStatus       the status the operation declares for success (the first 2xx it
 *                            lists), or 200 when it declares none
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
        int successStatus) {
}
