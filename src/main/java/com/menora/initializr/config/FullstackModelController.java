package com.menora.initializr.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.FullstackModelEntity;
import com.menora.initializr.db.repository.FullstackModelRepository;
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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Team-shared fullstack entity models — the Fullstack tab's "Team" presets.
 *
 * <p>Reading and saving are open to every engineer (requiring a login to save a <em>team</em>
 * preset would defeat the purpose of an internal tool); changing or deleting a model is for
 * whoever saved it or a signed-in admin ({@link TeamDataAccess}), and each answer says whether the
 * caller may ({@code canEdit}) and who changed it last ({@code updatedBy}). The stored {@code snapshot} is the UI's
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
    private final TeamDataAccess access;

    public FullstackModelController(FullstackModelRepository repo,
                                    ObjectMapper objectMapper,
                                    TeamDataAccess access) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.access = access;
    }

    // ── Wire shapes ─────────────────────────────────────────────────────────

    /** {@code canEdit}: whether whoever asked may change or delete it. */
    public record ModelSummary(Long id, String name, String description, int entityCount,
                               String createdBy, Instant createdAt, Instant updatedAt,
                               String updatedBy, boolean canEdit) {
        static ModelSummary of(FullstackModelEntity e, boolean canEdit) {
            return new ModelSummary(e.getId(), e.getName(), e.getDescription(), e.getEntityCount(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), e.getUpdatedBy(), canEdit);
        }

        static ModelSummary of(FullstackModelRepository.Summary s, boolean canEdit) {
            return new ModelSummary(s.getId(), s.getName(), s.getDescription(), s.getEntityCount(),
                    s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt(), s.getUpdatedBy(), canEdit);
        }
    }

    public record ModelDetail(Long id, String name, String description, int entityCount,
                              String createdBy, Instant createdAt, Instant updatedAt, JsonNode snapshot,
                              String updatedBy, boolean canEdit) {
    }

    public record ModelRequest(String name, String description, JsonNode snapshot) {
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping
    public List<ModelSummary> list(HttpServletRequest request) {
        TeamDataAccess.Caller caller = access.callerOf(request);
        return repo.findAllSummariesByOrderByUpdatedAtDesc().stream()
                .map(s -> ModelSummary.of(s, access.canEdit(s.getCreatedBy(), caller))).toList();
    }

    @GetMapping("/{id}")
    public ModelDetail get(@PathVariable Long id, HttpServletRequest request) {
        FullstackModelEntity e = find(id);
        return new ModelDetail(e.getId(), e.getName(), e.getDescription(), e.getEntityCount(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), readSnapshot(e),
                e.getUpdatedBy(), access.canEdit(e.getCreatedBy(), access.callerOf(request)));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ModelSummary> create(@RequestBody ModelRequest body, HttpServletRequest request) {
        TeamDataAccess.Caller caller = access.callerOf(request);
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
        // The creator is the gateway's user only: a row nobody is named for stays open to all.
        e.setCreatedBy(caller.user());
        e.setUpdatedBy(caller.name());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return ResponseEntity.status(HttpStatus.CREATED).body(ModelSummary.of(repo.save(e), true));
    }

    @PutMapping("/{id}")
    @Transactional
    public ModelSummary update(@PathVariable Long id, @RequestBody ModelRequest body, HttpServletRequest request) {
        FullstackModelEntity e = find(id);
        TeamDataAccess.Caller caller = access.callerOf(request);
        access.requireEdit(e.getCreatedBy(), caller);
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
        e.setUpdatedBy(caller.name());
        e.setUpdatedAt(Instant.now());
        return ModelSummary.of(repo.save(e), true);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        FullstackModelEntity e = find(id);
        access.requireEdit(e.getCreatedBy(), access.callerOf(request));
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

    @ExceptionHandler(TeamDataAccess.NotAllowedException.class)
    public ResponseEntity<Map<String, String>> handleNotAllowed(TeamDataAccess.NotAllowedException ex) {
        return TeamDataAccess.refusal(ex);
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
