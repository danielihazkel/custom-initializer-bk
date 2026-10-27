package com.menora.initializr.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.FullstackTemplateEntity;
import com.menora.initializr.db.repository.FullstackTemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Team-shared layout-designer templates — a page ({@code kind = "page"}) or a dashboard widget
 * ({@code kind = "widget"}) saved for everyone who opens the generator, beside the per-browser
 * "My templates" / "My widgets".
 *
 * <p>Open to every engineer, like {@link FullstackModelController} (and for the same reason). The
 * {@code content} is the UI's page or widget definition kept verbatim; the server checks only
 * that it is a JSON object with a {@code type} (page) or {@code kind} (widget) and caps its size —
 * a template names entities and pages of whatever model it came from, so the designer fits it to
 * the model it lands in. Names are unique per kind, case-insensitively (409). The list carries the
 * content too: templates are small, and the library draws them all at once.
 *
 * <p>Not part of the admin configuration export/import: they are user content, like team models.
 */
@RestController
@RequestMapping("/metadata/fullstack/templates")
public class FullstackTemplateController {

    static final Set<String> KINDS = Set.of("page", "widget");
    static final int MAX_CONTENT_BYTES = 64 * 1024;
    static final int MAX_NAME_LENGTH = 150;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private final FullstackTemplateRepository repo;
    private final ObjectMapper objectMapper;
    private final String userHeader;

    public FullstackTemplateController(FullstackTemplateRepository repo,
                                       ObjectMapper objectMapper,
                                       @Value("${menora.audit.user-header:userinfo}") String userHeader) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.userHeader = userHeader;
    }

    // ── Wire shapes ─────────────────────────────────────────────────────────

    public record TemplateView(Long id, String kind, String name, String description, String createdBy,
                               Instant createdAt, Instant updatedAt, JsonNode content) {
    }

    public record TemplateRequest(String kind, String name, String description, JsonNode content) {
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping
    public List<TemplateView> list(@RequestParam(required = false) String kind) {
        List<FullstackTemplateEntity> rows;
        if (kind == null || kind.isBlank()) {
            rows = repo.findAllByOrderByUpdatedAtDesc();
        } else {
            rows = repo.findAllByKindOrderByUpdatedAtDesc(kindOf(kind));
        }
        return rows.stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public TemplateView get(@PathVariable Long id) {
        return view(find(id));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<TemplateView> create(@RequestBody TemplateRequest body,
                                               @RequestHeader Map<String, String> headers) {
        Validated v = validate(body);
        repo.findByKindAndNameIgnoreCase(v.kind, v.name).ifPresent(existing -> {
            throw new ConflictException("A team " + v.kind + " template named '" + existing.getName() + "' already exists");
        });
        Instant now = Instant.now();
        FullstackTemplateEntity e = new FullstackTemplateEntity();
        e.setKind(v.kind);
        e.setName(v.name);
        e.setDescription(v.description);
        e.setContent(v.contentJson);
        e.setCreatedBy(currentUser(headers));
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(repo.save(e)));
    }

    @PutMapping("/{id}")
    @Transactional
    public TemplateView update(@PathVariable Long id, @RequestBody TemplateRequest body) {
        FullstackTemplateEntity e = find(id);
        Validated v = validate(body);
        if (!v.kind.equals(e.getKind())) {
            throw new InvalidTemplateException("kind cannot change (this template is a " + e.getKind() + ")");
        }
        repo.findByKindAndNameIgnoreCase(v.kind, v.name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("A team " + v.kind + " template named '" + other.getName() + "' already exists");
                });
        e.setName(v.name);
        e.setDescription(v.description);
        e.setContent(v.contentJson);
        e.setUpdatedAt(Instant.now());
        return view(repo.save(e));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repo.delete(find(id));
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private record Validated(String kind, String name, String description, String contentJson) {
    }

    private Validated validate(TemplateRequest body) {
        if (body == null) throw new InvalidTemplateException("Request body is required");
        String kind = kindOf(body.kind());
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) throw new InvalidTemplateException("name is required");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidTemplateException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        String description = body.description() == null ? null : body.description().trim();
        if (description != null && description.isEmpty()) description = null;
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidTemplateException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        JsonNode content = body.content();
        if (content == null || !content.isObject()) {
            throw new InvalidTemplateException("content must be a JSON object");
        }
        String key = kind.equals("page") ? "type" : "kind";
        JsonNode marker = content.get(key);
        if (marker == null || !marker.isTextual() || marker.asText().isBlank()) {
            throw new InvalidTemplateException("content." + key + " is required for a " + kind + " template");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException ex) {
            throw new InvalidTemplateException("content is not serializable: " + ex.getOriginalMessage());
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new InvalidTemplateException("content exceeds " + MAX_CONTENT_BYTES + " bytes");
        }
        return new Validated(kind, name, description, json);
    }

    private static String kindOf(String raw) {
        String kind = raw == null ? "" : raw.trim().toLowerCase();
        if (!KINDS.contains(kind)) throw new InvalidTemplateException("kind must be 'page' or 'widget'");
        return kind;
    }

    private FullstackTemplateEntity find(Long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("No team template with id " + id));
    }

    private TemplateView view(FullstackTemplateEntity e) {
        JsonNode content;
        try {
            content = objectMapper.readTree(e.getContent());
        } catch (JsonProcessingException ex) {
            // Only reachable if the row was hand-edited: stored content always came through validate().
            throw new IllegalStateException("Stored content of team template " + e.getId() + " is not valid JSON", ex);
        }
        return new TemplateView(e.getId(), e.getKind(), e.getName(), e.getDescription(), e.getCreatedBy(),
                e.getCreatedAt(), e.getUpdatedAt(), content);
    }

    /** Header names arrive lower-cased in the map; the configured header may not be. */
    private String currentUser(Map<String, String> headers) {
        String v = Optional.ofNullable(headers.get(userHeader.toLowerCase()))
                .orElseGet(() -> headers.get(userHeader));
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty()) return null;
        return v.length() > 255 ? v.substring(0, 255) : v;
    }

    // ── Errors (same {error, detail} shape as GlobalExceptionHandler) ──────

    static final class InvalidTemplateException extends RuntimeException {
        InvalidTemplateException(String message) { super(message); }
    }

    static final class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }

    static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    @ExceptionHandler(InvalidTemplateException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(InvalidTemplateException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid team template", "detail", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Name already in use", "detail", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found", "detail", ex.getMessage()));
    }
}
