package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.entity.ModuleDependencyMappingEntity;
import com.menora.initializr.db.entity.StarterTemplateDepEntity;
import com.menora.initializr.db.entity.EntityTemplateSetDefaultDepEntity;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.EntityTemplateSetDefaultDepRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import com.menora.initializr.db.repository.ModuleDependencyMappingRepository;
import com.menora.initializr.db.repository.ModuleTemplateRepository;
import com.menora.initializr.db.repository.StarterTemplateDepRepository;
import com.menora.initializr.db.repository.StarterTemplateRepository;
import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.FieldDefinition;
import com.menora.initializr.fullstack.SqlToEntityDefinitionConverter;
import com.menora.initializr.sql.SqlDialect;
import com.menora.initializr.sql.SqlEntityGenerator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class ExtensionMetadataController {

    private final DependencyConfigService configService;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final StarterTemplateRepository templateRepo;
    private final StarterTemplateDepRepository templateDepRepo;
    private final ModuleTemplateRepository moduleRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;
    private final DependencyEntryRepository entryRepo;
    private final EntityTemplateSetRepository entityTemplateSetRepo;
    private final EntityTemplateSetDefaultDepRepository entityTemplateSetDefaultDepRepo;
    private final SqlToEntityDefinitionConverter sqlToEntityConverter;

    public ExtensionMetadataController(DependencyConfigService configService,
                                       DependencyCompatibilityRepository compatibilityRepo,
                                       StarterTemplateRepository templateRepo,
                                       StarterTemplateDepRepository templateDepRepo,
                                       ModuleTemplateRepository moduleRepo,
                                       ModuleDependencyMappingRepository moduleMappingRepo,
                                       DependencyEntryRepository entryRepo,
                                       EntityTemplateSetRepository entityTemplateSetRepo,
                                       EntityTemplateSetDefaultDepRepository entityTemplateSetDefaultDepRepo,
                                       SqlToEntityDefinitionConverter sqlToEntityConverter) {
        this.configService = configService;
        this.compatibilityRepo = compatibilityRepo;
        this.templateRepo = templateRepo;
        this.templateDepRepo = templateDepRepo;
        this.moduleRepo = moduleRepo;
        this.moduleMappingRepo = moduleMappingRepo;
        this.entryRepo = entryRepo;
        this.entityTemplateSetRepo = entityTemplateSetRepo;
        this.entityTemplateSetDefaultDepRepo = entityTemplateSetDefaultDepRepo;
        this.sqlToEntityConverter = sqlToEntityConverter;
    }

    @GetMapping("/metadata/extensions")
    public Map<String, List<SubOptionDto>> extensions() {
        return configService.getAllSubOptions().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(so -> new SubOptionDto(so.getOptionId(), so.getLabel(), so.getDescription()))
                                .collect(Collectors.toList())
                ));
    }

    @GetMapping("/metadata/compatibility")
    public List<CompatibilityRuleDto> compatibility() {
        return compatibilityRepo.findAllByOrderBySortOrderAsc().stream()
                .map(r -> new CompatibilityRuleDto(
                        r.getSourceDepId(),
                        r.getTargetDepId(),
                        r.getRelationType().name(),
                        r.getDescription()))
                .toList();
    }

    @GetMapping("/metadata/starter-templates")
    public List<StarterTemplateDto> starterTemplates() {
        return templateRepo.findAllByOrderBySortOrderAsc().stream()
                .map(t -> {
                    List<StarterTemplateDepEntity> deps = templateDepRepo.findAllByTemplateId(t.getId());
                    List<TemplateDepDto> depDtos = deps.stream()
                            .map(d -> new TemplateDepDto(
                                    d.getDepId(),
                                    d.getSubOptions() != null && !d.getSubOptions().isBlank()
                                            ? List.of(d.getSubOptions().split(","))
                                            : List.of()))
                            .toList();
                    return new StarterTemplateDto(
                            t.getTemplateId(), t.getName(), t.getDescription(),
                            t.getIcon(), t.getColor(),
                            t.getBootVersion(), t.getJavaVersion(), t.getPackaging(),
                            depDtos);
                })
                .toList();
    }

    @GetMapping("/metadata/module-templates")
    public List<ModuleTemplateDto> moduleTemplates() {
        List<ModuleDependencyMappingEntity> allMappings = moduleMappingRepo.findAllByOrderBySortOrderAsc();
        Map<String, List<String>> mappingsByModule = allMappings.stream()
                .collect(Collectors.groupingBy(
                        ModuleDependencyMappingEntity::getModuleId,
                        Collectors.mapping(ModuleDependencyMappingEntity::getDependencyId, Collectors.toList())));

        return moduleRepo.findAllByOrderBySortOrderAsc().stream()
                .map(m -> new ModuleTemplateDto(
                        m.getModuleId(), m.getLabel(), m.getDescription(),
                        m.getSuffix(), m.getPackaging(), m.isHasMainClass(),
                        mappingsByModule.getOrDefault(m.getModuleId(), List.of())))
                .toList();
    }

    /**
     * Dep IDs for which the OpenAPI → Controller/DTO wizard is surfaced, filtered
     * to those currently in the catalog. The wizard is meaningful for web-layer
     * starters only.
     */
    @GetMapping("/metadata/openapi-capable-deps")
    public List<String> openApiCapableDeps() {
        Set<String> candidates = Set.of("web", "webflux");
        return entryRepo.findByDepIdIn(candidates).stream()
                .map(DependencyEntryEntity::getDepId)
                .sorted()
                .toList();
    }

    /**
     * Dep IDs for which the SOAP → Spring-WS wizard is surfaced, filtered to
     * those currently in the catalog. Wizard surfaces on the {@code web-services}
     * starter; empty list → UI hides the trigger button.
     */
    @GetMapping("/metadata/soap-capable-deps")
    public List<String> soapCapableDeps() {
        Set<String> candidates = Set.of("web-services");
        return entryRepo.findByDepIdIn(candidates).stream()
                .map(DependencyEntryEntity::getDepId)
                .sorted()
                .toList();
    }

    /**
     * Dep-id → dialect-enum-name for drivers the SQL wizard can handle AND that
     * currently exist in the dependency catalog. Empty map → UI hides the wizard.
     */
    @GetMapping("/metadata/sql-dialects")
    public Map<String, String> sqlDialects() {
        Set<String> candidateDepIds = Arrays.stream(SqlDialect.values())
                .map(SqlDialect::depId)
                .collect(Collectors.toSet());
        Set<String> present = entryRepo.findByDepIdIn(candidateDepIds).stream()
                .map(DependencyEntryEntity::getDepId)
                .collect(Collectors.toSet());
        Map<String, String> result = new LinkedHashMap<>();
        for (SqlDialect d : SqlDialect.values()) {
            if (present.contains(d.depId())) {
                result.put(d.depId(), d.name());
            }
        }
        return result;
    }

    /** Enabled fullstack CRUD template sets surfaced to the end-user wizard,
     *  each carrying its default dep IDs so the UI can pre-check them. */
    @GetMapping("/metadata/entity-template-sets")
    public List<EntityTemplateSetDto> entityTemplateSets() {
        return entityTemplateSetRepo.findAllByOrderBySortOrderAsc().stream()
                .filter(EntityTemplateSetEntity::isEnabled)
                .map(s -> {
                    List<String> defaults = entityTemplateSetDefaultDepRepo
                            .findBySetIdOrderBySortOrderAsc(s.getId()).stream()
                            .map(EntityTemplateSetDefaultDepEntity::getDepId).toList();
                    return new EntityTemplateSetDto(
                            s.getSetKey(), s.getName(), s.getDescription(), s.getKind().name(),
                            defaults);
                })
                .toList();
    }

    public record EntityTemplateSetDto(String setKey, String name, String description, String kind,
                                       List<String> defaultDeps) {}

    /**
     * Parses pasted CREATE TABLE DDL into the wire-format entity list that
     * {@code POST /starter-fullstack.zip} accepts. Lets the UI pre-fill the
     * EntitiesEditor from an existing schema instead of clicking field-by-field.
     *
     * <p>Body: {@code {sql, dialect?}} where dialect is one of {@link SqlDialect}
     * enum names (e.g. {@code POSTGRESQL}, {@code H2}). Defaults to {@code H2}.
     */
    @PostMapping("/metadata/fullstack/import-ddl")
    public ImportDdlResponse importDdl(@RequestBody ImportDdlRequest req) {
        SqlDialect dialect = parseDialect(req == null ? null : req.dialect());
        String sql = req == null ? null : req.sql();
        List<EntityDefinition> defs = sqlToEntityConverter.convert(sql, dialect);
        List<EntityWire> wire = new ArrayList<>(defs.size());
        for (EntityDefinition d : defs) {
            List<FieldWire> fields = new ArrayList<>(d.fields().size());
            for (FieldDefinition f : d.fields()) {
                fields.add(new FieldWire(
                        f.name(), f.type().name(),
                        f.primaryKey(), f.generated(), f.required(), f.unique(),
                        f.length(), f.enumValues()));
            }
            wire.add(new EntityWire(d.name(), d.tableName(), fields));
        }
        return new ImportDdlResponse(wire);
    }

    @ExceptionHandler(SqlEntityGenerator.SqlParseException.class)
    public ResponseEntity<Map<String, Object>> handleSqlParse(SqlEntityGenerator.SqlParseException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid SQL");
        body.put("detail", ex.getMessage());
        if (ex.statementIndex() != null) body.put("statementIndex", ex.statementIndex());
        if (ex.statementSnippet() != null) body.put("snippet", ex.statementSnippet());
        return ResponseEntity.badRequest().body(body);
    }

    private static SqlDialect parseDialect(String raw) {
        if (raw == null || raw.isBlank()) return SqlDialect.H2;
        String key = raw.trim().toUpperCase(Locale.ROOT);
        SqlDialect byDepId = SqlDialect.forDepId(raw.trim().toLowerCase(Locale.ROOT));
        if (byDepId != null) return byDepId;
        try {
            return SqlDialect.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return SqlDialect.H2;
        }
    }

    public record ImportDdlRequest(String sql, String dialect) {}
    public record ImportDdlResponse(List<EntityWire> entities) {}
    public record EntityWire(String name, String tableName, List<FieldWire> fields) {}
    public record FieldWire(
            String name, String type,
            boolean primaryKey, boolean generated, boolean required, boolean unique,
            Integer length, List<String> enumValues) {}

    public record SubOptionDto(String id, String label, String description) {}
    public record CompatibilityRuleDto(String sourceDepId, String targetDepId, String relationType, String description) {}
    public record TemplateDepDto(String depId, List<String> subOptions) {}
    public record StarterTemplateDto(
            String id, String name, String description,
            String icon, String color,
            String bootVersion, String javaVersion, String packaging,
            List<TemplateDepDto> dependencies) {}
    public record ModuleTemplateDto(
            String moduleId, String label, String description,
            String suffix, String packaging, boolean hasMainClass,
            List<String> dependencyIds) {}
}
