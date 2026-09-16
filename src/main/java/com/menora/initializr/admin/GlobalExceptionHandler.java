package com.menora.initializr.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application-wide REST error handling. Returns a consistent {@code {error, detail}}
 * JSON body for validation, conflict and unexpected failures across every controller.
 *
 * <p>Controller-local {@code @ExceptionHandler} methods (e.g. the wizard parse-exception
 * handlers in {@code WizardStarterController}/{@code FullstackStarterController}) take
 * precedence over this advice, so their richer {@code dep}/{@code snippet} bodies are
 * preserved. {@code @Order(LOWEST_PRECEDENCE)} keeps this advice from shadowing them.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of("errors", fieldErrors));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String detail = ex.getMostSpecificCause().getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Duplicate or constraint violation", "detail", detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        String detail = ex.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid request body", "detail", detail));
    }

    /**
     * The catalog-import and fullstack type parsers signal bad caller input with plain
     * {@code IllegalArgumentException}, so 400 is the right status for them. The cost is
     * that a genuine internal IAE thrown deeper in the generator also comes back as
     * "Validation failed" — so log it with a stack trace, otherwise such a bug is invisible.
     * (Wizard input parsing uses {@link com.menora.initializr.config.WizardArgumentException},
     * which is deliberately not an IAE.)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Request rejected as invalid argument", ex);
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Validation failed", "detail", String.valueOf(ex.getMessage())));
    }

    /**
     * Spring signals "no mapping for this path" with these, and they would otherwise be
     * caught by {@link #handleUnexpected} below and reported as a 500 — so every unknown
     * URL looked like a server fault instead of a 404.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, String>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found", "detail", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        // Generation controllers wrap IO failures in UncheckedIOException — surface the
        // underlying cause message rather than the wrapper's.
        Throwable cause = (ex instanceof UncheckedIOException && ex.getCause() != null)
                ? ex.getCause() : ex;
        log.error("Unhandled exception while serving request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal error", "detail", String.valueOf(cause.getMessage())));
    }
}
