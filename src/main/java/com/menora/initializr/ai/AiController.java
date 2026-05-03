package com.menora.initializr.ai;

import com.menora.initializr.ai.AiDtos.AiGenerationRequest;
import com.menora.initializr.ai.AiDtos.AiGenerationResponse;
import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import com.menora.initializr.ai.AiException.AiDisabledException;
import com.menora.initializr.ai.AiException.AiResponseParseException;
import com.menora.initializr.ai.AiException.AiTimeoutException;
import com.menora.initializr.ai.AiException.AiUpstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Front door for the AI file-contribution feature. The UI calls this with the
 * user's prompt + project metadata + currently selected dependencies; the
 * response is a list of {path, content} files the user can review/remove
 * before sending them back into {@code POST /starter-wizard.zip}.
 *
 * <p>Exception handlers are inline (not in {@code GlobalExceptionHandler}, which
 * is scoped to the {@code admin} package) so AI errors get mapped to the
 * documented HTTP statuses without leaking into other controllers.
 */
@RestController
public class AiController {

    private final AiFileGenerationService service;

    public AiController(AiFileGenerationService service) {
        this.service = service;
    }

    @PostMapping("/ai/generate-files")
    public AiGenerationResponse generate(@RequestBody AiGenerationRequest body) {
        List<GeneratedAiFile> files = service.generate(body);
        return new AiGenerationResponse(files);
    }

    @ExceptionHandler(AiDisabledException.class)
    public ResponseEntity<Map<String, String>> handleDisabled(AiDisabledException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "AI feature disabled", "detail", ex.getMessage()));
    }

    @ExceptionHandler(AiTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleTimeout(AiTimeoutException ex) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "AI endpoint timed out", "detail", ex.getMessage()));
    }

    @ExceptionHandler(AiUpstreamException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(AiUpstreamException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "AI endpoint error", "detail", ex.getMessage()));
    }

    @ExceptionHandler(AiResponseParseException.class)
    public ResponseEntity<Map<String, String>> handleParse(AiResponseParseException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Could not parse AI response", "detail", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid request", "detail", ex.getMessage()));
    }
}
