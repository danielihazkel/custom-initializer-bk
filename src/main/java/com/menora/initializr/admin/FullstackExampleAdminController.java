package com.menora.initializr.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.db.entity.FullstackExampleEntity;
import com.menora.initializr.db.repository.FullstackExampleRepository;
import com.menora.initializr.fullstack.FullstackRequestValidator;
import com.menora.initializr.fullstack.FullstackStarterRequest;
import com.menora.initializr.fullstack.FullstackStarterRequest.EntityDefinitionDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin CRUD for the Fullstack tab's "Start from → Examples". Behind the {@code /admin/*}
 * auth filter like the rest of the admin API.
 *
 * <p>{@code entities} travels as real JSON (not a string) and is run through
 * {@link FullstackRequestValidator} — the generator's own validator — before it is stored,
 * so an example an admin saves always generates.
 */
@RestController
@RequestMapping("/admin/fullstack-examples")
public class FullstackExampleAdminController {

    static final Pattern EXAMPLE_ID = Pattern.compile("^[a-z][a-z0-9-]*$");
    static final int MAX_ENTITIES_CHARS = 1024 * 1024;

    private final FullstackExampleRepository repo;
    private final ObjectMapper objectMapper;

    public FullstackExampleAdminController(FullstackExampleRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** Admin wire shape — the entity with {@code entities} as parsed JSON. */
    public record ExampleAdminView(Long id, String exampleId, String name, String description, String icon,
                                   JsonNode entities, int sortOrder, boolean enabled) {
    }

    public record ExampleRequest(String exampleId, String name, String description, String icon,
                                 JsonNode entities, Integer sortOrder, Boolean enabled) {
    }

    @GetMapping
    public List<ExampleAdminView> list() {
        return repo.findAllByOrderBySortOrderAscIdAsc().stream().map(this::view).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ExampleAdminView> create(@RequestBody ExampleRequest body) {
        FullstackExampleEntity e = new FullstackExampleEntity();
        apply(e, body, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(repo.save(e)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ExampleAdminView update(@PathVariable Long id, @RequestBody ExampleRequest body) {
        FullstackExampleEntity e = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("No example with id " + id));
        apply(e, body, id);
        return view(repo.save(e));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void apply(FullstackExampleEntity e, ExampleRequest body, Long selfId) {
        if (body == null) throw new InvalidExampleException("Request body is required");
        String exampleId = trimToNull(body.exampleId());
        if (exampleId == null) throw new InvalidExampleException("exampleId is required");
        if (exampleId.length() > 50 || !EXAMPLE_ID.matcher(exampleId).matches()) {
            throw new InvalidExampleException(
                    "exampleId must be lower-case letters, digits and '-', starting with a letter (max 50)");
        }
        repo.findByExampleId(exampleId)
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new ConflictException("An example with id '" + exampleId + "' already exists");
                });
        String name = trimToNull(body.name());
        if (name == null) throw new InvalidExampleException("name is required");
        if (name.length() > 150) throw new InvalidExampleException("name must be at most 150 characters");
        String description = trimToNull(body.description());
        if (description != null && description.length() > 500) {
            throw new InvalidExampleException("description must be at most 500 characters");
        }
        String icon = trimToNull(body.icon());
        if (icon != null && (icon.length() > 60 || !icon.matches("^[a-z0-9_]+$"))) {
            throw new InvalidExampleException("icon must be a Material Symbols name (lower-case, digits, '_')");
        }

        e.setExampleId(exampleId);
        e.setName(name);
        e.setDescription(description);
        e.setIcon(icon);
        e.setEntities(validateEntities(body.entities(), objectMapper));
        e.setSortOrder(body.sortOrder() == null ? 0 : body.sortOrder());
        e.setEnabled(body.enabled() == null || body.enabled());
    }

    /**
     * Checks {@code entities} is an array the fullstack generator accepts and returns it as
     * compact JSON text. Shared with the configuration import so an imported example is held
     * to the same rule.
     */
    public static String validateEntities(JsonNode entities, ObjectMapper objectMapper) {
        if (entities == null || !entities.isArray() || entities.isEmpty()) {
            throw new InvalidExampleException("entities must be a non-empty JSON array");
        }
        List<EntityDefinitionDto> dtos;
        try {
            dtos = objectMapper.convertValue(entities, new TypeReference<List<EntityDefinitionDto>>() {});
        } catch (IllegalArgumentException ex) {
            throw new InvalidExampleException("entities do not match the fullstack entity shape: "
                    + ex.getMessage());
        }
        try {
            FullstackRequestValidator.validateAndConvert(new FullstackStarterRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, dtos));
        } catch (WizardArgumentException ex) {
            throw new InvalidExampleException(ex.getMessage());
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(entities);
        } catch (JsonProcessingException ex) {
            throw new InvalidExampleException("entities are not serializable: " + ex.getOriginalMessage());
        }
        if (json.length() > MAX_ENTITIES_CHARS) {
            throw new InvalidExampleException("entities exceed " + MAX_ENTITIES_CHARS + " characters");
        }
        return json;
    }

    private ExampleAdminView view(FullstackExampleEntity e) {
        return new ExampleAdminView(e.getId(), e.getExampleId(), e.getName(), e.getDescription(), e.getIcon(),
                readEntities(e, objectMapper), e.getSortOrder(), e.isEnabled());
    }

    public static JsonNode readEntities(FullstackExampleEntity e, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(e.getEntities());
        } catch (JsonProcessingException ex) {
            // Only reachable if the row was hand-edited: stored entities always came through validateEntities().
            throw new IllegalStateException("Stored entities of example " + e.getExampleId() + " are not valid JSON", ex);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ── Errors (same {error, detail} shape as GlobalExceptionHandler) ──────

    public static final class InvalidExampleException extends RuntimeException {
        InvalidExampleException(String message) { super(message); }
    }

    static final class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }

    static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    @ExceptionHandler(InvalidExampleException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(InvalidExampleException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid example", "detail", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Example id already in use", "detail", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found", "detail", ex.getMessage()));
    }
}
