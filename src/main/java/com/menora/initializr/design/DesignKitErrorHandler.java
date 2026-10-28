package com.menora.initializr.design;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code {error, detail, problems?}} for the design-kit endpoints, like the rest of the admin API.
 * Ordered first: {@code GlobalExceptionHandler}'s catch-all would otherwise answer 500.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {DesignKitAdminController.class, DesignKitController.class})
public class DesignKitErrorHandler {

    @ExceptionHandler(DesignKitException.class)
    public ResponseEntity<Map<String, Object>> handle(DesignKitException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", switch (ex.status()) {
            case NOT_FOUND -> "Not found";
            case CONFLICT -> "Conflict";
            default -> "Invalid design kit";
        });
        body.put("detail", ex.getMessage());
        if (!ex.problems().isEmpty()) body.put("problems", ex.problems());
        return ResponseEntity.status(ex.status()).body(body);
    }
}
