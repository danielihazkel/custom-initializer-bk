# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ProjectGenerationIntegrationTests

# Run a single test method
mvn test -Dtest=ProjectGenerationIntegrationTests#kafkaDependencyInjectsConfigFiles

# Run the application
java -jar target/offline-spring-init-1.0.0-SNAPSHOT.jar

# Verify the running service
curl http://localhost:8080/metadata/client
curl http://localhost:8080/actuator/health

# Generate a test project via API
curl -o test.zip "http://localhost:8080/starter.zip?dependencies=web,kafka"

# Hot-reload metadata after DB change (no restart needed)
curl -X POST http://localhost:8080/admin/refresh
```

## Architecture

This app wraps the Spring Initializr framework (`initializr-web` + `initializr-generator-spring` v0.23.x). The framework handles the REST API and ZIP generation; this codebase adds:

1. **A database-driven dependency catalog** — all dependency definitions, file contributions, build customizations, and sub-options live in an H2 database (file-backed in production, in-memory for tests)
2. **A single dynamic generation config** (`DynamicProjectGenerationConfiguration`) — reads from DB at generation time, replacing what used to be 8 separate hardcoded extension classes
3. **An admin REST API** (`/admin/*`) — full CRUD for all DB tables + a `/admin/refresh` endpoint to hot-reload the dependency metadata cache

### Generation Pipeline

When a project is generated, the framework spins up a child Spring application context for that request and calls every `ProjectGenerationConfiguration` registered in `META-INF/spring.factories`. Only one is registered: `DynamicProjectGenerationConfiguration`, which contributes three beans:

- **`dynamicFileContributor`** (`ProjectContributor`) — for each selected dependency (plus the special `__common__` entry), writes/merges all associated `FileContributionEntity` records into the generated project
- **`dynamicDeleteContributor`** (`ProjectContributor`, `@Order(LOWEST_PRECEDENCE)`) — runs after everything else to delete files registered with `DELETE` type (e.g. `application.properties` written by the framework)
- **`dynamicBuildCustomizer`** (`BuildCustomizer<MavenBuild>`) — applies all `BuildCustomizationEntity` records (add dependency, exclude dependency, add repository). Like the file contributor, it skips a record whose `subOptionId` is set unless that sub-option was selected (`optionsContext.hasOption(depId, subOptionId)`) — e.g. `logging`'s `kafka-clients` is added only with the `kafka-logs` sub-option

### FileContributionEntity — File Types

| Type | Behavior |
|------|----------|
| `STATIC_COPY` | Writes content verbatim to target path |
| `YAML_MERGE` | Deep-merges YAML into the target file (creates if absent) |
| `TEMPLATE` | Applies substitution variables then writes |
| `DELETE` | Deletes target file (runs at LOWEST_PRECEDENCE, after framework writes) |

### Version Gating — `java_version` / `node_version`

A `FileContributionEntity` may pin itself to a single language-runtime version, so one
target path resolves to an entirely different file per selected version. Both columns
default to null (= applies to every version):

- **`java_version`** (backend) — checked in `DynamicProjectGenerationConfiguration`; the row
  is skipped unless it equals `description.getLanguage().jvmVersion()`. Used for the backend
  `Dockerfile` (`Dockerfile-java17.mustache` vs `Dockerfile-java21.mustache`, both targeting `Dockerfile`).
- **`node_version`** (frontend) — the frontend mirror, added in `V17`. Checked in
  `FrontendProjectGenerator.nodeVersionMismatch`; the row is skipped unless it equals
  `FrontendProjectDescription.getNodeVersion()`. Used for the frontend `Dockerfile`
  (`static-configs/frontend/common/Dockerfile-node{18,20,22}`, all targeting `Dockerfile`).

To add a Node version, seed a matching `Dockerfile-node<v>` row in `catalog/frontend/file-contributions.json`
— a version with no matching row generates no Dockerfile. Both columns round-trip through admin export/import.

### Template Substitution

`TEMPLATE` contributions are rendered through a real Mustache engine (`com.samskivert:jmustache`, `escapeHTML=false`). `FileContributionEntity.SubstitutionType` has two values:

- **`MUSTACHE`** — render content with the unified context below
- **`NONE`** — write content verbatim

The context exposed to every MUSTACHE template is:

| Key | Meaning |
|-----|---------|
| `artifactId`, `groupId`, `version`, `packageName` | straight from `ProjectDescription` |
| `packagePath` | `packageName` with `.` → `/` (also available in Target Path — resolved separately) |
| `javaVersion` | `description.getLanguage().jvmVersion()` — e.g. `"17"`, `"21"` |
| `packaging` | `description.getPackaging().id()` — e.g. `"jar"`, `"war"` |
| `has<Dep>` | `true` for every selected dep. Dep id is PascalCased: `kafka` → `hasKafka`, `mail-sampler` → `hasMailSampler` |
| `opt<Dep><Option>` | `true` for every selected sub-option. e.g. `optKafkaConsumerExample` |

This unlocks conditional file content — e.g. a single template can gate a block on a sub-option using `{{#optKafkaConsumerExample}}…{{/optKafkaConsumerExample}}` instead of requiring a separate `FileContributionEntity` row per variation.

### DataSeeder — First-Startup Seeding

`src/main/java/com/menora/initializr/db/DataSeeder.java`

Runs at startup as a `SmartInitializingSingleton`. If all DB tables are empty it loads the dependency catalog from JSON manifests under `src/main/resources/catalog/` (backend) and `src/main/resources/catalog/frontend/` (frontend) and inserts them as DB records. This bootstraps the system; after seeding, records can be modified via the admin API without touching the filesystem.

**Catalog manifests** (DTOs in `db/seed/CatalogManifests.java`). The same five generic loaders read both the backend manifests under `catalog/` (stamped `ProjectKind.BACKEND`) and the frontend manifests under `catalog/frontend/` (stamped `ProjectKind.FRONTEND`) — each `load*(path, kind)` takes the manifest path and the kind to stamp on every row:

| Manifest | Replaces | Notes |
|----------|----------|-------|
| `dependencies.json` | `seedDependencyCatalog()` | Groups + entries; `compatibilityRange`/`starter` are plain fields. FE entries carry no Maven coords; the three React-19-sensitive design systems (`design-mui`/`design-chakra`/`design-mantine`) set `compatibilityRange: "[18.0.0,19.0.0)"` |
| `file-contributions.json` | common + per-dep file contributions | Each row points at its content file via `contentResource` (a classpath path under `static-configs/*` or `templates/*`); the content itself stays in that file. Small inline strings (FSD barrels, layer READMEs, `.env` templates) use the `content` field instead. `DELETE` rows have no `contentResource`. An optional `javaVersion` (backend) or `nodeVersion` (frontend) field pins a row to one runtime version — see *Version Gating* above. `application.yaml` base keeps `sortOrder: -1`, the `application.properties` `DELETE` keeps `sortOrder: 9999` |
| `build-customizations.json` | `seedBuildCustomizations()` / FE `feNpm`/`feVitePlugin`/`feNpmScript` | `type` = `ADD_DEPENDENCY` / `EXCLUDE_DEPENDENCY` / `ADD_REPOSITORY` (backend) or `ADD_NPM_DEPENDENCY` / `ADD_VITE_PLUGIN` / `ADD_NPM_SCRIPT` (frontend). FE rows reuse the Maven columns: npm dep = `mavenArtifactId` (package) + `version` (semver) + `scope` (`dev`/blank) + optional `subOptionId` gate; vite plugin = `mavenGroupId` (import path) + `mavenArtifactId` (binding) + `version` (call expr); npm script = `mavenArtifactId` (name) + `version` (command) |
| `sub-options.json` | `seedSubOptions()` / FE `feSubOption` | |
| `compatibility.json` | `seedCompatibilityRules()` / FE `feCompat` | one file per kind: `catalog/compatibility.json` (backend) and `catalog/frontend/compatibility.json` (FE) |

The loaders (`loadDependencyCatalog`, `loadFileContributions`, …) are generic — to change either catalog, edit the JSON, not Java. **Still seeded from Java helpers** in `DataSeeder`: starter/module templates, color palettes (`seedColorPalettes`), version definitions (`seedVersionsIfMissing`), and entity template sets (`templates/fullstack/*/manifest.json`). Color palettes and versions run in the pre-guard "if missing" path (idempotent per-row), so they are intentionally not folded into the all-or-nothing catalog load.

### Fullstack Generation — Two-Layer Reuse

`POST /starter-fullstack.zip` (`FullstackStarterController`) generates a `backend/` + `frontend/` pair from a list of user-defined entities. Both halves are **built on top of the standalone generators** rather than reimplementing them — entity scaffolding is the only fullstack-specific layer:

- **Backend** — runs through the standard Initializr `ProjectGenerationInvoker`, so `DynamicProjectGenerationConfiguration` applies the dependency catalog as usual; `FullstackProjectGenerationConfiguration` (registered in `spring.factories`, short-circuits when `EntityDefinitionContext.isEmpty()`) adds per-entity Java from the `spring-jpa-crud` entity template set.
- **Frontend** — `renderFrontend` first calls `FrontendProjectGenerator.renderInto(dir, desc)` to lay down the standalone FSD substrate (tooling configs, layer barrels + READMEs, `index.html`, `.gitignore`, dev `.env`/Vite proxy). It then deletes the substrate's `src/pages/home` and renders the `react-tailwind-crud` template set **as an overlay** — only the per-entity CRUD files + the fullstack-owned shared UI and Tailwind-v4 styling stack (`package.json`/`vite.config`/`index.css`/`tsconfig`/`main.tsx`/`App.tsx`), rendered last so it overwrites the substrate where paths collide. Per-entity templates are rendered with `EntityScaffoldContext` merged with `FrontendMustacheContext` (so they see both entity naming/field view-models and dep/palette/version flags).

So the FE template set is intentionally a thin **overlay**, not a full project — substrate files live in `catalog/frontend/*` and `templates/frontend/*`, edited once. (Known follow-up: the v4 styling stack stays overlay-owned because the standalone `style-tailwind` dep is still v3; unifying it would let the substrate own `package.json`/`vite.config` too. **Upgraded DBs** keep the pre-overlay fat template-set rows until re-seeded, so the overlay benefit applies to fresh DBs.)

#### Entity model — fields, constraints, relations

A request entity (`EntityDefinition`, validated/converted from the wire by `FullstackRequestValidator`) has **fields** and **relations**:

- **Fields** (`FieldDefinition`) — `type` (`FieldType`: STRING/LONG/INTEGER/BOOLEAN/LOCAL_DATE/LOCAL_DATE_TIME/BIG_DECIMAL/ENUM), `primaryKey`/`generated`, `required`, `unique`, `length` (STRING only), and constraints `min`/`max` (numeric only, min ≤ max), `pattern` (STRING regex, validated compilable), `email` (STRING). Constraints render as Bean Validation on the **DTO** (`@Min`/`@Max` on integral, `@DecimalMin`/`@DecimalMax` on `BigDecimal`, `@Pattern`/`@Email` on strings) gated on the `validation` starter via `hasValidation`, and as HTML input attributes (`min`/`max`/`pattern`/`type="email"`) in the form. The regex is escaped once (`patternEscaped`) for both Java and JS string literals.
- **Primary keys** — at least one PK; **composite keys** are supported (multiple `primaryKey` fields). A composite key renders a `@IdClass(<Entity>Id.class)` plus a **separate top-level** `<Entity>Id.java implements Serializable` key class (its own per-entity template `EntityId.java.mustache`, gated on `hasCompositePk` — same package as the entity, matching the standalone SQL wizard's `renderIdClass`; Lombok variant uses `@Data`/`@NoArgsConstructor`/`@AllArgsConstructor`). The repository/service/controller reference the key class directly (`{{keyClassName}}`) with a gated `import {{entityPackage}}.{{keyClassName}};`, the repository/service id type becomes the key class, and the controller addresses rows by ordered path segments `/{k1}/{k2}` (precomputed as `pkPath`). The frontend `useResource` `update`/`remove` accept an ordered key array and join it (`toPath`). A `generated` PK requires a **single** integral PK (composite + generated is rejected). `EntityScaffoldContext` exposes `pkField` (first PK, back-compat), `pkFields` (list), `hasCompositePk`, `keyClassName`, `pkType`, `pkPath`.
- **Relations** (`RelationDefinition`, `RelationType`) — v1 supports **`MANY_TO_ONE`** only (the FK-owning side); `ONE_TO_MANY`/`MANY_TO_MANY` in the wire are rejected. A relation may **not** target a composite-PK entity (a single `<field>Id` can't address it — rejected). Each has `fieldName`, `targetEntity` (must be another entity in the same request — validated in a second pass and canonicalized), and `required`. Rendered as `@ManyToOne`/`@JoinColumn` (entity), a `<field>Id` component + target-entity import + a stub-by-id in `toEntity()` (DTO), a copy-on-update line (service), and an FK **`<select>`** in the frontend form (populated from the target's list endpoint `/api/<targetPlural>` via the shared `useOptions` hook; option label = target's first non-PK string field if any, else the id). The relation column on the table still shows the raw FK id.
  - **Inverse `@OneToMany` collections** (opt-in `optScaffoldInverse`, derivation-only) — auto-derived from the owning `MANY_TO_ONE`s: the target parent gets a read-only `@OneToMany(mappedBy=…)` collection (no cascade), and the DTO/frontend surface a `<child>Count` (a count, never embedded child objects — avoids JSON recursion). Deferred: child-ID-list mode, explicit wire `ONE_TO_MANY`, and DDL FK import.

The Mustache view-model is built **only** in `EntityScaffoldContext`: `buildProjectContext` (project-wide, includes the `entities` list, a private `__entitySummaries` lookup so relations can resolve their target's PK type/name + plural-kebab + label field, and a `__inverseRelations` lookup), `buildEntityContext` (project-wide + one entity; also sets the per-entity `softDeleteApplicable` flag), and the per-field/-relation flags. Add new template variables here, nowhere else.

#### Entity template sets (`EntityTemplateSetEntity`)

Each set is a named bundle of `EntityTemplateFileEntity` rows (`perEntity` files render once per entity, others once). Sets are seeded from `templates/fullstack/<set>/manifest.json` by `DataSeeder.seedEntityTemplateSetsIfMissing()` (one explicit `seedEntityTemplateSet(...)` call per set). Current sets:

| Set key | Kind | Notes |
|---------|------|-------|
| `spring-jpa-crud` | BACKEND_JAVA | Default backend: Entity/Repository/DTO/Service/Controller + CORS |
| `spring-jpa-crud-lombok` | BACKEND_JAVA | Same, but Lombok `@Data`/`@NoArgsConstructor`/`@AllArgsConstructor` entities (Lombok is a `__common__` dep, so no extra pom wiring) |
| `react-tailwind-crud` | FRONTEND_REACT | Default frontend overlay (see above) |

The controller resolves `backendTemplateSet`/`frontendTemplateSet` and **kind-checks** them (400 on unknown/wrong-kind). A manifest file entry may set **`sourceSet`** to borrow its content from another set's directory — `spring-jpa-crud-lombok` authors only its `Entity.java.mustache` and reuses the rest from `spring-jpa-crud`.

#### Opt-in scaffolding (gated template files)

`EntityTemplateFileEntity.gatedBy` (column `gated_by`, migration `V16`) names a context flag; `FullstackRenderer` skips the file unless that flag is truthy. The flags come from the request `opts` map (`{ "scaffold": ["tests", "audit", …] }`) read as `optScaffold<Option>` — **set in both render contexts**: `FullstackProjectGenerationConfiguration` (backend) **and** `FullstackStarterController.renderFrontend` (frontend `projectCtx`). The two render paths do not share a context, so a frontend-affecting opt that is only set on the backend silently never fires — set it in both. `gatedBy` is carried through the admin export/import round-trip.

Shipped opts:

| `opts.scaffold` value | Flag | Effect |
|---|---|---|
| `tests` | `optScaffoldTests` | Per-entity `@WebMvcTest` controller test (gated whole file; `spring-boot-starter-test` auto-added) |
| `audit` | `optScaffoldAudit` | Entity gets `@CreatedDate`/`@LastModifiedDate` `Instant createdAt/updatedAt` + `@EntityListeners(AuditingEntityListener.class)`; a non-perEntity `JpaAuditingConfig` (`@EnableJpaAuditing`, gated whole file) is added; DTO + frontend table surface them read-only. Uses `data-jpa` (already a default dep) |
| `softDelete` | `optScaffoldSoftDelete` → per-entity `softDeleteApplicable` | Entity gets a `deleted` column + Hibernate `@SQLDelete`/`@SQLRestriction`; `service.delete` then soft-deletes and reads auto-filter. **Skipped for composite-PK entities** (single-column WHERE) via `softDeleteApplicable = optScaffoldSoftDelete && !hasCompositePk`; `deleted` is never exposed on the DTO. Deferred: multi-column `@SQLDelete` |
| `inverseCollections` | `optScaffoldInverse` | See inverse `@OneToMany` collections above |

Audit/soft-delete/inverse modify *existing* templates via `{{#optScaffold…}}` sections (not whole-file gates); they are mirrored in both `spring-jpa-crud` and `spring-jpa-crud-lombok` `Entity.java.mustache`. Remaining opt-in candidates (OpenAPI/Flyway/seed data) still pend a catalog-dependency change.

### Adding or Modifying a Dependency

The DB is the source of truth. Use the admin API at runtime, or edit the catalog manifests for the initial seed:

1. **New dependency** — POST to `/admin/dependency-groups` + `/admin/dependency-entries`
2. **New file to inject** — POST to `/admin/file-contributions` with `dependencyId`, `fileType`, `content`, `targetPath`
3. **New build customization** — POST to `/admin/build-customizations`
4. **Hot-reload** — POST to `/admin/refresh` (no restart needed)

For a permanent change that survives a fresh DB (e.g. new deployment), edit the relevant manifest under `src/main/resources/catalog/` (drop any new content file under `static-configs/`/`templates/` and reference it via `contentResource`). See the catalog-manifest table above.

### Sub-Options (Optional Per-Dependency Files)

Some dependencies expose sub-options selectable by the user (e.g. `consumer-example`, `producer-example` for Kafka). URL convention: `opts-{depId}=opt1,opt2`.

`InitializrWebConfiguration` (the `@Order(MIN_VALUE)` servlet filter) calls `ProjectOptionsContext.populate(request)` before generation and `clear()` after. `DynamicProjectGenerationConfiguration` checks `optionsContext.hasOption(depId, subOptionId)` before writing sub-option-gated files.

Sub-options are managed via `/admin/sub-options`.

### Dependency Catalog in Metadata

`DatabaseInitializrMetadataProvider` (`@Primary` bean via `MetadataProviderConfig`) loads the dependency catalog from the DB. Non-dependency metadata (Java versions, Boot versions, packaging, types) still comes from `application.yml`.

The provider caches the metadata. Call `POST /admin/refresh` to invalidate the cache after DB changes.

### Dependency Version Compatibility Ranges

Each `DependencyEntryEntity` has an optional `compatibilityRange` field (column: `compatibility_range`). When set, the Spring Initializr framework automatically:

- Excludes the dependency from `/metadata/client` responses when the selected Boot version falls outside the range
- Includes `"versionRange"` in the metadata JSON for clients to display

**Range syntax** (interval notation):
- `[3.2.0,4.0.0)` — Boot ≥ 3.2.0 and < 4.0.0 (most common)
- `3.2.0` — Boot ≥ 3.2.0 (open upper bound)
- `[3.2.0,3.3.0]` — inclusive on both ends

A blank/null range means the dependency is compatible with all Boot versions (default behavior).

Set via the admin UI (Dependencies tab → edit → Compatibility Range field) or in `catalog/dependencies.json` (the `compatibilityRange` field on an entry) for fresh-DB seeds. The range is validated by `dep.resolve()` at metadata-build time — a malformed range throws immediately on refresh.

### Frontend Compatibility Rules (REQUIRES / CONFLICTS / RECOMMENDS)

Inter-dependency rules live in `dependency_compatibility` and are tagged by `project_kind`. The endpoint `/metadata/compatibility?projectKind=FRONTEND` returns FE-scoped rules (omit the param to get every row). FE seed rules are in `DataSeeder.feCompat(...)` and cover design-system conflicts, state-mgmt conflicts, and `design-shadcn REQUIRES style-tailwind`.

**Server-side enforcement.** `FrontendCompatibilityResolver` runs inside `FrontendStarterController.buildDescription` after the React-version filter. It auto-adds REQUIRES targets (or drops the source if the target is missing from the catalog) and drops the later-selected dep in a CONFLICTS pair, both with warn logs. This is the safety net for direct API hits (curl, IntelliJ); the UI surfaces the same rules as banners via `useCompatibility('FRONTEND')` so users see the issue before clicking Generate. RECOMMENDS never alter the selection — they only render as suggestions.

### InitializrWebConfiguration

`src/main/java/com/menora/initializr/config/InitializrWebConfiguration.java`

A `@Component`, `@Order(Integer.MIN_VALUE)` servlet filter (extends `OncePerRequestFilter`) that runs before all other filters and wraps every request with three responsibilities:

1. **`configurationFileFormat` default** — injects `configurationFileFormat=properties` when absent
2. **`X-Forwarded-Port` sanitization** — returns empty string if absent/unparseable/`"null"`
3. **Sub-option context** — calls `optionsContext.populate(request)` before and `clear()` after the filter chain

### Test Infrastructure

Tests use `src/test/resources/application.properties` which configures an in-memory H2 (`ddl-auto: validate`, schema managed by Flyway; `admin.password=test`). `DataSeeder` runs automatically at test startup and seeds the DB from the catalog manifests, so tests exercise the full DB-driven pipeline.

`src/test/java/com/menora/initializr/TestInvokerConfiguration.java` — a `@TestConfiguration` that provides a `ProjectGenerationInvoker<ProjectRequest>` bean. Test classes import it via `@Import(TestInvokerConfiguration.class)` to invoke project generation directly without HTTP.

**Coverage:** `jacoco-maven-plugin` runs during `mvn test` (no gate) → report at `target/site/jacoco/index.html`.

**Seeder / admin tests:**
- `db/DataSeederTest` — characterization test: asserts the observable seeded catalog (group/entry/file-contribution counts, representative content, compatibility/sub-option/template/palette/version counts). The regression oracle for catalog-manifest changes — together with `ProjectGenerationIntegrationTests` (actual generated file bytes) it pins seeding behavior.
- `admin/AdminApiIntegrationTests` (`MockMvc`) — auth gate (login, 401), validation (400) and orphan-conflict (409) error paths, `/admin/refresh`.
- `admin/ConfigurationExportImportServiceTest` — export → import round-trip preserves row counts and content (runs `@Transactional` so the destructive import rolls back).

**Test coverage summary (`ProjectGenerationIntegrationTests`):**
- `metadataEndpointReturnsOk` — HTTP smoke test; checks `kafka` and `rqueue` appear in metadata
- `generatedProjectContainsArtifactoryRepo` — verifies Artifactory repos in generated `pom.xml`
- `generatedProjectContainsVersionDockerfileAndK8s` — checks VERSION content, Dockerfile artifact ID substitution, k8s/values.yaml group ID substitution
- `generatedProjectContainsLog4j2` — verifies `log4j2-spring.xml` present, `logback-spring.xml` absent, `spring-boot-starter-log4j2` in pom
- `generatedProjectContainsEditorconfig` — checks `.editorconfig` present, `application.properties` absent
- `kafkaDependencyInjectsConfigFiles` — checks `application.yaml` contains `bootstrap-servers`, `KafkaConfig.java` present
- `withoutKafkaDependencyNoKafkaFiles` — verifies kafka files absent when kafka not selected
- `securityDependencyInjectsSecurityConfig` — checks `application.yaml` + `SecurityConfig.java`
- `jpaDependencyInjectsJpaConfig` — checks `application.yaml` + `JpaConfig.java`
- `actuatorDependencyInjectsObservabilityConfig` — checks `application.yaml` contains `management`
- `rqueueDependencyInjectsRqueueConfig` — checks `application.yaml` + `RqueueConfig.java`
- `multipleDependenciesInjectAllConfigs` — combines kafka + security + jpa + actuator; spot-checks all files

### Key Version Properties

Both places must stay in sync when changing Spring Boot version:
- `pom.xml` → `<parent><version>`
- `application.yml` → `initializr.boot-versions[].id` and `name`

Initializr framework version is controlled solely by `<spring-initializr.version>` in `pom.xml`.

### Artifactory URL

The URL `https://repo.menora.co.il/artifactory/libs-release` appears in three places that must be kept in sync:
1. `pom.xml` `<repositories>` — where this app resolves its own dependencies
2. `application.yml` `initializr.env.repositories` — exposed in metadata to clients (IntelliJ)
3. `DataSeeder.seedBuildCustomizations()` — what is written into generated `pom.xml` files (as a `BuildCustomizationEntity`)
