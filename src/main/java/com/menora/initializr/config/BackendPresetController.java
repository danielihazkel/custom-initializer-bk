package com.menora.initializr.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.BackendPresetEntity;
import com.menora.initializr.db.repository.BackendPresetRepository;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Team-shared Backend-tab setups — the Backend tab's "Team" presets, beside the ones a browser
 * keeps for itself. The rules are the Fullstack tab's team models' ({@link FullstackModelController}):
 * reading and saving are open to every engineer, changing or deleting one is for whoever saved it
 * or a signed-in admin ({@link TeamDataAccess}), names are unique ignoring case (409), the stored
 * {@code snapshot} is the UI's state of the tab kept verbatim (a JSON object with a {@code form}
 * object and a {@code selected} array, at most a megabyte), {@code createdBy} comes from the SSO
 * user header, and none of it is part of the admin configuration export/import.
 */
@RestController
@RequestMapping("/metadata/backend/presets")
public class BackendPresetController {

    static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;
    static final int MAX_NAME_LENGTH = 150;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private final BackendPresetRepository repo;
    private final ObjectMapper objectMapper;
    private final TeamDataAccess access;

    public BackendPresetController(BackendPresetRepository repo,
                                   ObjectMapper objectMapper,
                                   TeamDataAccess access) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.access = access;
    }

    // ── Wire shapes ─────────────────────────────────────────────────────────

    /** {@code canEdit}: whether whoever asked may change or delete it. */
    public record PresetSummary(Long id, String name, String description, String coordinates,
                                int dependencyCount, int moduleCount,
                                String createdBy, Instant createdAt, Instant updatedAt,
                                String updatedBy, boolean canEdit) {
        static PresetSummary of(BackendPresetEntity e, boolean canEdit) {
            return new PresetSummary(e.getId(), e.getName(), e.getDescription(), e.getCoordinates(),
                    e.getDependencyCount(), e.getModuleCount(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                    e.getUpdatedBy(), canEdit);
        }

        static PresetSummary of(BackendPresetRepository.Summary s, boolean canEdit) {
            return new PresetSummary(s.getId(), s.getName(), s.getDescription(), s.getCoordinates(),
                    s.getDependencyCount(), s.getModuleCount(), s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt(),
                    s.getUpdatedBy(), canEdit);
        }
    }

    public record PresetDetail(Long id, String name, String description, String coordinates,
                               int dependencyCount, int moduleCount,
                               String createdBy, Instant createdAt, Instant updatedAt, JsonNode snapshot,
                               String updatedBy, boolean canEdit) {
        static PresetDetail of(BackendPresetEntity e, JsonNode snapshot, boolean canEdit) {
            return new PresetDetail(e.getId(), e.getName(), e.getDescription(), e.getCoordinates(),
                    e.getDependencyCount(), e.getModuleCount(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), snapshot,
                    e.getUpdatedBy(), canEdit);
        }
    }

    public record PresetRequest(String name, String description, JsonNode snapshot) {
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping
    public List<PresetSummary> list(HttpServletRequest request) {
        TeamDataAccess.Caller caller = access.callerOf(request);
        return repo.findAllSummariesByOrderByUpdatedAtDesc().stream()
                .map(s -> PresetSummary.of(s, access.canEdit(s.getCreatedBy(), caller))).toList();
    }

    @GetMapping("/{id}")
    public PresetDetail get(@PathVariable Long id, HttpServletRequest request) {
        BackendPresetEntity e = find(id);
        try {
            return PresetDetail.of(e, objectMapper.readTree(e.getSnapshot()),
                    access.canEdit(e.getCreatedBy(), access.callerOf(request)));
        } catch (JsonProcessingException ex) {
            // Only reachable for a hand-edited row: a stored snapshot always came through validate().
            throw new IllegalStateException("Stored snapshot of team preset " + e.getId() + " is not valid JSON", ex);
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<PresetSummary> create(@RequestBody PresetRequest body, HttpServletRequest request) {
        TeamDataAccess.Caller caller = access.callerOf(request);
        Validated v = validate(body);
        repo.findByNameIgnoreCase(v.name).ifPresent(existing -> {
            throw new ConflictException("A team preset named '" + existing.getName() + "' already exists");
        });
        Instant now = Instant.now();
        BackendPresetEntity e = new BackendPresetEntity();
        apply(e, v);
        // The creator is the gateway's user only: a row nobody is named for stays open to all.
        e.setCreatedBy(caller.user());
        e.setUpdatedBy(caller.name());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return ResponseEntity.status(HttpStatus.CREATED).body(PresetSummary.of(repo.save(e), true));
    }

    @PutMapping("/{id}")
    @Transactional
    public PresetSummary update(@PathVariable Long id, @RequestBody PresetRequest body, HttpServletRequest request) {
        BackendPresetEntity e = find(id);
        TeamDataAccess.Caller caller = access.callerOf(request);
        access.requireEdit(e.getCreatedBy(), caller);
        Validated v = validate(body);
        repo.findByNameIgnoreCase(v.name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("A team preset named '" + other.getName() + "' already exists");
                });
        apply(e, v);
        e.setUpdatedBy(caller.name());
        e.setUpdatedAt(Instant.now());
        return PresetSummary.of(repo.save(e), true);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        BackendPresetEntity e = find(id);
        access.requireEdit(e.getCreatedBy(), access.callerOf(request));
        repo.delete(e);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private record Validated(String name, String description, String snapshotJson, String coordinates,
                             int dependencyCount, int moduleCount) {
    }

    private static void apply(BackendPresetEntity e, Validated v) {
        e.setName(v.name);
        e.setDescription(v.description);
        e.setSnapshot(v.snapshotJson);
        e.setCoordinates(v.coordinates);
        e.setDependencyCount(v.dependencyCount);
        e.setModuleCount(v.moduleCount);
    }

    private Validated validate(PresetRequest body) {
        if (body == null) throw new InvalidPresetException("Request body is required");
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) throw new InvalidPresetException("name is required");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidPresetException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        String description = body.description() == null ? null : body.description().trim();
        if (description != null && description.isEmpty()) description = null;
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidPresetException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        JsonNode snapshot = body.snapshot();
        if (snapshot == null || !snapshot.isObject()) throw new InvalidPresetException("snapshot must be a JSON object");
        JsonNode form = snapshot.get("form");
        if (form == null || !form.isObject()) throw new InvalidPresetException("snapshot.form must be an object");
        JsonNode selected = snapshot.get("selected");
        if (selected == null || !selected.isArray()) throw new InvalidPresetException("snapshot.selected must be an array");
        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new InvalidPresetException("snapshot is not serializable: " + ex.getOriginalMessage());
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw new InvalidPresetException("snapshot exceeds " + MAX_SNAPSHOT_BYTES + " bytes");
        }
        JsonNode modules = snapshot.get("selectedModules");
        int moduleCount = snapshot.path("multiModuleEnabled").asBoolean(false) && modules != null && modules.isArray()
                ? modules.size() : 0;
        String coordinates = form.path("groupId").asText("") + ":" + form.path("artifactId").asText("");
        return new Validated(name, description, json,
                coordinates.length() > 300 ? coordinates.substring(0, 300) : coordinates,
                selected.size(), moduleCount);
    }

    private BackendPresetEntity find(Long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("No team preset with id " + id));
    }

    // ── Errors (the {error, detail} shape GlobalExceptionHandler uses) ─────

    static final class InvalidPresetException extends RuntimeException {
        InvalidPresetException(String message) { super(message); }
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

    @ExceptionHandler(InvalidPresetException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(InvalidPresetException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid team preset", "detail", ex.getMessage()));
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
