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
            loadDependencyCatalog();
            loadFileContributions();
            loadBuildCustomizations();
            loadSubOptions();
            loadCompatibilityRules();
            seedStarterTemplates();
            seedModuleTemplates();
            seedFrontendCatalog();
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
            row.setContent(readClasspath(baseDir + f.get("source").asText()));
            row.setSubstitutionType(FileContributionEntity.SubstitutionType.valueOf(
                    f.get("substitutionType").asText()));
            row.setFileType(EntityTemplateFileEntity.FileType.valueOf(f.get("fileType").asText()));
            row.setPerEntity(f.hasNonNull("perEntity") && f.get("perEntity").asBoolean());
            row.setSortOrder(f.hasNonNull("sortOrder") ? f.get("sortOrder").asInt() : 0);
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

    private void loadDependencyCatalog() throws IOException {
        DependenciesManifest manifest = readManifest("catalog/dependencies.json", DependenciesManifest.class);
        for (GroupDef gd : manifest.groups()) {
            DependencyGroupEntity g = new DependencyGroupEntity();
            g.setName(gd.name());
            g.setSortOrder(gd.sortOrder());
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
                entryRepo.save(e);
            }
        }
    }

    private void loadFileContributions() throws IOException {
        for (FileContribDef d : readManifestList("catalog/file-contributions.json", FileContribDef.class)) {
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
            fileContribRepo.save(e);
        }
    }

    private void loadBuildCustomizations() throws IOException {
        for (BuildCustomDef d : readManifestList("catalog/build-customizations.json", BuildCustomDef.class)) {
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
            e.setSortOrder(d.sortOrder());
            buildCustomRepo.save(e);
        }
    }

    private void loadSubOptions() throws IOException {
        for (SubOptionDef d : readManifestList("catalog/sub-options.json", SubOptionDef.class)) {
            DependencySubOptionEntity e = new DependencySubOptionEntity();
            e.setDependencyId(d.depId());
            e.setOptionId(d.optionId());
            e.setLabel(d.label());
            e.setDescription(d.description());
            e.setSortOrder(d.sortOrder());
            subOptionRepo.save(e);
        }
    }

    private void loadCompatibilityRules() throws IOException {
        for (CompatibilityDef d : readManifestList("catalog/compatibility.json", CompatibilityDef.class)) {
            DependencyCompatibilityEntity e = new DependencyCompatibilityEntity();
            e.setSourceDepId(d.sourceDepId());
            e.setTargetDepId(d.targetDepId());
            e.setRelationType(DependencyCompatibilityEntity.RelationType.valueOf(d.relationType()));
            e.setDescription(d.description());
            e.setSortOrder(d.sortOrder());
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
                "Default Menora blue/violet palette", "#1976d2", "#9c27b0", null, "#d32f2f", true, 0);
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

    private void seedFrontendCatalog() throws IOException {
        // Groups
        DependencyGroupEntity routing  = feGroup("Routing", 0);
        DependencyGroupEntity state    = feGroup("State Management", 1);
        DependencyGroupEntity data     = feGroup("Data Fetching", 2);
        DependencyGroupEntity styling  = feGroup("Styling", 3);
        DependencyGroupEntity design   = feGroup("Design System", 4);
        DependencyGroupEntity forms    = feGroup("Forms & Validation", 5);
        DependencyGroupEntity anim     = feGroup("Animation", 6);
        DependencyGroupEntity testing  = feGroup("Testing", 7);
        DependencyGroupEntity quality  = feGroup("Quality (default-on)", 8);
        DependencyGroupEntity extras   = feGroup("Extras", 9);
        DependencyGroupEntity apiInteg = feGroup("API Integration", 10);

        // Entries
        feEntry(routing,  "router-react-router", "React Router",
                "Declarative routing for React (react-router-dom v6)", 0);
        feEntry(routing,  "router-tanstack",     "TanStack Router",
                "Type-safe, file-based routing with first-class data loading", 1);

        feEntry(state,    "state-zustand",       "Zustand",
                "Small, fast, scalable state-management with a tiny API", 0);
        feEntry(state,    "state-redux-toolkit", "Redux Toolkit",
                "Standard, opinionated Redux with @reduxjs/toolkit + react-redux", 1);
        feEntry(state,    "state-jotai",         "Jotai",
                "Primitive, atomic state management for React", 2);

        feEntry(data,     "data-tanstack-query", "TanStack Query",
                "Powerful asynchronous state management for server data", 0);
        feEntry(data,     "data-swr",            "SWR",
                "React Hooks for data fetching from Vercel", 1);

        feEntry(styling,  "style-tailwind",      "Tailwind CSS",
                "Utility-first CSS framework with PostCSS + Autoprefixer", 0);
        feEntry(styling,  "style-styled",        "styled-components",
                "Visual primitives for CSS-in-JS", 1);

        feEntry(design,   "design-none",         "None / Plain CSS",
                "No component library — bring your own UI", 0);
        feEntry(design,   "design-shadcn",       "shadcn/ui",
                "Tailwind + Radix copy-paste components (requires Tailwind)", 1);
        feEntry(design,   "design-mui",          "Material UI (MUI)",
                "Comprehensive React component library implementing Material Design", 2);
        feEntry(design,   "design-chakra",       "Chakra UI",
                "Modular, accessible component library powered by Emotion", 3);
        feEntry(design,   "design-mantine",      "Mantine",
                "Full-featured components plus a rich React hooks library", 4);

        feEntry(forms,    "form-react-hook-form","React Hook Form",
                "Performant, flexible and extensible forms with easy validation", 0);
        feEntry(forms,    "form-zod",            "Zod",
                "TypeScript-first schema validation with static type inference", 1);

        feEntry(anim,     "anim-framer-motion",  "Framer Motion",
                "Production-ready animation library for React", 0);

        feEntry(testing,  "test-vitest-rtl",     "Vitest + React Testing Library",
                "Fast unit test runner + RTL with jsdom and jest-dom matchers", 0);
        feEntry(testing,  "test-playwright",     "Playwright",
                "End-to-end testing for modern web apps", 1);
        feEntry(testing,  "test-msw",            "MSW (Mock Service Worker)",
                "API mocking library for browsers and Node, network-level interception", 2);

        feEntry(quality,  "quality-eslint",      "ESLint (flat config)",
                "Linting for TS + React, included by default in every project", 0);
        feEntry(quality,  "quality-prettier",    "Prettier",
                "Opinionated code formatter, included by default in every project", 1);
        feEntry(quality,  "quality-husky",       "Husky + lint-staged",
                "Git hook runner with pre-commit lint/format checks", 2);

        feEntry(extras,   "i18n-react-i18next",  "react-i18next",
                "Internationalization framework based on i18next", 0);
        feEntry(extras,   "storybook",           "Storybook",
                "Workshop for building UI components and pages in isolation", 1);
        feEntry(extras,   "auth-msal",           "Microsoft Auth (MSAL React)",
                "@azure/msal-react — Microsoft Identity Platform integration", 2);
        feEntry(extras,   "chart-recharts",      "Recharts",
                "Composable charting library built on React + D3", 3);

        feEntry(apiInteg, "api-client-openapi",  "OpenAPI Typed Client",
                "Generate a typed TypeScript client from the paired backend's OpenAPI spec", 0);

        // ── Common file contributions ────────────────────────────────────────
        int o = 0;
        // FSD layer barrels (one row each — keeps admin edits granular)
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/app/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export { HomePage } from './home';\n",
                "src/pages/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/widgets/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/features/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/entities/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/shared/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);

        // Layer READMEs
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# app\n\nApp-level providers, router, and global wiring. Imports from every layer below.\n",
                "src/app/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# pages\n\nRoute-level components. May import from widgets/features/entities/shared.\n",
                "src/pages/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# widgets\n\nComposite UI blocks. May import from features/entities/shared.\n",
                "src/widgets/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# features\n\nBusiness-level interactions. May import from entities/shared.\n",
                "src/features/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# entities\n\nDomain models and their UI. May import from shared only.\n",
                "src/entities/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# shared\n\nReusable infrastructure: ui-kit, lib helpers, api, config. Imports nothing from above.\n",
                "src/shared/README.md", FileContributionEntity.SubstitutionType.NONE, o++);

        // Entry files (templated so they pick up project metadata + selected deps)
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-index-html.mustache"),
                "index.html", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-main-tsx.mustache"),
                "src/main.tsx", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-app-tsx.mustache"),
                "src/app/App.tsx", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-pages-home-index.mustache"),
                "src/pages/home/index.ts", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-pages-home-ui.mustache"),
                "src/pages/home/ui/HomePage.tsx", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-readme.mustache"),
                "README.md", FileContributionEntity.SubstitutionType.MUSTACHE, o++);

        // Common static configs
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/tsconfig.json"),
                "tsconfig.json", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/tsconfig.node.json"),
                "tsconfig.node.json", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.gitignore"),
                ".gitignore", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.editorconfig"),
                ".editorconfig", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.dockerignore"),
                ".dockerignore", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/Dockerfile"),
                "Dockerfile", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/nginx.conf"),
                "nginx.conf", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/Jenkinsfile"),
                "Jenkinsfile", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/eslint.config.js"),
                "eslint.config.js", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.prettierrc.json"),
                ".prettierrc.json", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/husky-pre-commit"),
                ".husky/pre-commit", FileContributionEntity.SubstitutionType.NONE, o++);

        // Paired-backend / MSAL env files — body conditionally renders sections
        // for each opt-in. FrontendProjectGenerator skips writes for blank-rendered
        // TEMPLATE contributions, so projects with neither feature get no env file.
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                "{{#hasBackendPair}}VITE_API_BASE_URL={{apiBaseUrl}}\n{{/hasBackendPair}}"
                        + "{{#optAuthMsalInitConfig}}VITE_MSAL_CLIENT_ID=\n"
                        + "VITE_MSAL_TENANT_ID=\n"
                        + "VITE_MSAL_REDIRECT_URI=\n{{/optAuthMsalInitConfig}}",
                ".env.development", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                "{{#hasBackendPair}}# Override this per-environment. The dev value comes from .env.development.\n"
                        + "VITE_API_BASE_URL={{apiBaseUrl}}\n{{/hasBackendPair}}"
                        + "{{#optAuthMsalInitConfig}}# Azure AD app registration values — fill in per environment.\n"
                        + "VITE_MSAL_CLIENT_ID=\n"
                        + "VITE_MSAL_TENANT_ID=\n"
                        + "VITE_MSAL_REDIRECT_URI=\n{{/optAuthMsalInitConfig}}",
                ".env.example", FileContributionEntity.SubstitutionType.MUSTACHE, o++);

        // ── Per-dep file contributions ───────────────────────────────────────
        // Tailwind: config files + base CSS (Vite plugin import handled via ADD_VITE_PLUGIN below).
        feFc("style-tailwind", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/style-tailwind/tailwind.config.js.mustache"),
                "tailwind.config.js", FileContributionEntity.SubstitutionType.MUSTACHE, 0);
        feFc("style-tailwind", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/style-tailwind/postcss.config.js"),
                "postcss.config.js", FileContributionEntity.SubstitutionType.NONE, 1);
        feFc("style-tailwind", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/style-tailwind/index.css"),
                "src/index.css", FileContributionEntity.SubstitutionType.NONE, 2);

        // Vitest: config + test setup
        feFc("test-vitest-rtl", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-vitest-rtl/vitest.config.ts"),
                "vitest.config.ts", FileContributionEntity.SubstitutionType.NONE, 0);
        feFc("test-vitest-rtl", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-vitest-rtl/test-setup.ts"),
                "src/test-setup.ts", FileContributionEntity.SubstitutionType.NONE, 1);
        feFc("test-vitest-rtl", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-vitest-rtl/home-page.test.tsx"),
                "src/pages/home/ui/HomePage.test.tsx", FileContributionEntity.SubstitutionType.NONE,
                "sample-tests", 2);
        feFc("test-vitest-rtl", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-vitest-rtl/github-actions-ci.yml"),
                ".github/workflows/ci.yml", FileContributionEntity.SubstitutionType.NONE,
                "ci-config", 3);

        // Playwright: config + sample spec + CI workflow (each sub-option gated).
        feFc("test-playwright", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-playwright/playwright.config.ts"),
                "playwright.config.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-config", 0);
        feFc("test-playwright", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-playwright/home-spec.ts"),
                "e2e/home.spec.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-spec", 1);
        feFc("test-playwright", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-playwright/github-actions-e2e.yml"),
                ".github/workflows/e2e.yml", FileContributionEntity.SubstitutionType.NONE,
                "ci-config", 2);

        // Storybook: .storybook config + sample story (each sub-option gated).
        // preview.ts is a TEMPLATE because its CSS import is gated on Tailwind —
        // index.css only exists in projects that selected style-tailwind.
        feFc("storybook", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/storybook/main.ts"),
                ".storybook/main.ts", FileContributionEntity.SubstitutionType.NONE,
                "init-config", 0);
        feFc("storybook", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/storybook/preview.ts"),
                ".storybook/preview.ts", FileContributionEntity.SubstitutionType.MUSTACHE,
                "init-config", 1);
        feFc("storybook", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/storybook/example-stories.tsx"),
                "src/shared/ui/example.stories.tsx", FileContributionEntity.SubstitutionType.NONE,
                "sample-story", 2);

        // Default starter wiring per dep — gives users a runnable demo of the
        // selected library, not just an entry in package.json. App.tsx picks
        // these up via the {{#hasRouterReactRouter}} / {{#hasStateRedux...}}
        // / {{#hasDataTanstackQuery}} / {{#hasStateZustand}} flags.
        feFc("router-react-router", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/router-react-router/routes.tsx.mustache"),
                "src/app/routes.tsx", FileContributionEntity.SubstitutionType.MUSTACHE, 0);
        feFc("router-react-router", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/router-react-router/error-boundary.tsx"),
                "src/shared/ui/error-boundary.tsx", FileContributionEntity.SubstitutionType.NONE,
                "error-boundary", 1);
        feFc("router-react-router", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/router-react-router/about-index.ts"),
                "src/pages/about/index.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-routes", 2);
        feFc("router-react-router", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/router-react-router/about-page.tsx"),
                "src/pages/about/ui/AboutPage.tsx", FileContributionEntity.SubstitutionType.NONE,
                "sample-routes", 3);

        feFc("state-zustand", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/state-zustand/counter-store.ts.mustache"),
                "src/entities/counter/model/store.ts", FileContributionEntity.SubstitutionType.MUSTACHE,
                "sample-store", 0);

        feFc("state-redux-toolkit", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/state-redux-toolkit/store.ts"),
                "src/app/store.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-store", 0);
        feFc("state-redux-toolkit", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/state-redux-toolkit/counterSlice.ts"),
                "src/entities/counter/model/counterSlice.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-store", 1);
        feFc("state-redux-toolkit", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/state-redux-toolkit/hooks.ts"),
                "src/shared/lib/hooks.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-store", 2);

        feFc("data-tanstack-query", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/data-tanstack-query/queryClient.ts"),
                "src/shared/api/queryClient.ts", FileContributionEntity.SubstitutionType.NONE, 0);
        feFc("data-tanstack-query", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/data-tanstack-query/axios.ts"),
                "src/shared/api/axios.ts", FileContributionEntity.SubstitutionType.NONE,
                "axios-base", 1);
        feFc("data-tanstack-query", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/data-tanstack-query/use-users.ts"),
                "src/features/users/api/useUsers.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-query", 2);

        // Design system: theme stubs & shadcn helpers
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/components.json"),
                "components.json", FileContributionEntity.SubstitutionType.NONE, 0);
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/lib-utils.ts"),
                "src/shared/lib/utils.ts", FileContributionEntity.SubstitutionType.NONE, 1);
        // Overwrites the bare @tailwind index.css written by style-tailwind (sortOrder 2)
        // with one that also defines shadcn's CSS-variable design tokens, sourced from
        // the selected color palette via FrontendMustacheContext's HSL helpers.
        feFc("design-shadcn", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/design-shadcn/index.css"),
                "src/index.css", FileContributionEntity.SubstitutionType.MUSTACHE, 10);
        // Pre-built primitives — each gated by its own sub-option so users only pay for
        // what they pick. Components import { cn } from '@shared/lib/utils' (already shipped above).
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/button.tsx"),
                "src/shared/ui/button.tsx", FileContributionEntity.SubstitutionType.NONE,
                "comp-button", 11);
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/card.tsx"),
                "src/shared/ui/card.tsx", FileContributionEntity.SubstitutionType.NONE,
                "comp-card", 12);
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/input.tsx"),
                "src/shared/ui/input.tsx", FileContributionEntity.SubstitutionType.NONE,
                "comp-input", 13);
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/dialog.tsx"),
                "src/shared/ui/dialog.tsx", FileContributionEntity.SubstitutionType.NONE,
                "comp-dialog", 14);
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/toast.tsx"),
                "src/shared/ui/toast.tsx", FileContributionEntity.SubstitutionType.NONE,
                "comp-toast", 15);
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/use-toast.ts"),
                "src/shared/lib/use-toast.ts", FileContributionEntity.SubstitutionType.NONE,
                "comp-toast", 16);

        // MSAL: provider config + sample login. init-config also flips an env block
        // in .env.development/.env.example (see __common__ contributions above) and
        // a MsalProvider wrap in fe-app-tsx.mustache. Sample-login generates a
        // LoginButton component plus a useAuth() convenience hook.
        feFc("auth-msal", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/auth-msal/msal-config.ts"),
                "src/shared/auth/msal-config.ts", FileContributionEntity.SubstitutionType.NONE,
                "init-config", 0);
        feFc("auth-msal", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/auth-msal/login-button.tsx"),
                "src/shared/ui/login-button.tsx", FileContributionEntity.SubstitutionType.NONE,
                "sample-login", 1);
        feFc("auth-msal", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/auth-msal/use-auth.ts"),
                "src/shared/lib/use-auth.ts", FileContributionEntity.SubstitutionType.NONE,
                "sample-login", 2);

        // styled-components: themed via DefaultTheme + module augmentation
        feFc("style-styled", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/style-styled/theme.ts"),
                "src/shared/theme/theme.ts", FileContributionEntity.SubstitutionType.MUSTACHE, 0);
        feFc("style-styled", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/style-styled/styled.d.ts"),
                "src/shared/theme/styled.d.ts", FileContributionEntity.SubstitutionType.NONE, 1);

        feFc("design-mui", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/design-mui/theme.ts"),
                "src/shared/theme/theme.ts", FileContributionEntity.SubstitutionType.MUSTACHE, 0);
        feFc("design-chakra", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/design-chakra/theme.ts"),
                "src/shared/theme/theme.ts", FileContributionEntity.SubstitutionType.MUSTACHE, 0);
        feFc("design-mantine", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/design-mantine/theme.ts"),
                "src/shared/theme/theme.ts", FileContributionEntity.SubstitutionType.MUSTACHE, 0);

        // form-react-hook-form: sample signup form (TEMPLATE — body switches on rhf-zod-resolver)
        feFc("form-react-hook-form", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/form-react-hook-form/signup-form.tsx.mustache"),
                "src/features/signup/ui/SignupForm.tsx", FileContributionEntity.SubstitutionType.MUSTACHE,
                "sample-form", 0);

        // api-client-openapi: types-only client wires openapi-typescript; the actual
        // openapi.yaml is injected at generation time from OpenApiSpecContext (paired
        // wizard's backend spec). The .gitkeep ensures the output dir exists even
        // before `gen:api` runs once.
        feFc("api-client-openapi", FileContributionEntity.FileType.STATIC_COPY,
                "# Generated TypeScript bindings for the paired backend's OpenAPI spec.\n"
                        + "# Run `gen:api` after pulling spec changes.\n",
                "src/shared/api/generated/.gitkeep", FileContributionEntity.SubstitutionType.NONE, 0);
        // README pointer so users know how the generated dir is meant to be used.
        feFc("api-client-openapi", FileContributionEntity.FileType.STATIC_COPY,
                "# api-client-openapi\n\n"
                        + "This project consumes the paired backend's OpenAPI contract.\n\n"
                        + "- `openapi.yaml` at the project root carries the spec.\n"
                        + "- `gen:api` script regenerates `src/shared/api/generated/schema.ts`.\n"
                        + "- Pair with TanStack Query for typed hooks (recommended).\n",
                "src/shared/api/README.md", FileContributionEntity.SubstitutionType.NONE, 1);
        // Orval config — only written when the react-query-hooks sub-option is on.
        // Orval itself is not auto-installed (current build-customization model has
        // no sub-option gating); users opt in via `pnpm add -D orval`.
        feFc("api-client-openapi", FileContributionEntity.FileType.STATIC_COPY,
                "// Generated config for orval (https://orval.dev).\n"
                        + "// Install with: pnpm add -D orval (then run `npx orval`).\n"
                        + "import { defineConfig } from 'orval';\n\n"
                        + "export default defineConfig({\n"
                        + "  api: {\n"
                        + "    input: './openapi.yaml',\n"
                        + "    output: {\n"
                        + "      mode: 'tags-split',\n"
                        + "      target: 'src/shared/api/generated/endpoints.ts',\n"
                        + "      schemas: 'src/shared/api/generated/model',\n"
                        + "      client: 'react-query',\n"
                        + "      override: { mutator: { path: './src/shared/api/axios.ts', name: 'api' } },\n"
                        + "    },\n"
                        + "  },\n"
                        + "});\n",
                "orval.config.ts", FileContributionEntity.SubstitutionType.NONE,
                "react-query-hooks", 2);

        // ── Common npm deps (every FE project) ───────────────────────────────
        // react / react-dom / @types/react / @types/react-dom are pinned in the
        // baseline package.json template via {{reactPackageVersion}} so picking
        // React 19 in the UI actually generates a React 19 project. See
        // FrontendMustacheContext.reactPackageVersion(...).
        feNpm("__common__", "@vitejs/plugin-react", "^4.3.1",  "dev", 4);
        feNpm("__common__", "vite",                 "^5.2.0",  "dev", 5);
        feNpm("__common__", "typescript",           "^5.4.5",  "dev", 6);

        // Quality baseline (always on, per user preference)
        feNpm("__common__", "eslint",                              "^9.10.0", "dev", 10);
        feNpm("__common__", "@eslint/js",                          "^9.10.0", "dev", 11);
        feNpm("__common__", "typescript-eslint",                   "^8.5.0",  "dev", 12);
        feNpm("__common__", "eslint-plugin-react-hooks",           "^5.1.0",  "dev", 13);
        feNpm("__common__", "eslint-plugin-react-refresh",         "^0.4.11", "dev", 14);
        feNpm("__common__", "globals",                             "^15.9.0", "dev", 15);
        feNpm("__common__", "prettier",                            "^3.3.3",  "dev", 16);
        feNpm("__common__", "husky",                               "^9.1.5",  "dev", 17);
        feNpm("__common__", "lint-staged",                         "^15.2.10","dev", 18);

        // Common Vite plugin: @vitejs/plugin-react
        feVitePlugin("__common__", "@vitejs/plugin-react", "react", "react()", 0);

        // ── npm scripts contributed via ADD_NPM_SCRIPT ───────────────────────
        // The baseline package.json template hardcodes the obvious scripts
        // (dev/build/lint/test). These rows extend the baseline without
        // touching it — admins can add more via /admin/build-customizations.
        feNpmScript("__common__",        "lint:fix",      "eslint . --fix",                    0);
        feNpmScript("__common__",        "format:check",  "prettier --check .",                1);
        feNpmScript("__common__",        "typecheck",     "tsc --noEmit",                      2);
        feNpmScript("test-playwright",   "e2e:install",   "playwright install --with-deps",    0);
        feNpmScript("test-playwright",   "e2e:report",    "playwright show-report",            1);
        // gen:api — by default produces typed bindings via openapi-typescript;
        // when react-query-hooks is selected, PackageJsonBuilder doesn't override
        // this (single script row per name wins) so users running with orval
        // would manually swap to `orval` after generation. Phase 3.5 may add
        // sub-option-gated script variants.
        feNpmScript("api-client-openapi", "gen:api",
                "openapi-typescript openapi.yaml -o src/shared/api/generated/schema.ts", 0);

        // ── Per-dep npm deps ─────────────────────────────────────────────────
        feNpm("router-react-router", "react-router-dom",                "^6.26.0", "",    0);
        feNpm("router-tanstack",     "@tanstack/react-router",          "^1.50.0", "",    0);
        feNpm("router-tanstack",     "@tanstack/router-devtools",       "^1.50.0", "dev", 1);

        feNpm("state-zustand",       "zustand",                         "^4.5.5",  "",    0);
        feNpm("state-redux-toolkit", "@reduxjs/toolkit",                "^2.2.7",  "",    0);
        feNpm("state-redux-toolkit", "react-redux",                     "^9.1.2",  "",    1);
        feNpm("state-jotai",         "jotai",                           "^2.9.3",  "",    0);

        feNpm("data-tanstack-query", "@tanstack/react-query",           "^5.51.23","",    0);
        feNpm("data-tanstack-query", "@tanstack/react-query-devtools",  "^5.51.23","dev", 1);
        feNpm("data-tanstack-query", "axios",                           "^1.7.0",  "",    2);
        feNpm("data-swr",            "swr",                             "^2.2.5",  "",    0);

        feNpm("style-tailwind",      "tailwindcss",                     "^3.4.10", "dev", 0);
        feNpm("style-tailwind",      "postcss",                         "^8.4.41", "dev", 1);
        feNpm("style-tailwind",      "autoprefixer",                    "^10.4.20","dev", 2);
        feNpm("style-styled",        "styled-components",               "^6.1.13", "",    0);
        feNpm("style-styled",        "@types/styled-components",        "^5.1.34", "dev", 1);

        // Design system component libraries
        feNpm("design-shadcn",       "clsx",                            "^2.1.1",  "",    0);
        feNpm("design-shadcn",       "tailwind-merge",                  "^2.5.2",  "",    1);
        feNpm("design-shadcn",       "class-variance-authority",        "^0.7.0",  "",    2);
        feNpm("design-shadcn",       "@radix-ui/react-slot",            "^1.1.0",  "",    3);
        // Radix primitives ship only when their component is picked.
        feNpm("design-shadcn",       "@radix-ui/react-dialog",          "^1.1.1",  "",    "comp-dialog", 4);
        feNpm("design-shadcn",       "@radix-ui/react-toast",           "^1.2.1",  "",    "comp-toast",  5);
        feNpm("design-mui",          "@mui/material",                   "^5.16.7", "",    0);
        feNpm("design-mui",          "@emotion/react",                  "^11.13.0","",    1);
        feNpm("design-mui",          "@emotion/styled",                 "^11.13.0","",    2);
        feNpm("design-chakra",       "@chakra-ui/react",                "^2.8.2",  "",    0);
        feNpm("design-chakra",       "@emotion/react",                  "^11.13.0","",    1);
        feNpm("design-chakra",       "@emotion/styled",                 "^11.13.0","",    2);
        feNpm("design-chakra",       "framer-motion",                   "^11.3.31","",    3);
        feNpm("design-mantine",      "@mantine/core",                   "^7.13.0", "",    0);
        feNpm("design-mantine",      "@mantine/hooks",                  "^7.13.0", "",    1);

        feNpm("form-react-hook-form","react-hook-form",                 "^7.53.0", "",    0);
        // Ship the resolver bridge + zod under RHF too so the rhf-zod-resolver
        // sub-option produces a valid project even when form-zod isn't selected.
        feNpm("form-react-hook-form","@hookform/resolvers",             "^3.9.0",  "",    1);
        feNpm("form-react-hook-form","zod",                             "^3.23.8", "",    2);
        feNpm("form-zod",            "zod",                             "^3.23.8", "",    0);
        feNpm("form-zod",            "@hookform/resolvers",             "^3.9.0",  "",    1);

        feNpm("anim-framer-motion",  "framer-motion",                   "^11.3.31","",    0);

        feNpm("test-vitest-rtl",     "vitest",                          "^2.0.5",  "dev", 0);
        feNpm("test-vitest-rtl",     "@vitest/ui",                      "^2.0.5",  "dev", 1);
        feNpm("test-vitest-rtl",     "@testing-library/react",          "^16.0.1", "dev", 2);
        feNpm("test-vitest-rtl",     "@testing-library/jest-dom",       "^6.5.0",  "dev", 3);
        feNpm("test-vitest-rtl",     "jsdom",                           "^25.0.0", "dev", 4);
        feNpm("test-playwright",     "@playwright/test",                "^1.46.1", "dev", 0);
        feNpm("test-msw",            "msw",                             "^2.4.2",  "dev", 0);
        // api-client-openapi: types-only stack is always shipped; orval is added
        // when the react-query-hooks sub-option is on (gated via the FE generator).
        feNpm("api-client-openapi",  "openapi-typescript",              "^7.4.0",  "dev", 0);

        feNpm("i18n-react-i18next",  "react-i18next",                   "^15.0.1", "",    0);
        feNpm("i18n-react-i18next",  "i18next",                         "^23.14.0","",    1);

        feNpm("storybook",           "storybook",                       "^8.2.9",  "dev", 0);
        feNpm("storybook",           "@storybook/react-vite",           "^8.2.9",  "dev", 1);
        feNpm("storybook",           "@storybook/react",                "^8.2.9",  "dev", 2);
        feNpm("storybook",           "@storybook/addon-essentials",     "^8.2.9",  "dev", 3);
        feNpm("storybook",           "@storybook/addon-interactions",   "^8.2.9",  "dev", 4);
        feNpm("storybook",           "@storybook/test",                 "^8.2.9",  "dev", 5);

        feNpm("auth-msal",           "@azure/msal-browser",             "^3.21.0", "",    0);
        feNpm("auth-msal",           "@azure/msal-react",               "^2.0.22", "",    1);

        feNpm("chart-recharts",      "recharts",                        "^2.12.7", "",    0);

        // ── Sub-options (v1: metadata only; file gating can be added per row later) ──
        feSubOption("router-react-router", "lazy-routes",      "Lazy routes",
                "Use React.lazy() + Suspense for code-split routes", 0);
        feSubOption("router-react-router", "error-boundary",   "Error boundary",
                "Wrap routes in an ErrorBoundary for graceful failures", 1);
        feSubOption("router-react-router", "sample-routes",    "Sample routes",
                "Generate a demo router config with /home and /about", 2);

        feSubOption("state-zustand", "devtools",      "Redux DevTools middleware",
                "Wire zustand to the Redux DevTools browser extension", 0);
        feSubOption("state-zustand", "persist",       "Persist middleware",
                "Add zustand/middleware persist for localStorage rehydration", 1);
        feSubOption("state-zustand", "sample-store",  "Sample store",
                "Generate a counter store under src/entities/counter", 2);

        feSubOption("state-redux-toolkit", "sample-store", "Sample slice + store",
                "Generate a counter slice + configured store", 0);

        feSubOption("data-tanstack-query", "devtools",       "Devtools",
                "Include @tanstack/react-query-devtools setup", 0);
        feSubOption("data-tanstack-query", "axios-base",     "Axios base client",
                "Configure an Axios instance under shared/api", 1);
        feSubOption("data-tanstack-query", "sample-query",   "Sample query hook",
                "Generate a useUsers() hook example", 2);

        feSubOption("style-tailwind", "dark-mode",   "Dark mode (class strategy)",
                "Configure Tailwind dark mode via class attribute", 0);

        feSubOption("form-react-hook-form", "rhf-zod-resolver", "Use Zod resolver",
                "Wire @hookform/resolvers to validate with Zod schemas", 0);
        feSubOption("form-react-hook-form", "sample-form",      "Sample form",
                "Generate a demo signup form component", 1);

        feSubOption("test-vitest-rtl", "sample-tests",  "Sample tests",
                "Generate a sample render test for HomePage", 0);
        feSubOption("test-vitest-rtl", "ci-config",     "CI config",
                "Generate a GitHub Actions workflow that runs the test suite", 1);

        feSubOption("test-playwright", "sample-config", "playwright.config.ts",
                "Generate a baseline config targeting Chromium+Firefox+WebKit", 0);
        feSubOption("test-playwright", "sample-spec",   "Sample E2E spec",
                "Generate e2e/home.spec.ts covering the home page", 1);
        feSubOption("test-playwright", "ci-config",     "GitHub Actions CI",
                "Generate .github/workflows/e2e.yml that installs browsers and runs pnpm e2e", 2);

        feSubOption("storybook", "init-config",   ".storybook config",
                "Generate .storybook/main.ts and preview.ts so `pnpm storybook` works out of the box", 0);
        feSubOption("storybook", "sample-story",  "Sample story",
                "Generate src/shared/ui/example.stories.tsx as a starting point", 1);

        feSubOption("design-shadcn", "comp-button", "Button",
                "Generate src/shared/ui/button.tsx (forwardRef + cva variants)", 0);
        feSubOption("design-shadcn", "comp-card",   "Card",
                "Generate src/shared/ui/card.tsx (Card / CardHeader / CardContent / CardFooter)", 1);
        feSubOption("design-shadcn", "comp-input",  "Input",
                "Generate src/shared/ui/input.tsx", 2);
        feSubOption("design-shadcn", "comp-dialog", "Dialog",
                "Generate src/shared/ui/dialog.tsx (adds @radix-ui/react-dialog)", 3);
        feSubOption("design-shadcn", "comp-toast",  "Toast",
                "Generate src/shared/ui/toast.tsx + use-toast hook (adds @radix-ui/react-toast)", 4);

        feSubOption("auth-msal", "init-config",  "MSAL config + provider",
                "Generate msal-config.ts, wrap App in MsalProvider, and add VITE_MSAL_* env keys", 0);
        feSubOption("auth-msal", "sample-login", "Sample login button + useAuth hook",
                "Generate LoginButton.tsx and a useAuth() hook in shared/", 1);

        feSubOption("api-client-openapi", "react-query-hooks", "React Query hooks (orval)",
                "Add an orval.config.ts so `npx orval` produces typed React Query hooks "
                        + "(install orval manually: pnpm add -D orval)", 0);

        // ── React-version compatibility ranges ───────────────────────────────
        // Only set ranges where the constraint is real — leave open by default.
        // Applied via FrontendVersionRangeFilter at /frontend/metadata time.
        // MUI v5, Mantine v7, and Chakra v2 all pin react ≥18 <19 via peer deps.
        // When the upstream majors that support React 19 (MUI v6, Mantine v8,
        // Chakra v3) get seeded, drop the upper bound here.
        entryRepo.findByDepId("design-chakra")
                .ifPresent(e -> { e.setCompatibilityRange("[18.0.0,19.0.0)"); entryRepo.save(e); });
        entryRepo.findByDepId("design-mui")
                .ifPresent(e -> { e.setCompatibilityRange("[18.0.0,19.0.0)"); entryRepo.save(e); });
        entryRepo.findByDepId("design-mantine")
                .ifPresent(e -> { e.setCompatibilityRange("[18.0.0,19.0.0)"); entryRepo.save(e); });

        // ── Compatibility rules ──────────────────────────────────────────────
        feCompat("router-react-router", "router-tanstack",     DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Only one router can be selected", 0);
        feCompat("state-zustand", "state-redux-toolkit",       DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one state-management library", 0);
        feCompat("state-zustand", "state-jotai",               DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one state-management library", 1);
        feCompat("state-redux-toolkit", "state-jotai",         DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one state-management library", 2);
        feCompat("data-tanstack-query", "data-swr",            DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one data-fetching library", 0);
        feCompat("design-shadcn", "style-tailwind",            DependencyCompatibilityEntity.RelationType.REQUIRES,
                "shadcn/ui is built on Tailwind CSS", 0);
        // Design systems are mutually exclusive (UI enforces single-choice; this is a backend safety net)
        feCompat("design-shadcn",  "design-mui",               DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 0);
        feCompat("design-shadcn",  "design-chakra",            DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 1);
        feCompat("design-shadcn",  "design-mantine",           DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 2);
        feCompat("design-mui",     "design-chakra",            DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 3);
        feCompat("design-mui",     "design-mantine",           DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 4);
        feCompat("design-chakra",  "design-mantine",           DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 5);
        feCompat("form-react-hook-form", "form-zod",           DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "Pair RHF with Zod via @hookform/resolvers for typed validation", 0);
        feCompat("api-client-openapi", "data-tanstack-query",  DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "Typed OpenAPI hooks are most useful with TanStack Query", 1);

        log.info("Seeded frontend catalog (FRONTEND kind)");
    }

    // ── Frontend seed helpers ──────────────────────────────────────────────────

    private DependencyGroupEntity feGroup(String name, int order) {
        DependencyGroupEntity g = new DependencyGroupEntity();
        g.setName(name);
        g.setSortOrder(order);
        g.setProjectKind(ProjectKind.FRONTEND);
        return groupRepo.save(g);
    }

    private void feEntry(DependencyGroupEntity group, String depId, String name, String desc, int order) {
        DependencyEntryEntity e = new DependencyEntryEntity();
        e.setGroup(group);
        e.setDepId(depId);
        e.setName(name);
        e.setDescription(desc);
        e.setSortOrder(order);
        e.setStarter(true);
        e.setProjectKind(ProjectKind.FRONTEND);
        entryRepo.save(e);
    }

    private void feFc(String depId, FileContributionEntity.FileType fileType, String content,
                       String targetPath, FileContributionEntity.SubstitutionType subType, int order) {
        feFc(depId, fileType, content, targetPath, subType, null, order);
    }

    private void feFc(String depId, FileContributionEntity.FileType fileType, String content,
                       String targetPath, FileContributionEntity.SubstitutionType subType,
                       String subOptionId, int order) {
        FileContributionEntity e = new FileContributionEntity();
        e.setDependencyId(depId);
        e.setFileType(fileType);
        e.setContent(content);
        e.setTargetPath(targetPath);
        e.setSubstitutionType(subType);
        e.setSubOptionId(subOptionId);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        fileContribRepo.save(e);
    }

    private void feNpm(String depId, String pkg, String version, String scope, int order) {
        feNpm(depId, pkg, version, scope, null, order);
    }

    /** Variant that gates the npm dep on a sub-option of the parent dep. */
    private void feNpm(String depId, String pkg, String version, String scope,
                       String subOptionId, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_NPM_DEPENDENCY);
        e.setMavenArtifactId(pkg);
        e.setVersion(version);
        e.setScope(scope);
        e.setSubOptionId(subOptionId);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        buildCustomRepo.save(e);
    }

    private void feVitePlugin(String depId, String importPath, String importBinding,
                               String pluginCall, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_VITE_PLUGIN);
        e.setMavenGroupId(importPath);
        e.setMavenArtifactId(importBinding);
        e.setVersion(pluginCall);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        buildCustomRepo.save(e);
    }

    private void feNpmScript(String depId, String scriptName, String command, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_NPM_SCRIPT);
        e.setMavenArtifactId(scriptName);
        e.setVersion(command);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        buildCustomRepo.save(e);
    }

    private void feSubOption(String depId, String optionId, String label, String desc, int order) {
        DependencySubOptionEntity e = new DependencySubOptionEntity();
        e.setDependencyId(depId);
        e.setOptionId(optionId);
        e.setLabel(label);
        e.setDescription(desc);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        subOptionRepo.save(e);
    }

    private void feCompat(String source, String target,
                          DependencyCompatibilityEntity.RelationType type,
                          String desc, int order) {
        DependencyCompatibilityEntity e = new DependencyCompatibilityEntity();
        e.setSourceDepId(source);
        e.setTargetDepId(target);
        e.setRelationType(type);
        e.setDescription(desc);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        compatibilityRepo.save(e);
    }
}
