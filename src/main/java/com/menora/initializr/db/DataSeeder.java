package com.menora.initializr.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.*;
import com.menora.initializr.db.repository.*;
import com.menora.initializr.db.seed.CatalogManifests.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Seeds the database from existing classpath resources on first startup.
 * Runs only when all tables are empty.
 *
 * <p>Implements {@link SmartInitializingSingleton} (rather than {@code CommandLineRunner})
 * so the seed completes inside {@code finishBeanFactoryInitialization} — before
 * {@code ServletWebServerApplicationContext.finishRefresh()} opens the Tomcat connector
 * and before {@code /actuator/health} starts reporting UP. Otherwise a request that
 * arrives during the gap would see {@code selectedDepIds=[]} and the metadata cache
 * would populate empty until someone called {@code POST /admin/refresh}.
 */
@Component
public class DataSeeder implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final DependencyGroupRepository groupRepo;
    private final DependencyEntryRepository entryRepo;
    private final FileContributionRepository fileContribRepo;
    private final BuildCustomizationRepository buildCustomRepo;
    private final DependencySubOptionRepository subOptionRepo;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final StarterTemplateRepository templateRepo;
    private final StarterTemplateDepRepository templateDepRepo;
    private final ModuleTemplateRepository moduleRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;
    private final EntityTemplateSetRepository entityTemplateSetRepo;
    private final EntityTemplateFileRepository entityTemplateFileRepo;
    private final EntityTemplateSetDefaultDepRepository entityTemplateSetDefaultDepRepo;
    private final ColorPaletteRepository colorPaletteRepo;
    private final VersionDefinitionRepository versionRepo;

    public DataSeeder(DependencyGroupRepository groupRepo,
                      DependencyEntryRepository entryRepo,
                      FileContributionRepository fileContribRepo,
                      BuildCustomizationRepository buildCustomRepo,
                      DependencySubOptionRepository subOptionRepo,
                      DependencyCompatibilityRepository compatibilityRepo,
                      StarterTemplateRepository templateRepo,
                      StarterTemplateDepRepository templateDepRepo,
                      ModuleTemplateRepository moduleRepo,
                      ModuleDependencyMappingRepository moduleMappingRepo,
                      EntityTemplateSetRepository entityTemplateSetRepo,
                      EntityTemplateFileRepository entityTemplateFileRepo,
                      EntityTemplateSetDefaultDepRepository entityTemplateSetDefaultDepRepo,
                      ColorPaletteRepository colorPaletteRepo,
                      VersionDefinitionRepository versionRepo) {
        this.groupRepo = groupRepo;
        this.entryRepo = entryRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
        this.compatibilityRepo = compatibilityRepo;
        this.templateRepo = templateRepo;
        this.templateDepRepo = templateDepRepo;
        this.moduleRepo = moduleRepo;
        this.moduleMappingRepo = moduleMappingRepo;
        this.entityTemplateSetRepo = entityTemplateSetRepo;
        this.entityTemplateFileRepo = entityTemplateFileRepo;
        this.entityTemplateSetDefaultDepRepo = entityTemplateSetDefaultDepRepo;
        this.colorPaletteRepo = colorPaletteRepo;
        this.versionRepo = versionRepo;
    }

    @Override
    @Transactional
    public void afterSingletonsInstantiated() {
        try {
            // Run table-scoped seeds before the main guard so new tables added in
            // later releases (e.g. color_palette, entity_template_set) get populated
            // on existing installations without forcing a full re-seed.
            seedColorPalettes();
            seedEntityTemplateSetsIfMissing();
            seedVersionsIfMissing();

            if (groupRepo.count() > 0) {
                log.info("Database already seeded — skipping main DataSeeder");
                normalizeLegacyBlankStrings();
                return;
            }
            log.info("Seeding database from classpath resources...");
            loadDependencyCatalog("catalog/dependencies.json", ProjectKind.BACKEND);
            loadFileContributions("catalog/file-contributions.json", ProjectKind.BACKEND);
            loadBuildCustomizations("catalog/build-customizations.json", ProjectKind.BACKEND);
            loadSubOptions("catalog/sub-options.json", ProjectKind.BACKEND);
            loadCompatibilityRules("catalog/compatibility.json", ProjectKind.BACKEND);
            seedStarterTemplates();
            seedModuleTemplates();
            loadDependencyCatalog("catalog/frontend/dependencies.json", ProjectKind.FRONTEND);
            loadFileContributions("catalog/frontend/file-contributions.json", ProjectKind.FRONTEND);
            loadBuildCustomizations("catalog/frontend/build-customizations.json", ProjectKind.FRONTEND);
            loadSubOptions("catalog/frontend/sub-options.json", ProjectKind.FRONTEND);
            loadCompatibilityRules("catalog/frontend/compatibility.json", ProjectKind.FRONTEND);
            log.info("Seeded frontend catalog (FRONTEND kind)");
            seedFrontendStarterTemplates();
            log.info("Database seeding complete");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to seed database from classpath", e);
        }
    }

    /**
     * Seeds the fullstack CRUD template sets from {@code templates/fullstack/&lt;set&gt;/manifest.json}.
     * Independent of the main seeder's all-or-nothing guard so the new tables get populated
     * on databases that already had the original catalog.
     */
    private void seedEntityTemplateSetsIfMissing() throws IOException {
        if (entityTemplateSetRepo.count() > 0) {
            log.debug("Entity template sets already present — skipping");
            return;
        }
        log.info("Seeding entity template sets from classpath manifests");
        seedEntityTemplateSet("templates/fullstack/spring-jpa-crud/");
        seedEntityTemplateSet("templates/fullstack/spring-jpa-crud-lombok/");
        seedEntityTemplateSet("templates/fullstack/react-tailwind-crud/");
    }

    private void seedEntityTemplateSet(String baseDir) throws IOException {
        String manifestJson = readClasspath(baseDir + "manifest.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(manifestJson);

        EntityTemplateSetEntity set = new EntityTemplateSetEntity();
        set.setSetKey(root.get("setKey").asText());
        set.setName(root.get("name").asText());
        if (root.hasNonNull("description")) set.setDescription(root.get("description").asText());
        set.setKind(EntityTemplateSetEntity.Kind.valueOf(root.get("kind").asText()));
        set.setEnabled(true);
        set.setSortOrder(root.hasNonNull("sortOrder") ? root.get("sortOrder").asInt() : 0);
        if (root.hasNonNull("designSystem")) {
            set.setDesignSystem(EntityTemplateSetEntity.DesignSystem.valueOf(root.get("designSystem").asText()));
        }
        if (root.hasNonNull("bootVersion")) set.setBootVersion(root.get("bootVersion").asText());
        if (root.hasNonNull("javaVersion")) set.setJavaVersion(root.get("javaVersion").asText());
        set = entityTemplateSetRepo.save(set);

        JsonNode files = root.get("files");
        if (files == null || !files.isArray()) {
            log.warn("Template set '{}' has no 'files' array — set seeded with no contents", set.getSetKey());
            return;
        }
        for (JsonNode f : files) {
            EntityTemplateFileEntity row = new EntityTemplateFileEntity();
            row.setSetId(set.getId());
            row.setPathTemplate(f.get("path").asText());
            JsonNode sourceNode = f.get("source");
            if (sourceNode == null || sourceNode.asText().isBlank()) {
                throw new IllegalStateException("Template set '" + set.getSetKey()
                        + "' has a file entry with a missing/blank 'source' (path="
                        + f.path("path").asText() + ")");
            }
            // A file may borrow its content from another set's directory via "sourceSet",
            // so a variant set (e.g. spring-jpa-crud-lombok) only needs to author the files
            // that actually differ and reuse the rest.
            String sourceDir = f.hasNonNull("sourceSet")
                    ? "templates/fullstack/" + f.get("sourceSet").asText() + "/"
                    : baseDir;
            String resourcePath = sourceDir + sourceNode.asText();
            try {
                row.setContent(readClasspath(resourcePath));
            } catch (IOException e) {
                throw new IllegalStateException("Template set '" + set.getSetKey()
                        + "' references missing content file '" + sourceNode.asText()
                        + "' (expected at " + resourcePath + ")", e);
            }
            row.setSubstitutionType(FileContributionEntity.SubstitutionType.valueOf(
                    f.get("substitutionType").asText()));
            row.setFileType(EntityTemplateFileEntity.FileType.valueOf(f.get("fileType").asText()));
            row.setPerEntity(f.hasNonNull("perEntity") && f.get("perEntity").asBoolean());
            row.setSortOrder(f.hasNonNull("sortOrder") ? f.get("sortOrder").asInt() : 0);
            row.setGatedBy(f.hasNonNull("gatedBy") ? f.get("gatedBy").asText() : null);
            entityTemplateFileRepo.save(row);
        }

        JsonNode defaultDeps = root.get("defaultDeps");
        int defaultDepCount = 0;
        if (defaultDeps != null && defaultDeps.isArray()) {
            int order = 0;
            for (JsonNode d : defaultDeps) {
                String depId = d.asText();
                if (depId == null || depId.isBlank()) continue;
                EntityTemplateSetDefaultDepEntity dd = new EntityTemplateSetDefaultDepEntity();
                dd.setSetId(set.getId());
                dd.setDepId(depId.trim());
                dd.setSortOrder(order++);
                entityTemplateSetDefaultDepRepo.save(dd);
                defaultDepCount++;
            }
        }
        log.info("Seeded entity template set '{}' with {} files and {} default deps",
                set.getSetKey(), files.size(), defaultDepCount);
    }

    /**
     * Legacy rows inserted through the admin UI before the entity setters were
     * taught to coerce blank strings to null. Re-saving each row runs the now-coercing
     * setters and flushes NULLs to columns that used to hold "".
     */
    private void normalizeLegacyBlankStrings() {
        int fcFixed = 0;
        for (FileContributionEntity f : fileContribRepo.findAll()) {
            boolean bad = "".equals(f.getSubOptionId()) || "".equals(f.getJavaVersion());
            if (bad) {
                f.setSubOptionId(f.getSubOptionId());
                f.setJavaVersion(f.getJavaVersion());
                fileContribRepo.save(f);
                fcFixed++;
            }
        }
        int entryFixed = 0;
        for (DependencyEntryEntity e : entryRepo.findAll()) {
            boolean bad = "".equals(e.getMavenGroupId()) || "".equals(e.getMavenArtifactId())
                    || "".equals(e.getVersion()) || "".equals(e.getScope())
                    || "".equals(e.getRepository()) || "".equals(e.getCompatibilityRange())
                    || "".equals(e.getDescription());
            if (bad) {
                e.setMavenGroupId(e.getMavenGroupId());
                e.setMavenArtifactId(e.getMavenArtifactId());
                e.setVersion(e.getVersion());
                e.setScope(e.getScope());
                e.setRepository(e.getRepository());
                e.setCompatibilityRange(e.getCompatibilityRange());
                e.setDescription(e.getDescription());
                entryRepo.save(e);
                entryFixed++;
            }
        }
        if (fcFixed + entryFixed > 0) {
            log.info("Normalized legacy blank strings: {} file contributions, {} dependency entries",
                    fcFixed, entryFixed);
        }
    }

    // ── Manifest-driven backend catalog loaders ──────────────────────────────
    //
    // The backend dependency catalog, file contributions, build customizations,
    // sub-options, and compatibility rules live in src/main/resources/catalog/*.json.
    // These loaders map the manifests to entities. Content files (templates,
    // static-configs) stay on the classpath; file-contribution manifests reference
    // them by path via contentResource, resolved here through readClasspath.

    private <T> T readManifest(String path, Class<T> type) throws IOException {
        return new ObjectMapper().readValue(readClasspath(path), type);
    }

    private <T> List<T> readManifestList(String path, Class<T> elementType) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(readClasspath(path),
                mapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    private void loadDependencyCatalog(String path, ProjectKind kind) throws IOException {
        DependenciesManifest manifest = readManifest(path, DependenciesManifest.class);
        for (GroupDef gd : manifest.groups()) {
            DependencyGroupEntity g = new DependencyGroupEntity();
            g.setName(gd.name());
            g.setSortOrder(gd.sortOrder());
            g.setProjectKind(kind);
            g = groupRepo.save(g);
            for (EntryDef ed : gd.entries()) {
                DependencyEntryEntity e = new DependencyEntryEntity();
                e.setGroup(g);
                e.setDepId(ed.depId());
                e.setName(ed.name());
                e.setDescription(ed.description());
                e.setMavenGroupId(ed.mavenGroupId());
                e.setMavenArtifactId(ed.mavenArtifactId());
                e.setVersion(ed.version());
                e.setScope(ed.scope());
                e.setRepository(ed.repository());
                e.setSortOrder(ed.sortOrder());
                e.setCompatibilityRange(ed.compatibilityRange());
                if (ed.starter() != null) e.setStarter(ed.starter());
                e.setProjectKind(kind);
                entryRepo.save(e);
            }
        }
    }

    private void loadFileContributions(String path, ProjectKind kind) throws IOException {
        for (FileContribDef d : readManifestList(path, FileContribDef.class)) {
            FileContributionEntity e = new FileContributionEntity();
            e.setDependencyId(d.depId());
            e.setFileType(FileContributionEntity.FileType.valueOf(d.fileType()));
            e.setContent(d.contentResource() != null ? readClasspath(d.contentResource()) : d.content());
            e.setTargetPath(d.targetPath());
            e.setSubstitutionType(d.substitutionType() != null
                    ? FileContributionEntity.SubstitutionType.valueOf(d.substitutionType()) : null);
            e.setJavaVersion(d.javaVersion());
            e.setSubOptionId(d.subOptionId());
            e.setSortOrder(d.sortOrder());
            e.setProjectKind(kind);
            fileContribRepo.save(e);
        }
    }

    private void loadBuildCustomizations(String path, ProjectKind kind) throws IOException {
        for (BuildCustomDef d : readManifestList(path, BuildCustomDef.class)) {
            BuildCustomizationEntity e = new BuildCustomizationEntity();
            e.setDependencyId(d.depId());
            e.setCustomizationType(BuildCustomizationEntity.CustomizationType.valueOf(d.type()));
            e.setMavenGroupId(d.mavenGroupId());
            e.setMavenArtifactId(d.mavenArtifactId());
            e.setVersion(d.version());
            e.setExcludeFromGroupId(d.excludeFromGroupId());
            e.setExcludeFromArtifactId(d.excludeFromArtifactId());
            e.setRepoId(d.repoId());
            e.setRepoName(d.repoName());
            e.setRepoUrl(d.repoUrl());
            if (d.snapshotsEnabled() != null) e.setSnapshotsEnabled(d.snapshotsEnabled());
            e.setScope(d.scope());
            e.setSubOptionId(d.subOptionId());
            e.setSortOrder(d.sortOrder());
            e.setProjectKind(kind);
            buildCustomRepo.save(e);
        }
    }

    private void loadSubOptions(String path, ProjectKind kind) throws IOException {
        for (SubOptionDef d : readManifestList(path, SubOptionDef.class)) {
            DependencySubOptionEntity e = new DependencySubOptionEntity();
            e.setDependencyId(d.depId());
            e.setOptionId(d.optionId());
            e.setLabel(d.label());
            e.setDescription(d.description());
            e.setSortOrder(d.sortOrder());
            e.setProjectKind(kind);
            subOptionRepo.save(e);
        }
    }

    private void loadCompatibilityRules(String path, ProjectKind kind) throws IOException {
        for (CompatibilityDef d : readManifestList(path, CompatibilityDef.class)) {
            DependencyCompatibilityEntity e = new DependencyCompatibilityEntity();
            e.setSourceDepId(d.sourceDepId());
            e.setTargetDepId(d.targetDepId());
            e.setRelationType(DependencyCompatibilityEntity.RelationType.valueOf(d.relationType()));
            e.setDescription(d.description());
            e.setSortOrder(d.sortOrder());
            e.setProjectKind(kind);
            compatibilityRepo.save(e);
        }
    }

    // ── Starter templates ───────────────────────────────────────────────────

    private void seedStarterTemplates() {
        StarterTemplateEntity restApi = starterTemplate(
                "rest-api", "REST API Service",
                "Spring Web + JPA + PostgreSQL + Actuator",
                "api", "#4CAF50", null, null, null, 0);
        templateDep(restApi, "web", null);
        templateDep(restApi, "data-jpa", null);
        templateDep(restApi, "postgresql", "pg-primary");
        templateDep(restApi, "actuator", null);
        templateDep(restApi, "logging", null);

        StarterTemplateEntity eventDriven = starterTemplate(
                "event-driven", "Event-Driven Service",
                "Kafka + JPA + Consumer/Producer examples",
                "bolt", "#FF9800", null, null, null, 1);
        templateDep(eventDriven, "kafka", "consumer-example,producer-example");
        templateDep(eventDriven, "data-jpa", null);
        templateDep(eventDriven, "postgresql", "pg-primary");
        templateDep(eventDriven, "actuator", null);
        templateDep(eventDriven, "logging", null);

        StarterTemplateEntity microservice = starterTemplate(
                "microservice", "Microservice (Full Stack)",
                "Web + Kafka + JPA + Security + Observability",
                "cloud", "#2196F3", null, null, null, 2);
        templateDep(microservice, "web", null);
        templateDep(microservice, "kafka", null);
        templateDep(microservice, "data-jpa", null);
        templateDep(microservice, "postgresql", "pg-primary");
        templateDep(microservice, "security", null);
        templateDep(microservice, "actuator", null);
        templateDep(microservice, "prometheus", null);
        templateDep(microservice, "logging", null);
    }

    private StarterTemplateEntity starterTemplate(String templateId, String name, String description,
                                                   String icon, String color,
                                                   String bootVersion, String javaVersion, String packaging,
                                                   int sortOrder) {
        StarterTemplateEntity e = new StarterTemplateEntity();
        e.setTemplateId(templateId);
        e.setName(name);
        e.setDescription(description);
        e.setIcon(icon);
        e.setColor(color);
        e.setBootVersion(bootVersion);
        e.setJavaVersion(javaVersion);
        e.setPackaging(packaging);
        e.setSortOrder(sortOrder);
        return templateRepo.save(e);
    }

    private void templateDep(StarterTemplateEntity template, String depId, String subOptions) {
        StarterTemplateDepEntity e = new StarterTemplateDepEntity();
        e.setTemplate(template);
        e.setDepId(depId);
        e.setSubOptions(subOptions);
        templateDepRepo.save(e);
    }

    private void seedFrontendStarterTemplates() {
        StarterTemplateEntity dashboard = feStarterTemplate(
                "fe-dashboard", "Admin Dashboard",
                "Tailwind + shadcn/ui with router, Zustand, TanStack Query and a sample form",
                "dashboard", "#7C3AED", 0);
        templateDep(dashboard, "style-tailwind",        "dark-mode");
        templateDep(dashboard, "design-shadcn",         null);
        templateDep(dashboard, "router-react-router",   "sample-routes,lazy-routes");
        templateDep(dashboard, "state-zustand",         "sample-store,devtools");
        templateDep(dashboard, "data-tanstack-query",   "sample-query,axios-base,devtools");
        templateDep(dashboard, "form-react-hook-form",  "rhf-zod-resolver");

        StarterTemplateEntity marketing = feStarterTemplate(
                "fe-marketing", "Marketing Site",
                "Lightweight Tailwind + shadcn site with routing and Framer Motion",
                "campaign", "#EC4899", 1);
        templateDep(marketing, "style-tailwind",        null);
        templateDep(marketing, "design-shadcn",         null);
        templateDep(marketing, "router-react-router",   "sample-routes");
        templateDep(marketing, "anim-framer-motion",    null);

        StarterTemplateEntity saas = feStarterTemplate(
                "fe-saas-app", "SaaS App",
                "Redux Toolkit + TanStack Query + RHF/Zod with MSAL auth and Vitest",
                "apps", "#10B981", 2);
        templateDep(saas, "style-tailwind",         null);
        templateDep(saas, "design-shadcn",          null);
        templateDep(saas, "router-react-router",    "sample-routes,error-boundary");
        templateDep(saas, "state-redux-toolkit",    "sample-store");
        templateDep(saas, "data-tanstack-query",    "axios-base,sample-query");
        templateDep(saas, "form-react-hook-form",   "rhf-zod-resolver,sample-form");
        templateDep(saas, "test-vitest-rtl",        "sample-tests");
        templateDep(saas, "auth-msal",              null);
    }

    private StarterTemplateEntity feStarterTemplate(String templateId, String name, String description,
                                                     String icon, String color, int sortOrder) {
        StarterTemplateEntity e = new StarterTemplateEntity();
        e.setTemplateId(templateId);
        e.setName(name);
        e.setDescription(description);
        e.setIcon(icon);
        e.setColor(color);
        e.setSortOrder(sortOrder);
        e.setProjectKind(ProjectKind.FRONTEND);
        return templateRepo.save(e);
    }

    // ── Module templates ──────────────────────────────────────────────────

    private void seedModuleTemplates() {
        // API module — gets the main class and web-facing dependencies
        moduleTemplate("api", "API Module",
                "REST controllers, web layer, and application entry point",
                "-api", "jar", true, 0);

        // Core module — shared business logic, no web or DB
        moduleTemplate("core", "Core Module",
                "Shared domain models, services, and utilities",
                "-core", "jar", false, 1);

        // Persistence module — JPA entities and repositories
        moduleTemplate("persistence", "Persistence Module",
                "JPA entities, repositories, and database configuration",
                "-persistence", "jar", false, 2);

        // Module-to-dependency mappings
        moduleDepMapping("api", "web", 0);
        moduleDepMapping("api", "security", 1);
        moduleDepMapping("api", "actuator", 2);

        moduleDepMapping("persistence", "data-jpa", 0);
        moduleDepMapping("persistence", "postgresql", 1);

        moduleDepMapping("core", "logging", 0);
    }

    private void moduleTemplate(String moduleId, String label, String description,
                                 String suffix, String packaging, boolean hasMainClass, int sortOrder) {
        ModuleTemplateEntity e = new ModuleTemplateEntity();
        e.setModuleId(moduleId);
        e.setLabel(label);
        e.setDescription(description);
        e.setSuffix(suffix);
        e.setPackaging(packaging);
        e.setHasMainClass(hasMainClass);
        e.setSortOrder(sortOrder);
        moduleRepo.save(e);
    }

    private void moduleDepMapping(String moduleId, String depId, int sortOrder) {
        ModuleDependencyMappingEntity e = new ModuleDependencyMappingEntity();
        e.setModuleId(moduleId);
        e.setDependencyId(depId);
        e.setSortOrder(sortOrder);
        moduleMappingRepo.save(e);
    }

    private String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("Classpath resource not found: " + path);
        }
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    // ── Frontend (React + TS + Vite + FSD) catalog ──────────────────────────────
    //
    // Mirrors the backend seed but produces FRONTEND-kind rows. The
    // FrontendProjectGenerator queries them through DependencyConfigService
    // filtered by ProjectKind.FRONTEND.

    private void seedColorPalettes() {
        // Idempotent — each helper call is a no-op when its paletteId already
        // exists, so existing installations pick up newly-added rows on next
        // startup without touching admin-edited ones.
        colorPalette("menora-default", "Menora Default",
                "Menora brand navy with gold accent", "#0a2b6b", "#2d3344", "#ffc700", "#d32f2f", true, 0);
        colorPalette("forest", "Forest",
                "Earthy greens and warm accents", "#2e7d32", "#8d6e63", "#ff8f00", "#c62828", false, 1);
        colorPalette("slate", "Slate",
                "Neutral cool grays for understated UIs", "#475569", "#0ea5e9", null, "#dc2626", false, 2);

        // Brand-inspired palettes (colors evoke each brand, not pixel-exact)
        colorPalette("stripe-purple", "Stripe Purple",
                "Saturated indigo with magenta accent", "#635bff", "#00d4ff", "#ff5996", "#df1b41", false, 3);
        colorPalette("vercel-mono", "Vercel Mono",
                "High-contrast monochrome with a single highlight", "#000000", "#666666", "#0070f3", "#ee0000", false, 4);
        colorPalette("linear-violet", "Linear",
                "Cool violet/blue product palette", "#5e6ad2", "#26a69a", "#f2c94c", "#eb5757", false, 5);
        colorPalette("github-blue", "GitHub",
                "Classic GitHub blue with green CTA", "#0969da", "#1f883d", "#bf3989", "#cf222e", false, 6);
        colorPalette("notion-warm", "Notion",
                "Warm neutrals with terracotta accent", "#2f3437", "#787774", "#d9730d", "#e03e3e", false, 7);
        colorPalette("tailwind-sky", "Tailwind Sky",
                "Tailwind sky/rose default-ish pairing", "#0ea5e9", "#f43f5e", "#a855f7", "#dc2626", false, 8);
    }

    private void colorPalette(String paletteId, String name, String description,
                              String primary, String secondary, String accent, String error,
                              boolean isDefault, int sortOrder) {
        if (colorPaletteRepo.findByPaletteId(paletteId).isPresent()) return;
        ColorPaletteEntity p = new ColorPaletteEntity();
        p.setPaletteId(paletteId);
        p.setName(name);
        p.setDescription(description);
        p.setPrimary(primary);
        p.setSecondary(secondary);
        p.setAccent(accent);
        p.setError(error);
        p.setDefault(isDefault);
        p.setSortOrder(sortOrder);
        colorPaletteRepo.save(p);
    }

    /**
     * Seeds the Java / Boot / React / Node / package-manager version lists into
     * {@code version_definition}. Idempotent per row — keeps admin-edited
     * versions intact while picking up newly-shipped defaults on next startup.
     * Values mirror the legacy {@code initializr.*-versions} and
     * {@code frontend.*-versions} YAML blocks so behavior is unchanged after
     * the YAML lines are removed.
     */
    private void seedVersionsIfMissing() {
        version(VersionKind.JAVA, "21", "21", true,  0, null, null);
        version(VersionKind.JAVA, "17", "17", false, 1, null, null);

        version(VersionKind.BOOT, "3.2.1", "3.2.1", true, 0, null, null);

        version(VersionKind.REACT, "18", "React 18", true,  0, "^18.3.1", "^18.3.3");
        version(VersionKind.REACT, "19", "React 19", false, 1, "^19.0.0", "^19.0.0");

        version(VersionKind.NODE, "20", "Node 20 (LTS)",         true,  0, null, null);
        version(VersionKind.NODE, "22", "Node 22 (Current)",     false, 1, null, null);
        version(VersionKind.NODE, "18", "Node 18 (Maintenance)", false, 2, null, null);

        version(VersionKind.PACKAGE_MANAGER, "pnpm", "pnpm", true,  0, null, null);
        version(VersionKind.PACKAGE_MANAGER, "npm",  "npm",  false, 1, null, null);
    }

    private void version(VersionKind kind, String versionId, String displayName,
                         boolean isDefault, int sortOrder, String npmSemver, String typesSemver) {
        if (versionRepo.findByKindAndVersionId(kind, versionId).isPresent()) return;
        VersionDefinitionEntity v = new VersionDefinitionEntity();
        v.setKind(kind);
        v.setVersionId(versionId);
        v.setDisplayName(displayName);
        v.setDefault(isDefault);
        v.setSortOrder(sortOrder);
        v.setEnabled(true);
        v.setNpmSemver(npmSemver);
        v.setTypesSemver(typesSemver);
        versionRepo.save(v);
    }

}
