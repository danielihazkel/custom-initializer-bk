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
    private final EntityTemplateSetSync templateSetSync;
    private final CatalogSync catalogSync;
    private final ColorPaletteRepository colorPaletteRepo;
    private final VersionDefinitionRepository versionRepo;
    private final DepartmentRepository departmentRepo;
    private final FullstackExampleRepository fullstackExampleRepo;

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
                      EntityTemplateSetSync templateSetSync,
                      CatalogSync catalogSync,
                      ColorPaletteRepository colorPaletteRepo,
                      VersionDefinitionRepository versionRepo,
                      DepartmentRepository departmentRepo,
                      FullstackExampleRepository fullstackExampleRepo) {
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
        this.templateSetSync = templateSetSync;
        this.catalogSync = catalogSync;
        this.colorPaletteRepo = colorPaletteRepo;
        this.versionRepo = versionRepo;
        this.departmentRepo = departmentRepo;
        this.fullstackExampleRepo = fullstackExampleRepo;
    }

    @Override
    @Transactional
    public void afterSingletonsInstantiated() {
        // The catalog history: seeding an empty database is the release itself, not a change
        // anybody made, so it is not recorded; what a start does to an existing one (the syncs
        // bringing rows up to this release) is, as "release-sync" by "system".
        // Flushed inside the scope: the history is written as the changes reach the database, and
        // this method's own transaction would otherwise commit them after the scope has closed.
        boolean fresh = groupRepo.count() == 0;
        Runnable work = () -> {
            seedAndSync();
            entityManager.flush();
        };
        if (fresh) {
            com.menora.initializr.admin.history.CatalogChangeContext.suppressed(work);
        } else {
            com.menora.initializr.admin.history.CatalogChangeContext.as("release-sync", work);
        }
    }

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private void seedAndSync() {
        try {
            // Run table-scoped seeds before the main guard so new tables added in
            // later releases (e.g. color_palette, entity_template_set) get populated
            // on existing installations without forcing a full re-seed.
            seedColorPalettes();
            templateSetSync.sync();
            seedVersionsIfMissing();
            seedDepartmentsIfMissing();
            syncFullstackExamples();

            if (groupRepo.count() > 0) {
                log.info("Database already seeded — skipping main DataSeeder");
                normalizeLegacyBlankStrings();
                // The catalog's rows nobody edited follow the release.
                catalogSync.sync();
                return;
            }
            log.info("Seeding database from classpath resources...");
            seedCatalog(ProjectKind.BACKEND);
            seedStarterTemplates();
            seedModuleTemplates();
            seedCatalog(ProjectKind.FRONTEND);
            log.info("Seeded frontend catalog (FRONTEND kind)");
            seedFrontendStarterTemplates();
            // Every row is the shipped one: recorded as such, so later releases can follow.
            catalogSync.sync();
            log.info("Database seeding complete");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to seed database from classpath", e);
        }
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
    // The dependency catalog — groups and entries, file contributions, build customizations,
    // sub-options and compatibility rules — lives in src/main/resources/catalog/*.json (and
    // catalog/frontend/). CatalogSync reads the manifests (shippedDependencyGroups,
    // shippedFileContributions, …) and keeps the rows in step with later releases; the content
    // files they reference by contentResource stay on the classpath.

    /**
     * One kind's catalog as this release ships it, through the readers {@link CatalogSync}
     * compares with — so what a fresh database is seeded with and what a later start calls "the
     * shipped row" cannot drift apart. Saving a group saves its entries.
     */
    private void seedCatalog(ProjectKind kind) throws IOException {
        groupRepo.saveAll(catalogSync.shippedDependencyGroups(kind));
        fileContribRepo.saveAll(catalogSync.shippedFileContributions(kind));
        buildCustomRepo.saveAll(catalogSync.shippedBuildCustomizations(kind));
        subOptionRepo.saveAll(catalogSync.shippedSubOptions(kind));
        compatibilityRepo.saveAll(catalogSync.shippedCompatibilityRules(kind));
    }

    // ── Starter templates ───────────────────────────────────────────────────

    private void seedStarterTemplates() {
        StarterTemplateEntity restApi = starterTemplate(
                "rest-api", "REST API Service",
                "Spring Web + JPA + db2 + Actuator",
                "api", "#4CAF50", null, null, null, 0);
        templateDep(restApi, "web", null);
        templateDep(restApi, "data-jpa", null);
        templateDep(restApi, "db2", "db2-primary");
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
                "Menora brand purple with gold accent", "#9A83F7", "#FEDB41", "#8AEF1E", "#FF6339", true, 0);
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
        // The customer-site brand (menoramivt.co.il): purple is identity, yellow is the action colour.
        colorPalette("menora-digital", "Menora Digital",
                "Menora Mivtachim customer-site purple with the yellow action colour", "#684eed", "#ffc700", null, null, false, 9);
    }

    /**
     * Seeds the department list with {@code lts} as the default — the value every template
     * hardcoded before departments became selectable. Table-scoped (not per-row) so a
     * department an admin deleted is not resurrected on the next restart.
     */
    private void seedDepartmentsIfMissing() {
        if (departmentRepo.count() > 0) return;
        DepartmentEntity d = new DepartmentEntity();
        d.setDepartmentId("lts");
        d.setName("LTS");
        d.setDefault(true);
        d.setSortOrder(0);
        departmentRepo.save(d);
    }

    /**
     * Seeds the Fullstack tab's "Start from → Examples" from {@code catalog/fullstack-examples.json}
     * and keeps them in step with the release. An empty table gets every example. Otherwise an
     * existing example an admin has not edited (its content still hashes to the
     * {@code seedHash} it was seeded with, or it predates the hash) takes the shipped name,
     * description, icon, entities, pages and settings; an edited one is kept, and so are its
     * {@code sortOrder} and {@code enabled}. A shipped example with no row is not added back —
     * an admin may have deleted it.
     */
    private void syncFullstackExamples() throws IOException {
        boolean empty = fullstackExampleRepo.count() == 0;
        if (!empty && !templateSetSync.enabled()) return;
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(readClasspath("catalog/fullstack-examples.json"));
        int updated = 0;
        for (JsonNode ex : root) {
            String exampleId = ex.get("exampleId").asText();
            FullstackExampleEntity e = empty ? null : fullstackExampleRepo.findByExampleId(exampleId).orElse(null);
            if (e == null) {
                if (!empty) continue;
                e = new FullstackExampleEntity();
                e.setExampleId(exampleId);
                e.setSortOrder(ex.path("sortOrder").asInt(0));
                e.setEnabled(ex.path("enabled").asBoolean(true));
            } else {
                String current = exampleHash(mapper, e);
                boolean untouched = e.getSeedHash() == null || e.getSeedHash().equals(current);
                if (!untouched) {
                    FullstackExampleEntity shipped = shippedExample(mapper, ex, new FullstackExampleEntity());
                    if (!current.equals(exampleHash(mapper, shipped))) {
                        log.info("Fullstack example '{}': keeping the admin-edited version", exampleId);
                        continue;
                    }
                }
            }
            String before = e.getId() == null ? null : exampleHash(mapper, e);
            shippedExample(mapper, ex, e);
            String shippedHash = exampleHash(mapper, e);
            if (shippedHash.equals(before) && shippedHash.equals(e.getSeedHash())) continue;
            if (before != null && !shippedHash.equals(before)) updated++;
            e.setSeedHash(shippedHash);
            fullstackExampleRepo.save(e);
        }
        if (updated > 0) log.info("Fullstack examples: {} updated to this release", updated);
    }

    /** Copies a shipped example's content (not its order or enabled flag) onto a row. */
    private static FullstackExampleEntity shippedExample(ObjectMapper mapper, JsonNode ex, FullstackExampleEntity e)
            throws IOException {
        e.setName(ex.get("name").asText());
        e.setDescription(ex.path("description").asText(null));
        e.setIcon(ex.path("icon").asText(null));
        e.setEntities(mapper.writeValueAsString(ex.get("entities")));
        e.setPages(ex.hasNonNull("pages") ? mapper.writeValueAsString(ex.get("pages")) : null);
        e.setSettings(ex.hasNonNull("settings") ? mapper.writeValueAsString(ex.get("settings")) : null);
        return e;
    }

    /** Hash of what an example shows and generates, its JSON re-serialized so formatting is no edit. */
    private static String exampleHash(ObjectMapper mapper, FullstackExampleEntity e) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String part : new String[] {e.getName(), e.getDescription(), e.getIcon()}) {
            text.append(part == null ? "" : part).append('\u0001');
        }
        for (String json : new String[] {e.getEntities(), e.getPages(), e.getSettings()}) {
            text.append(json == null || json.isBlank() ? "" : mapper.writeValueAsString(mapper.readTree(json)))
                    .append('\u0001');
        }
        return EntityTemplateSetSync.hash(text.toString());
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
        // The last 3.x line. Offered beside the default, not instead of it; an admin edits the
        // row (Admin → Versions) to follow the patch release the company repository mirrors.
        version(VersionKind.BOOT, "3.5.16", "3.5.16", false, 1, null, null);

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
