package com.menora.initializr.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.db.entity.FullstackExampleEntity;
import com.menora.initializr.db.repository.FullstackExampleRepository;
import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.FullstackPageValidator;
import com.menora.initializr.fullstack.FullstackRequestValidator;
import com.menora.initializr.fullstack.FullstackStarterRequest;
import com.menora.initializr.fullstack.FullstackStarterRequest.EntityDefinitionDto;
import com.menora.initializr.fullstack.FullstackStarterRequest.PageDefinitionDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                                   JsonNode entities, JsonNode pages, JsonNode settings,
                                   int sortOrder, boolean enabled) {
    }

    public record ExampleRequest(String exampleId, String name, String description, String icon,
                                 JsonNode entities, JsonNode pages, JsonNode settings,
                                 Integer sortOrder, Boolean enabled) {
    }

    /** Keys an example's {@code settings} object may carry — the editor state it applies on load. */
    static final Set<String> SETTINGS_STRING_KEYS = Set.of(
            "dashboardTitle", "dashboardOverview", "locale", "backendTemplateSet", "frontendTemplateSet",
            "colorPalette");
    static final Pattern SCAFFOLD_OPT = Pattern.compile("^[a-zA-Z]{1,40}$");

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
        e.setPages(validatePages(body.entities(), body.pages(), body.settings(), objectMapper));
        e.setSettings(validateSettings(body.settings(), objectMapper));
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

    /**
     * Checks {@code pages} is a page layout the generator accepts for {@code entities} (which must
     * already have passed {@link #validateEntities}) and returns it as compact JSON text, or null
     * when absent/empty (the classic layout). {@code settings} supplies the example's scaffold opts
     * (a list page may show the audit columns only with {@code audit} on).
     */
    public static String validatePages(JsonNode entities, JsonNode pages, JsonNode settings, ObjectMapper objectMapper) {
        if (pages == null || pages.isNull() || (pages.isArray() && pages.isEmpty())) return null;
        if (!pages.isArray()) throw new InvalidExampleException("pages must be a JSON array");
        List<PageDefinitionDto> dtos;
        try {
            dtos = objectMapper.convertValue(pages, new TypeReference<List<PageDefinitionDto>>() {});
        } catch (IllegalArgumentException ex) {
            throw new InvalidExampleException("pages do not match the fullstack page shape: " + ex.getMessage());
        }
        try {
            List<EntityDefinition> converted = FullstackRequestValidator.validateAndConvert(new FullstackStarterRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null,
                    objectMapper.convertValue(entities, new TypeReference<List<EntityDefinitionDto>>() {})));
            FullstackPageValidator.validateAndConvert(dtos, converted, scaffoldOptsOf(settings));
        } catch (WizardArgumentException ex) {
            throw new InvalidExampleException(ex.getMessage());
        }
        return toJson(pages, "pages", objectMapper);
    }

    /** The {@code scaffold} names of an example's settings (an array of strings; anything else: none). */
    private static Set<String> scaffoldOptsOf(JsonNode settings) {
        if (settings == null || !settings.isObject() || !settings.path("scaffold").isArray()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode n : settings.get("scaffold")) {
            if (n.isTextual()) out.add(n.asText());
        }
        return out;
    }

    /**
     * Checks {@code settings} is an object of known keys — strings, plus {@code scaffold} as an
     * array of option names — and returns it as compact JSON text, or null when absent/empty.
     * Template-set and palette keys are not resolved here: the editor ignores a key it can't match.
     */
    public static String validateSettings(JsonNode settings, ObjectMapper objectMapper) {
        if (settings == null || settings.isNull() || (settings.isObject() && settings.isEmpty())) return null;
        if (!settings.isObject()) throw new InvalidExampleException("settings must be a JSON object");
        for (Iterator<Map.Entry<String, JsonNode>> it = settings.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            String key = en.getKey();
            JsonNode v = en.getValue();
            if (SETTINGS_STRING_KEYS.contains(key)) {
                if (!v.isTextual() || v.asText().length() > 500) {
                    throw new InvalidExampleException("settings." + key + " must be a string of at most 500 characters");
                }
                if (key.equals("locale") && !Set.of("en", "he").contains(v.asText())) {
                    throw new InvalidExampleException("settings.locale must be 'en' or 'he'");
                }
            } else if (key.equals("scaffold")) {
                if (!v.isArray() || v.size() > 20) {
                    throw new InvalidExampleException("settings.scaffold must be an array of at most 20 option names");
                }
                for (JsonNode opt : v) {
                    if (!opt.isTextual() || !SCAFFOLD_OPT.matcher(opt.asText()).matches()) {
                        throw new InvalidExampleException("settings.scaffold holds an invalid option name: " + opt);
                    }
                }
            } else {
                throw new InvalidExampleException("settings." + key + " is not a known setting (expected one of "
                        + new java.util.TreeSet<>(SETTINGS_STRING_KEYS) + " or scaffold)");
            }
        }
        return toJson(settings, "settings", objectMapper);
    }

    private static String toJson(JsonNode node, String what, ObjectMapper objectMapper) {
        String json;
        try {
            json = objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new InvalidExampleException(what + " are not serializable: " + ex.getOriginalMessage());
        }
        if (json.length() > MAX_ENTITIES_CHARS) {
            throw new InvalidExampleException(what + " exceed " + MAX_ENTITIES_CHARS + " characters");
        }
        return json;
    }

    private ExampleAdminView view(FullstackExampleEntity e) {
        return new ExampleAdminView(e.getId(), e.getExampleId(), e.getName(), e.getDescription(), e.getIcon(),
                readEntities(e, objectMapper), readJson(e.getPages(), e, objectMapper),
                readJson(e.getSettings(), e, objectMapper), e.getSortOrder(), e.isEnabled());
    }

    /** Parses an optional stored JSON column ({@code pages}/{@code settings}); null stays null. */
    public static JsonNode readJson(String json, FullstackExampleEntity e, ObjectMapper objectMapper) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored JSON of example " + e.getExampleId() + " is not valid", ex);
        }
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
