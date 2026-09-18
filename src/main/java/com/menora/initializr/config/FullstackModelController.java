package com.menora.initializr.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.FullstackModelEntity;
import com.menora.initializr.db.repository.FullstackModelRepository;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Team-shared fullstack entity models — the Fullstack tab's "Team" presets.
 *
 * <p>Reads and writes are open to every engineer (the only auth in this app is the shared
 * admin password on {@code /admin/*}, and requiring it to save a <em>team</em> preset would
 * defeat the purpose of an internal tool). The stored {@code snapshot} is the UI's
 * {@code menora-fullstack-model/1} export kept verbatim; the server only checks that it is a
 * JSON object carrying an {@code entities} array and caps its size. Names are unique
 * case-insensitively (409 on a clash). {@code createdBy} comes from the SSO user header —
 * the same {@code menora.audit.user-header} the generation audit uses.
 *
 * <p>Deliberately <em>not</em> part of the admin configuration export/import: that path
 * wipes and re-inserts the catalog, and team models are user content.
 */
@RestController
@RequestMapping("/metadata/fullstack/models")
public class FullstackModelController {

    static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;
    static final int MAX_NAME_LENGTH = 150;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private final FullstackModelRepository repo;
    private final ObjectMapper objectMapper;
    private final String userHeader;

    public FullstackModelController(FullstackModelRepository repo,
                                    ObjectMapper objectMapper,
                                    @Value("${menora.audit.user-header:userinfo}") String userHeader) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.userHeader = userHeader;
    }

    // ── Wire shapes ─────────────────────────────────────────────────────────

    public record ModelSummary(Long id, String name, String description, int entityCount,
                               String createdBy, Instant createdAt, Instant updatedAt) {
        static ModelSummary of(FullstackModelEntity e) {
            return new ModelSummary(e.getId(), e.getName(), e.getDescription(), e.getEntityCount(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record ModelDetail(Long id, String name, String description, int entityCount,
                              String createdBy, Instant createdAt, Instant updatedAt, JsonNode snapshot) {
    }

    public record ModelRequest(String name, String description, JsonNode snapshot) {
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping
    public List<ModelSummary> list() {
        return repo.findAllByOrderByUpdatedAtDesc().stream().map(ModelSummary::of).toList();
    }

    @GetMapping("/{id}")
    public ModelDetail get(@PathVariable Long id) {
        FullstackModelEntity e = find(id);
        return new ModelDetail(e.getId(), e.getName(), e.getDescription(), e.getEntityCount(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), readSnapshot(e));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ModelSummary> create(@RequestBody ModelRequest body,
                                               @RequestHeader Map<String, String> headers) {
        Validated v = validate(body);
        repo.findByNameIgnoreCase(v.name).ifPresent(existing -> {
            throw new ConflictException("A team model named '" + existing.getName() + "' already exists");
        });
        Instant now = Instant.now();
        FullstackModelEntity e = new FullstackModelEntity();
        e.setName(v.name);
        e.setDescription(v.description);
        e.setSnapshot(v.snapshotJson);
        e.setEntityCount(v.entityCount);
        e.setCreatedBy(currentUser(headers));
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return ResponseEntity.status(HttpStatus.CREATED).body(ModelSummary.of(repo.save(e)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ModelSummary update(@PathVariable Long id, @RequestBody ModelRequest body) {
        FullstackModelEntity e = find(id);
        Validated v = validate(body);
        repo.findByNameIgnoreCase(v.name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("A team model named '" + other.getName() + "' already exists");
                });
        e.setName(v.name);
        e.setDescription(v.description);
        e.setSnapshot(v.snapshotJson);
        e.setEntityCount(v.entityCount);
        e.setUpdatedAt(Instant.now());
        return ModelSummary.of(repo.save(e));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        FullstackModelEntity e = find(id);
        repo.delete(e);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private record Validated(String name, String description, String snapshotJson, int entityCount) {
    }

    private Validated validate(ModelRequest body) {
        if (body == null) throw new InvalidModelException("Request body is required");
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) throw new InvalidModelException("name is required");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidModelException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        String description = body.description() == null ? null : body.description().trim();
        if (description != null && description.isEmpty()) description = null;
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidModelException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        JsonNode snapshot = body.snapshot();
        if (snapshot == null || !snapshot.isObject()) {
            throw new InvalidModelException("snapshot must be a JSON object");
        }
        JsonNode entities = snapshot.get("entities");
        if (entities == null || !entities.isArray()) {
            throw new InvalidModelException("snapshot.entities must be an array");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new InvalidModelException("snapshot is not serializable: " + ex.getOriginalMessage());
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw new InvalidModelException("snapshot exceeds " + MAX_SNAPSHOT_BYTES + " bytes");
        }
        return new Validated(name, description, json, entities.size());
    }

    private FullstackModelEntity find(Long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("No team model with id " + id));
    }

    private JsonNode readSnapshot(FullstackModelEntity e) {
        try {
            return objectMapper.readTree(e.getSnapshot());
        } catch (JsonProcessingException ex) {
            // Only reachable if the row was hand-edited: a stored snapshot always came through validate().
            throw new IllegalStateException("Stored snapshot of team model " + e.getId() + " is not valid JSON", ex);
        }
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

    static final class InvalidModelException extends RuntimeException {
        InvalidModelException(String message) { super(message); }
    }

    static final class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }

    static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    @ExceptionHandler(InvalidModelException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(InvalidModelException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid team model", "detail", ex.getMessage()));
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
