package com.menora.initializr.admin;

import com.menora.initializr.db.entity.SchemaSourceEntity;
import com.menora.initializr.db.repository.SchemaSourceRepository;
import com.menora.initializr.liveschema.LiveSchemaException;
import com.menora.initializr.liveschema.LiveSchemaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD for the databases everyone may read the schema of ({@code liveschema/}). A source
 * names its password by reference only; {@code passwordAvailable} says whether that reference
 * is set on this server. {@code POST /test} tries a source — saved or not — without saving it.
 *
 * <p>Changes are logged in the catalog history like any catalog row. Sources are not part of the
 * admin export/import: they name this environment's databases and secrets.
 */
@RestController
@RequestMapping("/admin/schema-sources")
public class SchemaSourceAdminController {

    private final SchemaSourceRepository repository;
    private final LiveSchemaService service;

    public SchemaSourceAdminController(SchemaSourceRepository repository, LiveSchemaService service) {
        this.repository = repository;
        this.service = service;
    }

    /** A source as the admin screen shows it. */
    public record SourceView(Long id, String name, String description, String dialect, String host, Integer port,
                             String databaseName, String library, String username, String passwordRef,
                             boolean trustServerCertificate, String defaultSchema, boolean enabled, int sortOrder,
                             boolean passwordAvailable, boolean allowCreateTables) {}

    @GetMapping
    public List<SourceView> list() {
        return repository.findAllByOrderBySortOrderAscNameAsc().stream().map(this::view).toList();
    }

    @PostMapping
    @Transactional
    public SourceView create(@Valid @RequestBody SchemaSourceEntity source) {
        source.setId(null);
        check(source, null);
        return view(repository.save(source));
    }

    @PutMapping("/{id}")
    @Transactional
    public SourceView update(@PathVariable Long id, @Valid @RequestBody SchemaSourceEntity source) {
        if (!repository.existsById(id)) throw new LiveSchemaException(HttpStatus.NOT_FOUND, "Not found", "No source " + id);
        source.setId(id);
        check(source, id);
        return view(repository.save(source));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Connects with the source as given (saved or not) and says what it found. */
    @PostMapping("/test")
    public LiveSchemaService.TestResult test(@RequestBody SchemaSourceEntity source) {
        try {
            checkParts(source);
        } catch (LiveSchemaException e) {
            return new LiveSchemaService.TestResult(false, e.getMessage(), 0);
        }
        return service.test(source);
    }

    /** What a save needs beyond the bean constraints: parts that build a URL, a password reference that is allowed, a free name. */
    private void check(SchemaSourceEntity source, Long id) {
        checkParts(source);
        repository.findByNameIgnoreCase(source.getName().trim())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new LiveSchemaException(HttpStatus.CONFLICT, "Name taken",
                            "There is already a source called " + other.getName());
                });
        source.setName(source.getName().trim());
    }

    /** The parts that build a URL, checked as a connection would check them, and the password reference. */
    private void checkParts(SchemaSourceEntity source) {
        String ref = source.getPasswordRef();
        if (ref != null && !ref.isBlank() && !LiveSchemaService.PASSWORD_REF.matcher(ref.trim()).matches()) {
            throw LiveSchemaException.badRequest("The password reference must be an environment variable "
                    + "SCHEMA_SOURCE_… or a property menora.live-schema.secrets.…");
        }
        if (ref != null) source.setPasswordRef(ref.isBlank() ? null : ref.trim());
        service.specOf(copyWithoutPassword(source));
    }

    private static SchemaSourceEntity copyWithoutPassword(SchemaSourceEntity s) {
        SchemaSourceEntity c = new SchemaSourceEntity();
        c.setName(s.getName());
        c.setDialect(s.getDialect());
        c.setHost(s.getHost());
        c.setPort(s.getPort());
        c.setDatabaseName(s.getDatabaseName());
        c.setLibrary(s.getLibrary());
        c.setUsername(s.getUsername());
        c.setTrustServerCertificate(s.isTrustServerCertificate());
        return c;
    }

    private SourceView view(SchemaSourceEntity s) {
        return new SourceView(s.getId(), s.getName(), s.getDescription(), s.getDialect(), s.getHost(), s.getPort(),
                s.getDatabaseName(), s.getLibrary(), s.getUsername(), s.getPasswordRef(), s.isTrustServerCertificate(),
                s.getDefaultSchema(), s.isEnabled(), s.getSortOrder(), service.passwordAvailable(s),
                s.isAllowCreateTables());
    }

    @ExceptionHandler(LiveSchemaException.class)
    public ResponseEntity<Map<String, Object>> handle(LiveSchemaException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.error());
        body.put("detail", ex.getMessage());
        return ResponseEntity.status(ex.status()).body(body);
    }
}
