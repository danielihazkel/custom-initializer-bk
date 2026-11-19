package com.menora.initializr.config;

import com.menora.initializr.observability.AuditDetails;
import com.menora.initializr.observability.SnapshotStore;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * As a generated zip is about to be written — the framework's {@code /starter.zip} included:
 * {@code X-Snapshot} names what the project is ({@link GeneratedSnapshots}), and a download or an
 * update keeps it ({@link SnapshotStore#keepCurrent}). The editors keep the id with the model they
 * generated and send it back when they ask for an update of that project; on an update's zip it
 * names what the project becomes once the update is applied. Done here, before the body goes out,
 * so a project is kept before anyone holding it can ask for its update.
 */
@ControllerAdvice
public class SnapshotHeaderAdvice implements ResponseBodyAdvice<Object> {

    private final SnapshotStore snapshotStore;

    public SnapshotHeaderAdvice(SnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof byte[]) {
            String id = AuditDetails.snapshotId();
            if (id != null) {
                if (!response.getHeaders().containsKey(GeneratedSnapshots.HEADER)) {
                    response.getHeaders().set(GeneratedSnapshots.HEADER, id);
                }
                snapshotStore.keepCurrent(request.getURI().getPath());
            }
        }
        return body;
    }
}
