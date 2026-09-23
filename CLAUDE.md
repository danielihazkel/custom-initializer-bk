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
- **`dynamicDeleteContributor`** (`ProjectContributor`, `@Order(LOWEST_PRECEDENCE)`) — runs after everything else to delete files registered with `DELETE` type (e.g. `application.properties` written by the framework). `DELETE` is handled **only** here, never in the write pass, and it applies the same `isGatedOut` sub-option/`javaVersion` gating and the same `resolveTargetPath` (`{{packagePath}}`) resolution as the write pass — the two share one predicate so they can't drift
- **`dynamicBuildCustomizer`** (`BuildCustomizer<MavenBuild>`) — applies all `BuildCustomizationEntity` records (add dependency, exclude dependency, add repository). Like the file contributor, it skips a record whose `subOptionId` is set unless that sub-option was selected (`optionsContext.hasOption(depId, subOptionId)`) — e.g. `mapstruct`'s processor deps, or a database dep's secondary-datasource config, are added only with the matching sub-option

### FileContributionEntity — File Types

| Type | Behavior |
|------|----------|
| `STATIC_COPY` | Writes content verbatim to target path |
| `YAML_MERGE` | Deep-merges YAML into the target file (creates if absent). Blank / comments-only content loads as `null` in SnakeYAML and is treated as an empty map, so such a row is a no-op rather than an NPE |
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

The table above is the backend-dependency subset. For the **complete** variable catalog across all three render contexts (backend dependency, fullstack entity, frontend) — every key, where it's derived (with file:line), the path-vs-content substitution rules, and authoring walkthroughs (add a template, conditional/sub-option gating, version gating) — see [`docs/MUSTACHE_GUIDE.md`](docs/MUSTACHE_GUIDE.md).

### DataSeeder — First-Startup Seeding

`src/main/java/com/menora/initializr/db/DataSeeder.java`

Runs at startup as a `SmartInitializingSingleton`. If all DB tables are empty it loads the dependency catalog from JSON manifests under `src/main/resources/catalog/` (backend) and `src/main/resources/catalog/frontend/` (frontend) and inserts them as DB records. This bootstraps the system; after seeding, records can be modified via the admin API without touching the filesystem.

**Catalog manifests** (DTOs in `db/seed/CatalogManifests.java`). The same five generic loaders read both the backend manifests under `catalog/` (stamped `ProjectKind.BACKEND`) and the frontend manifests under `catalog/frontend/` (stamped `ProjectKind.FRONTEND`) — each `load*(path, kind)` takes the manifest path and the kind to stamp on every row:

| Manifest | Replaces | Notes |
|----------|----------|-------|
| `dependencies.json` | `seedDependencyCatalog()` | Groups + entries; `compatibilityRange`/`starter` are plain fields. FE entries carry no Maven coords; the three React-19-sensitive design systems (`design-mui`/`design-chakra`/`design-mantine`) set `compatibilityRange: "[18.0.0,19.0.0)"`. `design-menora-digital` (the Menora Mivtachim customer-site brand system, see *Menora Digital* below) is plain CSS and carries no range |
| `file-contributions.json` | common + per-dep file contributions | Each row points at its content file via `contentResource` (a classpath path under `static-configs/*` or `templates/*`); the content itself stays in that file. Small inline strings (FSD barrels, layer READMEs, `.env` templates) use the `content` field instead. `DELETE` rows have no `contentResource`. An optional `javaVersion` (backend) or `nodeVersion` (frontend) field pins a row to one runtime version — see *Version Gating* above. `application.yaml` base keeps `sortOrder: -1`, the `application.properties` `DELETE` keeps `sortOrder: 9999` |
| `build-customizations.json` | `seedBuildCustomizations()` / FE `feNpm`/`feVitePlugin`/`feNpmScript` | `type` = `ADD_DEPENDENCY` / `EXCLUDE_DEPENDENCY` / `ADD_REPOSITORY` (backend) or `ADD_NPM_DEPENDENCY` / `ADD_VITE_PLUGIN` / `ADD_NPM_SCRIPT` (frontend). FE rows reuse the Maven columns: npm dep = `mavenArtifactId` (package) + `version` (semver) + `scope` (`dev`/blank) + optional `subOptionId` gate; vite plugin = `mavenGroupId` (import path) + `mavenArtifactId` (binding) + `version` (call expr); npm script = `mavenArtifactId` (name) + `version` (command) |
| `sub-options.json` | `seedSubOptions()` / FE `feSubOption` | |
| `compatibility.json` | `seedCompatibilityRules()` / FE `feCompat` | one file per kind: `catalog/compatibility.json` (backend) and `catalog/frontend/compatibility.json` (FE) |

The loaders (`loadDependencyCatalog`, `loadFileContributions`, …) are generic — to change either catalog, edit the JSON, not Java. **Still seeded from Java helpers** in `DataSeeder`: starter/module templates, color palettes (`seedColorPalettes`), version definitions (`seedVersionsIfMissing`), and entity template sets (`templates/fullstack/*/manifest.json`). Departments and fullstack examples (`catalog/fullstack-examples.json`) are table-scoped seeds in the same pre-guard path. Color palettes and versions run in the pre-guard "if missing" path (idempotent per-row), so they are intentionally not folded into the all-or-nothing catalog load.

### Fullstack Generation — Two-Layer Reuse

`POST /starter-fullstack.zip` (`FullstackStarterController`) generates a `backend/` + `frontend/` pair from a list of user-defined entities. Both halves are **built on top of the standalone generators** rather than reimplementing them — entity scaffolding is the only fullstack-specific layer:

- **Backend** — runs through the standard Initializr `ProjectGenerationInvoker`, so `DynamicProjectGenerationConfiguration` applies the dependency catalog as usual; `FullstackProjectGenerationConfiguration` (registered in `spring.factories`, short-circuits when `EntityDefinitionContext.isEmpty()`) adds per-entity Java from the `spring-jpa-crud` entity template set.
- **Frontend** — `renderFrontend` first calls `FrontendProjectGenerator.renderInto(dir, desc)` to lay down the standalone FSD substrate (tooling configs, layer barrels + READMEs, `index.html`, `.gitignore`, dev `.env`/Vite proxy). It then deletes the substrate's `src/pages/home` and renders the `react-tailwind-crud` template set **as an overlay** — only the per-entity CRUD files + the fullstack-owned shared UI and Tailwind-v4 styling stack (`package.json`/`vite.config`/`index.css`/`tsconfig`/`main.tsx`/`App.tsx`), rendered last so it overwrites the substrate where paths collide. Per-entity templates are rendered with `EntityScaffoldContext` merged with `FrontendMustacheContext` (so they see both entity naming/field view-models and dep/palette/version flags).

So the FE template set is intentionally a thin **overlay**, not a full project — substrate files live in `catalog/frontend/*` and `templates/frontend/*`, edited once. (Known follow-up: the v4 styling stack stays overlay-owned because the standalone `style-tailwind` dep is still v3; unifying it would let the substrate own `package.json`/`vite.config` too. **Upgraded DBs** keep the pre-overlay fat template-set rows until re-seeded, so the overlay benefit applies to fresh DBs.) Because the overlay overwrites the substrate's `package.json`, `renderFrontend` finishes with `FrontendProjectGenerator.mergeSubstrateTooling(dir, deps)`, which folds the `__common__` npm build customizations (eslint / prettier / husky / lint-staged deps and scripts) back into the overlay file **without overriding its pins** (`PackageJsonBuilder.merge`) — otherwise the `eslint.config.js` / `.husky/pre-commit` the substrate wrote reference packages that are never installed. The overlay `package.json.mustache` reads `{{reactPackageVersion}}` / `{{reactTypesVersion}}` (and `{{nodeVersion}}` for `engines`), so it follows the selected React version.

**Frontend ↔ backend wiring.** The generated frontend always calls same-origin `/api/...` — `VITE_API_BASE_URL` is empty in both overlay `.env` files. In dev the overlay's `vite.config.ts.mustache` (a MUSTACHE row, so it keeps the substrate's `basePath`/`apiBaseUrl`/`host: true` instead of clobbering them) proxies `/api` to the backend; in production the shared `static-configs/frontend/common/nginx.conf` has a `location /api/ { proxy_pass ${API_UPSTREAM}; }` block that `entrypoint.sh` substitutes at container start (`-e API_UPSTREAM=http://backend:8080`, default `http://localhost:8080`). The generated `CorsConfig` is therefore **off by default** and reads `app.cors.allowed-origins` (comma-separated) for the only case that needs CORS — a frontend built with `VITE_API_BASE_URL` pointing at the API directly. The API client rejects a non-JSON `content-type` so a missing proxy fails with a pointed message rather than a `SyntaxError` on `index.html`. Pinned by `generatesBackendAndFrontendForTwoEntities`.

**Generated API error contract.** Every scaffolded endpoint answers errors as RFC-7807 `ProblemDetail` (`application/problem+json`) from one non-perEntity `web/ApiExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`, so it beats the `validation` dep's `ValidationExceptionHandler` for the exceptions it declares): 404 for `web/ResourceNotFoundException` (thrown by `Service.findById`), 409 for `web/ResourceConflictException` (a `create` with a client-supplied key that `existsById` — `save()` would otherwise silently merge over the row) and for `DataIntegrityViolationException` (detail classified from the driver message: unique → "already exists", FK, not-null), 400 for bean validation with the per-field map under the `errors` property (the FE `api-client` maps it to `ApiError.fieldErrors`), unreadable bodies, type mismatches, `PropertyReferenceException` and any service `IllegalArgumentException`. Controllers carry **no** local `@ExceptionHandler`s. `?sort=` is whitelisted per controller (`SORTABLE` = every scalar DTO column + audit columns; unknown → 400) and the default sort is **all** PK columns; text search escapes `%`/`_`/`\` (`escapeLike`, `ESCAPE '\'`) and lowercases with `Locale.ROOT`. The slice test uses `@MockitoBean` from Boot 3.4 (`useMockitoBean`). Pinned by `fullstackEndpoint_hardensGeneratedApi`.

**Generated frontend forms and data hook.** Each feature gets `model/validate.ts` (`EntityValidate.ts.mustache`, re-exported by the feature barrel) with `validate<Entity>(value)`, derived from the same field metadata as the DTO's Bean Validation (required, `length`, `min`/`max`, `pattern` anchored as `^(?:…)$`, email, required relation FKs; a generated PK is never checked, a client-supplied one is required; the `blank` helper is emitted only when `hasBlankChecks`, because the generated lint forbids unused vars), and `EntityPage.save` runs it before calling the API, showing messages under the fields and clearing a field's message as soon as it is edited. `FormDrawer` is a real `<form noValidate>` (Enter submits, Save is `type="submit"`). `useResource.reload` drops out-of-order responses via a request counter, and `Table`/`CardGrid` show the skeleton only on the first load, keeping (dimmed) rows during a refetch. Pinned by `fullstackEndpoint_formValidatesClientSideAndDataHookGuardsStaleResponses`.

#### Entity model — fields, constraints, relations

A request entity (`EntityDefinition`, validated/converted from the wire by `FullstackRequestValidator`) has **fields** and **relations**:

- **Fields** (`FieldDefinition`) — `type` (`FieldType`: STRING/TEXT/LONG/INTEGER/BOOLEAN/LOCAL_DATE/LOCAL_DATE_TIME/BIG_DECIMAL/UUID/ENUM), `primaryKey`/`generated`, `required`, `unique`, `length` (STRING only), and constraints `min`/`max` (`BigDecimal` on the wire and in `FieldDefinition`; numeric only, min ≤ max; whole numbers on LONG/INTEGER — and inside the int range for INTEGER — while BIG_DECIMAL takes decimals. `EntityScaffoldContext` renders both as strings so `@Min(0)`, `@DecimalMin(value = "0.5")`, the form's `min="0.5"` and the TS validator splice the same literal; the form adds `step="any"` on BigDecimal), `pattern` (STRING regex, validated compilable), `email` (STRING). **TEXT** is long text → a SQL `TEXT` column (`columnDefinition`, not `@Lob`, so search's `lower()`/`LIKE` works) rendered as a `<textarea>`; it joins `stringFields` so it stays searchable. **UUID** maps to the fully-qualified `java.util.UUID` (null import — the FQN keeps the Repository/Service compiling without a per-field import block) and may be `generated` (→ Hibernate `@UuidGenerator` instead of `IDENTITY`). Constraints render as Bean Validation on the **DTO** (`@Min`/`@Max` on integral, `@DecimalMin`/`@DecimalMax` on `BigDecimal`, `@Pattern`/`@Email` on strings) gated on the `validation` starter via `hasValidation`, and as HTML input attributes (`min`/`max`/`pattern`/`type="email"`) in the form. The regex is escaped once (`patternEscaped`) for both Java and JS string literals. **`defaultValue`** (String, optional) is type-checked in the validator per `FieldType` (integral/decimal parse, `true`/`false`, ISO date/date-time, `UUID.fromString`, ENUM ∈ `enumValues`, STRING/TEXT within `length`; rejected on a `generated` PK; errors name the field) and canonicalized. `fieldViewModel` exposes `hasDefault`/`defaultJava`/`defaultTs`: the entity field gets a Java initializer (`private String status = "draft";`, both backend sets), the DTO's `toEntity` skips a null so an omitted field keeps it, the FE `EntityPage` pre-fills `newDefaults()` on New (`hasFieldDefaults`), and `seedExpression` uses it for non-key, non-unique fields. Pinned by `fullstackEndpoint_rendersFieldDefaults`. **`enumLabels`** (`Map<String,String>`, ENUM only, optional) gives a constant its display label (`{"OPEN": "פתוח"}`; keys matched case-insensitively, canonicalized to the upper-cased constant; blank/>80-char/unknown-key → 400 naming the field and key). `fieldViewModel` puts `label` (custom, else `humanizeConstant`: `IN_PROGRESS` → "In progress") and `labelTs` (single-quote-escaped) on every `enumValues` entry plus a field-level `hasEnumLabels`; the entity view adds `breakdownIsEnum`/`breakdownEnumTypeName`/`kanbanEnumTypeName` and each `kanbanColumns` lane a `labelExpr` (a quoted literal, or `t('trueLabel')`/`t('falseLabel')` for boolean lanes). The generated `types.ts` emits `<Enum>Labels`/`<Enum>Options` next to the union type, and the form/filter bar/bulk edit/kanban/table cell/detail/dashboard chart all read them; the Java enum, CSV export and demo data keep the raw constant. Pinned by `fullstackEndpoint_rendersEnumLabels`.
- **Primary keys** — at least one PK; **composite keys** are supported (multiple `primaryKey` fields). A composite key renders a `@IdClass(<Entity>Id.class)` plus a **separate top-level** `<Entity>Id.java implements Serializable` key class (its own per-entity template `EntityId.java.mustache`, gated on `hasCompositePk` — same package as the entity, matching the standalone SQL wizard's `renderIdClass`; Lombok variant uses `@Data`/`@NoArgsConstructor`/`@AllArgsConstructor`). The repository/service/controller reference the key class directly (`{{keyClassName}}`) with a gated `import {{entityPackage}}.{{keyClassName}};`, the repository/service id type becomes the key class, and the controller addresses rows by ordered path segments `/{k1}/{k2}` (precomputed as `pkPath`). The frontend `useResource` `update`/`remove` accept an ordered key array and join it (`toPath`). A `generated` PK requires a **single** integral PK (composite + generated is rejected). `EntityScaffoldContext` exposes `pkField` (first PK, back-compat), `pkFields` (list), `hasCompositePk`, `keyClassName`, `pkType`, `pkPath`.
- **Relations** (`RelationDefinition`, `RelationType`) — v1 supports **`MANY_TO_ONE`** only (the FK-owning side); `ONE_TO_MANY`/`MANY_TO_MANY` in the wire are rejected. A relation may **not** target a composite-PK entity (a single `<field>Id` can't address it — rejected). Each has `fieldName`, `targetEntity` (must be another entity in the same request — validated in a second pass and canonicalized), and `required`. Rendered as `@ManyToOne`/`@JoinColumn` (entity), a `<field>Id` component + target-entity import + a stub-by-id in `toEntity()` (DTO), a copy-on-update line (service), and an FK **`<select>`** in the frontend form (populated from the target's list endpoint `/api/<targetPlural>` via the shared `useOptions` hook; option label = target's first non-PK string field if any, else the id). When the target has a label field the entity also gets a read-only `@Formula` column `<field>Label` (a per-row subselect of the target's label column — open-in-view is off, so the DTO cannot touch the lazy association), exposed on the DTO and shown by the table/detail views (falling back to `#id`), and the CSV export. Every `MANY_TO_ONE` also becomes a **relation filter** (`filterFields` entry with `isRelationFilter`, param `<field>Id`, `cb.equal(root.get(field).get(pk), …)`); the FE `FilterBar` renders it as a `<select>` fed by `useOptions` from the target's list endpoint. Because relations count as filters, an entity with a relation always has `hasFilters` (and the 3-arg `findAll`).
  - **Inverse `@OneToMany` collections** (opt-in `optScaffoldInverse`, derivation-only) — auto-derived from the owning `MANY_TO_ONE`s: the target parent gets a read-only `@OneToMany(mappedBy=…)` collection (no cascade), and the DTO/frontend surface a `<child>Count` (a count, never embedded child objects — avoids JSON recursion). The count is a Hibernate **`@Formula`** subselect column (`(select count(*) from <child_table> c where c.<fk_column> = <pk_column>)`, from the inverse view-model's `childTableRef`/`childJoinColumn`), **not** `collection.size()`: the DTO is built in the controller after the service transaction ended and `open-in-view` is off, so reading the lazy collection there threw `LazyInitializationException` on every `GET` of a parent entity. Soft-deleted children are still counted. Deferred: child-ID-list mode, explicit wire `ONE_TO_MANY`, and DDL FK import.

The Mustache view-model is built **only** in `EntityScaffoldContext`: `buildProjectContext` (project-wide, includes the `entities` list, a private `__entitySummaries` lookup so relations can resolve their target's PK type/name + plural-kebab + label field, and a `__inverseRelations` lookup), `buildEntityContext` (project-wide + one entity; also sets the per-entity `softDeleteApplicable`/`auditApplicable`/`bulkDeleteApplicable`/`bulkUpdateApplicable`/`bulkSelectApplicable` flags, the list-view flags `viewTable`/`viewCards`/`viewKanban`/`viewCalendar` + `hasViewToggle`/`initialView`/`viewModeType`, and `hasBreakdown`/`breakdownField`/`breakdownLabel` for the dashboard chart), and the per-field/-relation flags. Add new template variables here, nowhere else.

#### Entity template sets (`EntityTemplateSetEntity`)

Each set is a named bundle of `EntityTemplateFileEntity` rows (`perEntity` files render once per entity, others once). Sets are seeded from `templates/fullstack/<set>/manifest.json` by `DataSeeder.seedEntityTemplateSetsIfMissing()` (one explicit `seedEntityTemplateSet(...)` call per set). Current sets:

| Set key | Kind | Notes |
|---------|------|-------|
| `spring-jpa-crud` | BACKEND_JAVA | Default backend: Entity/Repository/DTO/Service/Controller + CORS |
| `spring-jpa-crud-lombok` | BACKEND_JAVA | Same, but Lombok `@Data`/`@NoArgsConstructor`/`@AllArgsConstructor` entities (Lombok is a `__common__` dep, so no extra pom wiring) |
| `react-tailwind-crud` | FRONTEND_REACT | Default frontend overlay (see above) |
| `react-menora-digital-crud` | FRONTEND_REACT | Same CRUD app re-skinned with the Menora Digital design system (`designSystem: MENORA_DIGITAL`), built from the `@shared/ui/menora` ports. Authors `index.css.mustache` (Tailwind `@theme` re-pointed to the brand tokens), `App.tsx.mustache` (white top bar with `NavLinks` + `ThemeToggle`, the `Footer` band — **no ChatLauncher**: that is the marketing site's contact tab), `DashboardPage.tsx.mustache` (`Hero` with the yellow full stop and **no CTA**, then an `ActionPanel` with one yellow `ActionDisc` per entity, stat/chart cards on `.mn-panel`), `main.tsx`, `package.json.mustache`, the Menora-skinned shared UI (`Table.tsx` wrapping the `Table` port + `SearchField` + `Chip` cells + a `DropdownMenu` row-action menu, `CardGrid`, `EmptyState`, `FilterBar`, `FormDrawer`/`DetailDrawer`/`ConfirmDialog` with `Button`-port footers, `Badge` → `Chip`) and `EntityPage.tsx.mustache` (`SectionHeader` + a toolbar with `Tabs` view switcher, count `Chip`, outlined Export and the single primary New pill; columns carry `numeric`/`chip`). Everything else (Kanban/Calendar views, Field, Skeleton, Alert, useTheme, per-entity form/detail/validate/types/hooks) is borrowed from `react-tailwind-crud` via `sourceSet`; the path set is identical |

The request may also carry `dashboardTitle`/`dashboardOverview` (blank → the dashboard template's built-in heading/blurb) and `colorPalette`. The controller resolves `backendTemplateSet`/`frontendTemplateSet` and **kind-checks** them (400 on unknown/wrong-kind). A manifest file entry may set **`sourceSet`** to borrow its content from another set's directory — `spring-jpa-crud-lombok` authors only its `Entity.java.mustache` and reuses the rest from `spring-jpa-crud`; `react-menora-digital-crud` likewise borrows 27 of its 41 files from `react-tailwind-crud` (it authors the Menora-skinned shared UI and entity page). Because borrowed content is copied at seed time, an edit to a shared `react-tailwind-crud` component reaches both frontend sets on the next fresh seed.

**Semantic action tokens.** The overlay's primary buttons (New / Save / Edit / empty-state CTA) use `bg-primary text-on-primary hover:bg-primary-deep`, not `bg-brand text-white`. `react-tailwind-crud`'s `index.css.mustache` aliases `--color-primary` to the palette primary with white text (so it looks exactly as before); a variant set re-points the three tokens to re-skin every action button without forking the shared components — Menora Digital maps them to yellow `#ffc700` with ink `#37374e` text. `bg-brand` remains the *identity* colour (view toggles, filter badge, charts, nav highlight).

**Menora Digital in fullstack.** `FullstackStarterController.renderFrontend` adds the standalone `design-menora-digital` dep to the substrate description when the selected frontend set is tagged `MENORA_DIGITAL` — that single gate lays down `src/shared/ui/menora/` (tokens, `.mn-*` classes, the React component ports), `public/menora-mivtachim-logo.png` and `@fontsource/assistant`, and exposes `hasDesignMenoraDigital` to the overlay templates. The set's own `index.css`/`App.tsx` then overwrite the substrate's, and `src/pages/home` is deleted as for every set. The Menora set ignores the palette for brand colours (they are the fixed design-system tokens) but still takes `success`/`danger` from the palette's accent/error. **Yellow budget** (canvas rule "one yellow fill per view"): the dashboard spends it on the ActionPanel discs (the Hero has no CTA and there is no chat tab), the entity page on the one primary New pill (table cells only ever hold text Buttons / a `DropdownMenu`; status values are lavender `Chip`s at weight 600/400 — the palette has no red/green); drawers are their own layer, so Save/Edit stay primary. Pinned by `fullstackEndpoint_menoraDigitalSetReskinsTheFrontend` and built for real by `GeneratedFrontendBuildSmokeTests.fullstackFrontendMenora*InstallsAndBuilds`.

#### Opt-in scaffolding (gated template files)

`EntityTemplateFileEntity.gatedBy` (column `gated_by`, migration `V16`) names a context flag; `FullstackRenderer` skips the file unless that flag is truthy. The flags come from the request `opts` map (`{ "scaffold": ["tests", "audit", …] }`) read as `optScaffold<Option>` — **set in both render contexts**: `FullstackProjectGenerationConfiguration` (backend) **and** `FullstackStarterController.renderFrontend` (frontend `projectCtx`). The two render paths do not share a context, so a frontend-affecting opt that is only set on the backend silently never fires — set it in both. `gatedBy` is carried through the admin export/import round-trip.

Shipped opts:

| `opts.scaffold` value | Flag | Effect |
|---|---|---|
| `tests` | `optScaffoldTests` | Per-entity `@WebMvcTest` controller test (gated whole file; `spring-boot-starter-test` auto-added). The mocked `findAll` carries two or three `any()` matchers following `hasFilters`, because the service overload changes shape with it — a filterable entity mocked with two matchers does not compile. The mocked page is `new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)`: the single-arg constructor carries `Pageable.unpaged()`, which Jackson cannot serialize, so the slice test would 500 |
| `audit` | `optScaffoldAudit` | Entity gets `@CreatedDate`/`@LastModifiedDate` `Instant createdAt/updatedAt` + `@EntityListeners(AuditingEntityListener.class)`; a non-perEntity `JpaAuditingConfig` (`@EnableJpaAuditing`, gated whole file) is added; DTO + frontend table surface them read-only. Uses `data-jpa` (already a default dep) |
| `softDelete` | `optScaffoldSoftDelete` → per-entity `softDeleteApplicable` | Entity gets a `deleted` column + Hibernate `@SQLDelete`/`@SQLRestriction`; `service.delete` then soft-deletes and reads auto-filter. **Skipped for composite-PK entities** (single-column WHERE) via `softDeleteApplicable = optScaffoldSoftDelete && !hasCompositePk`; `deleted` is never exposed on the DTO. Also adds `POST /api/x/{id}/restore` (`Repository.restore`: a **native** `UPDATE … SET deleted = false`, because `@SQLRestriction` hides the row from every JPQL query; `Service.restore` 404s when the id does not exist) and the FE delete toast wires its **Undo** to it — without soft delete the toast offers no Undo at all (re-creating would hand out a new id). Deferred: multi-column `@SQLDelete` |
| `inverseCollections` | `optScaffoldInverse` | See inverse `@OneToMany` collections above |
| `openapi` | `optScaffoldOpenApi` | Controller gets springdoc `@Tag`/`@Operation` annotations (backend-only). The `openapi` dep (`springdoc-openapi-starter-webmvc-ui`, already in the catalog) is **force-added** to the build in `FullstackStarterController.buildArtifacts` when this opt is set — analogous to `tests` relying on `spring-boot-starter-test` |
| `secured` | `optScaffoldSecured` | Controller CRUD methods get **commented-out** `@RequiresPermission` hints (reads → `Constants.USER`, writes → `Constants.ADMIN`) plus commented `security.*` imports and a short how-to-enable note — the user uncomments per-endpoint to enforce. Emitted commented so the default project compiles with no unused imports / no enforcement. The flag still **requires an ldap-auth variant on the build** — either `ldap-auth` (direct LDAP bind) or `ldap-auth-rest` (groups via REST); the gate accepts `containsKey("ldap-auth") || containsKey("ldap-auth-rest")` — no point scaffolding hints that reference security classes that aren't present; no-ops cleanly if neither is selected |
| `csvExport` | `optScaffoldCsvExport` | Controller gets a `GET /api/x/export.csv` endpoint (RFC-4180 cells, UTF-8 BOM, honors `q`/filters/sort, ignores pagination; **streamed** as a `StreamingResponseBody` in 500-row chunks so memory is bounded) and the FE `EntityPage` gets an **Export** button wired to `useResource.exportCsv` (downloads via the `api.download` blob helper). No extra dependency |
| `bulkDelete` | `optScaffoldBulkDelete` → per-entity `bulkDeleteApplicable` | Controller gets `DELETE /api/x/bulk` (`@RequestBody List<id>`) + `service.deleteAll`; the FE `Table` gains row-selection checkboxes and a "Delete selected" bar. **Single-PK writable entities only** (`bulkDeleteApplicable = optScaffoldBulkDelete && !hasCompositePk && mutable`); soft-delete-aware (`deleteAllById` triggers `@SQLDelete`). No extra dependency |
| `bulkUpdate` | `optScaffoldBulkUpdate` → per-entity `bulkUpdateApplicable` (+ `bulkSelectApplicable` = bulkDelete ∨ bulkUpdate) | Controller gets `PATCH /api/x/bulk` (`{ids, field, value}`) + `service.updateMany` with a whitelist `switch` over `bulkUpdatableFields` (non-PK, non-read-only scalars, each with a `bulkInputKind` for the FE control); the FE table's selection bar gains a field picker + value input + Apply. **Single-PK writable entities with ≥1 editable field only**. No extra dependency |
| `seedData` | `optScaffoldSeedData` | Backend-only: a non-perEntity `config/DemoDataLoader` (`CommandLineRunner`, `@ConditionalOnProperty("app.demo-data.enabled", matchIfMissing = true)`) inserts 8 rows per writable entity on first start when every seeded table is empty. `EntityScaffoldContext` supplies `seedEntities` (parents before children via `buildSeedOrder`; read-only/view entities skipped; each relation carries `targetSeeded`, false for self-references and cycle back-edges) and a per-field `seedExpr` (`seedExpression`: strings/keys carry the row number so unique columns never clash, integral values cycle inside `min`/`max`, enums cycle their constants, emails become `user<i>@example.com`). Set the opt in `FullstackProjectGenerationConfiguration` only |
| `rtl` | (not an `optScaffold*` flag) | Frontend-only: `FrontendProjectDescription.setRtl(true)` → `dir="rtl"`/`lang="he"` on `index.html`, `isRtl` in the FE context |
| *(request field `locale`, not an opt)* | `locale` / `isHebrew` (FE context only) | Frontend-only chrome-string language, `en` (default) or `he`; anything else is a 400. The overlay's `i18n-strings.ts.mustache` → `src/shared/i18n/strings.ts` ships **both** tables and picks one on `isHebrew`; every chrome literal in the overlay (shell, dashboard, list page, shared UI, form hint, validator messages) goes through `t('key')` / `t('key', { x })` (`{x}` placeholders, so Hebrew word order works), and `LOCALE` feeds `Intl`/`toLocale*String` (`CalendarView` derives weekday/month names from it). Field/entity labels stay model-driven. Independent of `rtl` — the generator UI turns both on for the Menora Digital set. Imports are gated where use is conditional (`hasValidationChecks` for `validate.ts`, `formUsesStrings` for the form, `auditApplicable` for the detail view) because the generated lint forbids unused imports. Pinned by `fullstackEndpoint_defaultsChromeStringsToEnglish` / `_hebrewLocaleRendersHebrewChrome` / `_rejectsUnknownLocale`; `GeneratedFrontendBuildSmokeTests` type-checks both locales |

**Per-entity overrides.** `EntityDefinitionDto.opts` (`Map<String, Boolean>`, carried into `EntityDefinition.opts`) lets one entity opt out of — or into — a project-wide flag: `{ "name": "Note", "opts": { "audit": false, "csvExport": false } }`. Known keys are exactly `EntityScaffoldContext.SCAFFOLD_OPT_FLAGS` (`audit`, `softDelete`, `csvExport`, `bulkDelete`, `bulkUpdate`, `tests`); anything else is a 400 (`Unknown scaffold option 'x' on entity Y`). `buildEntityContext` resolves `override ?? projectOpt` and stores it under the same `optScaffold<X>` key in the **per-entity** context, so per-entity templates, per-entity `gatedBy` files (`FullstackRenderer` gates those against the per-entity context — that is how `tests` works per entity) and the derived `*Applicable` flags all see the resolved value, on both render paths (backend config and `renderFrontend` both feed `FullstackRenderer.render`, which builds the per-entity context last). Project-level files (`JpaAuditingConfig`) and the shared FE components (the `Table` selection bar for `bulkDelete`/`bulkUpdate`) still key off the project flag — an opted-out entity page simply never passes the selection props. Pinned by `fullstackEndpoint_perEntityOptOverrides`.

**Adding an opt** touches three places: `FullstackProjectGenerationConfiguration` (backend flag), `FullstackStarterController.renderFrontend` (frontend flag, if any FE template reads it), **and** `SCAFFOLD_OPTIONS` in `ui/src/components/fullstack/FullstackView.tsx` — an opt missing from that list is honored by the API but unreachable from the editor (this happened to `openapi`/`secured`/`csvExport`/`bulkDelete` once). `FullstackStarterIntegrationTests` pins the generated bytes; extend the matching case.

Audit/soft-delete/inverse modify *existing* templates via `{{#optScaffold…}}` sections (not whole-file gates); they are mirrored in both `spring-jpa-crud` and `spring-jpa-crud-lombok` `Entity.java.mustache`. `openapi`/`secured`/`csvExport`/`bulkDelete` likewise add `{{#optScaffold…}}` sections to the shared `Controller.java.mustache`/`Service.java.mustache` (the Lombok set reuses them via `sourceSet`, so one edit covers both backend sets). Remaining opt-in candidates (Flyway/seed data) still pend a catalog-dependency change.

Audit & soft-delete only apply to **writable, table-backed** entities, so their per-entity flags both require `mutable` (see read-only views below): `auditApplicable = optScaffoldAudit && mutable`, `softDeleteApplicable = optScaffoldSoftDelete && !hasCompositePk && mutable`. Templates gate on `auditApplicable`/`softDeleteApplicable`, **not** the raw `optScaffold*` opt (the project-level `JpaAuditingConfig` whole-file gate still keys off `optScaffoldAudit`).

#### Read-only entities & SELECT-backed views (per-entity)

Unlike the project-wide `opts.scaffold` flags, **read-only** is a **per-entity** property on `EntityDefinition` (`readOnly`, `viewQuery`; wire `EntityDefinitionDto.readOnly`/`viewQuery`). A read-only entity generates GET-only scaffolding — `EntityScaffoldContext` exposes `readOnly`, `mutable` (`!readOnly`), `isView` (`viewQuery` present), and `viewQuery`; `Service`/`Controller` wrap create/update/delete in `{{#mutable}}`, and the frontend `EntityPage` gates its New/Edit/Delete surface (the shared `Table` hides its Actions column when `onEdit`/`onDelete` are omitted).

**List views (`listViews`)** is another per-entity property (`EntityDefinition.listViews`, wire `EntityDefinitionDto.listViews`; an ordered subset of `"table"`/`"cards"`/`"kanban"`/`"calendar"`, normalized/deduped in the record's compact constructor, default `["table"]`). The generated `EntityPage` emits **exactly the enabled views** that the entity's fields support — a runtime **view toggle** appears only when 2+ are enabled, and `initialView` (the `useState` seed) is the first. The legacy single `listView` field is still accepted (treated as a one-element `listViews`) for back-compat; `EntityDefinition.listView()` returns the first enabled view. `EntityScaffoldContext` exposes the per-view flags `viewTable`/`viewCards`/`viewKanban`/`viewCalendar`, `hasViewToggle`, `initialView`, and `viewModeType` (the TS union). All view modes consume the **same `Column<T>[]` model** as `Table.tsx` (so the data/`useResource` layer is unchanged); the first column is the heading/label.

- **`cards`** — the shared `CardGrid.tsx` (responsive grid; first column is the card heading).
- **`kanban`** — the shared `KanbanBoard.tsx` (native HTML5 drag-and-drop). Lanes are the **breakdown field** (first ENUM, else BOOLEAN); dragging a card writes the new lane value back via the entity's `update`. Requested kanban is **dropped** (`viewKanban` false) when there's no breakdown field or the entity is read-only; if the resulting view set is empty it falls back to `["table"]`.
- **`calendar`** — the shared `CalendarView.tsx` (month grid). Records are bucketed by the **first temporal** (`LOCAL_DATE`/`LOCAL_DATE_TIME`) field; requested calendar is dropped (`viewCalendar` false) when the entity has no temporal field. Read-only-safe.

Independently of `listViews`, every entity with ≥1 non-PK enum/boolean/temporal/numeric field gets a **type-aware filter bar** (shared `FilterBar.tsx`, gated on `hasFilters`): enum dropdown, boolean tri-state, temporal from/to, numeric min/max. The filter values are forwarded by `useResource` as query params (`name`, `nameFrom`/`nameTo`, `nameMin`/`nameMax`) into the backend `Service`, which composes them with the text-search `Specification` (`filterFields` + `needsSpecification`). The repository already extends `JpaSpecificationExecutor`, so no repo change.

Every entity also gets a read-only **detail panel**: a row/card "view" action (`onView` on both `Table`/`CardGrid`) opens the shared `DetailDrawer.tsx` rendering the per-entity `EntityDetail.tsx` (all fields stacked, TEXT wrapped, relations/audit/inverse counts) — kept **local to `EntityPage`** (`detailRow` state) rather than threading a record id through the app's custom string-based nav. The generated **dashboard** renders a per-entity grouped **bar chart** (client-side grouping of a fetched page, no extra endpoint) for each entity whose `hasBreakdown` is set (first ENUM, else BOOLEAN field). `src/vite-env.d.ts` (`/// <reference types="vite/client" />`) makes `import.meta.env` typecheck under `tsc -b`; it is a FRONTEND `__common__` catalog row (`static-configs/frontend/common/vite-env.d.ts`), so every standalone frontend has it (auth-msal's `msal-config.ts` and the tanstack axios client read env vars — without it `GeneratedFrontendBuildSmokeTests.advertisedFeaturesProjectInstallsAndBuilds` failed), and the fullstack overlay ships the same file.

When `viewQuery` is set the entity maps to a **Hibernate `@Immutable` + `@Subselect("…")`** view instead of a `@Table` (both `Entity.java.mustache` swap on `{{#isView}}`); the field `@Column(name=…)` uses the **verbatim** field name (not snake_case) so it matches the SELECT's projected column label — so import keeps the projected alias as the field name and users control naming via `SELECT … AS alias`. A view requires ≥1 PK (the `@Id`), forbids a `generated` PK and relations, and may not be targeted by a `MANY_TO_ONE` (all enforced in `FullstackRequestValidator`, mirrored in the UI `validation.ts`).

Two import endpoints feed the editor (both `{sql, dialect?}` → wire entities): `POST /metadata/fullstack/import-ddl` (CREATE TABLE → table-backed entities) and `POST /metadata/fullstack/import-select` (a single SELECT → one read-only view; fields default to `STRING` for the user to type). The SELECT parser is `SqlEntityGenerator.parseSelectForImport`, bridged via `SqlToEntityDefinitionConverter.convertSelect` (which returns a `SelectImportResult(entities, note)`; the `note` rides back on `ImportDdlResponse`). `@Subselect` runs **native SQL**, so when JSqlParser can't parse the query, the parser falls back to a regex column extractor (`extractProjectedColumns` — finds the outer `SELECT … FROM` projection, splits on top-level commas, names each item by `AS` alias or trailing identifier) and flags `SelectProjection.heuristic`; if even that finds nothing the view comes back with zero fields for manual entry. Only a deliberate `SELECT *` / unaliased expression is a hard 400 (those parse fine — they just can't be auto-named). The standalone backend SQL wizard (`/starter-wizard.zip`) does **not** support SELECT — fullstack only for v1.

`SqlToEntityDefinitionConverter.convert` runs two passes so the DDL import keeps the schema's shape: a single-column `FOREIGN KEY` whose referenced table is in the same paste (single-key, and the FK column not itself part of the key) becomes a `MANY_TO_ONE` relation and the FK column leaves the fields (`relationFieldName`: the column minus its `_id`/`Id` suffix, camel-cased, falling back to the decapitalized target name; a still-colliding name keeps the column as a plain field). Composite FKs, FKs to tables outside the paste and FKs to composite-key tables stay plain fields. `CHECK (col IN ('A','B'))` — column- or table-level, read from the statement text with a regex because column-level checks only survive JSqlParser as raw tokens — turns the column into an `ENUM` (`toEnumConstant`: upper-case, non-identifier chars → `_`, leading digit → `_` prefix; an unsanitizable or colliding value leaves the column a string). `EntityWire` carries `relations`. The validator's enum-constant keyword check is exact-case (`NEW`/`DEFAULT` are ordinary constants; a literal `new` is rejected). Pinned by `SqlToEntityDefinitionConverterTest` and `importDdlEndpoint_turnsForeignKeysIntoRelationsAndCheckInIntoEnums`.

**Team models.** `config/FullstackModelController` at `/metadata/fullstack/models` (`GET` summaries, `POST`, `GET|PUT|DELETE /{id}`) stores whole editor snapshots — the UI's `menora-fullstack-model/1` export shape, verbatim, ≤ 1 MB, structurally an object with an `entities` array — in `initializer_fullstack_model` (`V19`, `db/entity/FullstackModelEntity`) so a model is visible to everyone who opens the generator. Reads and writes are open: the only reusable auth is the admin password, which would defeat the purpose. Names are unique case-insensitively (409 with `{error, detail}`), `createdBy` comes from the `userinfo` header like the generation audit, and the rows are deliberately **not** part of admin export/import (that import wipes and re-inserts the catalog). Pinned by `FullstackModelControllerTests`.

**Fullstack examples.** The Fullstack tab's "Start from → Examples" (Blog, Orders, Tickets, …) live in `initializer_fullstack_example` (`V21`, `db/entity/FullstackExampleEntity`): `exampleId` slug, name/description/icon (Material Symbols), `entities` (the wire-entity JSON array of a `/starter-fullstack.zip` body, stored as text), `sortOrder`, `enabled`. Seeded from `catalog/fullstack-examples.json` by `DataSeeder.seedFullstackExamplesIfMissing` (table-scoped, like departments). Public `GET /metadata/fullstack/examples` (`config/FullstackExampleController`, enabled rows only, `id` = slug); admin CRUD at `/admin/fullstack-examples` (`admin/FullstackExampleAdminController`) runs every save through `FullstackRequestValidator` (`validateEntities`), so a stored example always generates — 400 `{error, detail}` otherwise, 409 on a duplicate slug. Unlike team models they **are** part of admin export/import (`fullstackExamples`, validated before the wipe, skipped when an older export lacks the field). Pinned by `FullstackExampleTests`.

**Frontend page layouts (`pages`).** A fullstack request may carry `pages` (`FullstackStarterRequest.PageDefinitionDto`): the generated frontend's screens as a fixed catalog of page types — `entity-list` (`entity`, optional `presetFilter` of enum/boolean field → value), `dashboard` (`widgets`: `kpi` one number, `bar` grouped by an enum/boolean `groupBy` — defaults to the first enum, else boolean — `line` a time series over a temporal `groupBy` bucketed by `bucket` (`day`/`month`/`year`, default month), and `recent` latest `limit` rows, newest first by `sortBy` — any non-key field, default the PK; `kpi`/`bar`/`line` take `agg` (`count` default, else `sum`/`avg`/`min`/`max`) over a non-key numeric `field`; every widget may take a `span` of 1–4 grid columns — default 1 for a tile, 2 otherwise, rendered as a literal `className` on the widget — and a `presetFilter` like a list page's. A dashboard `dateRange` (`all`/`7d`/`30d`/`90d`/`ytd`/`12m`) adds a `PeriodSelect` opening on that period; each widget's `dateField` — default its entity's first filterable non-key date, which must be filterable because the period travels as the list's `<field>From`/`<field>To` params (`stats.ts` `rangeParams`, whole days for a date-time) — is limited to it, a widget whose entity has no such date is not, and a `dateRange` that limits no widget is a 400. The widgets take one `params` string (preset + period, joined by `statsQuery`) that both the `/stats` calls and the list calls carry), `tabs` (2–6 `tabs`, each embedding another page by id — never a tabs or record page; requires a `title`), `master-detail` (a `parent` list beside the `child` rows of the selected parent, linked by the child's `via` MANY_TO_ONE — defaulted when the child has exactly one relation to the parent, required and named in the error when it has several) `record` (one row of `entity` opened by id, with a details tab plus one `childTabs` entry per related entity — the default is every entity with a MANY_TO_ONE to it) and `report` (one `entity`'s filter bar, a single `chart` — `groupBy` an enum/boolean for bars or a date for a line, with the same `agg`/`field`/`bucket` — a grouped totals table, and an Export CSV button when the `csvExport` opt is on). Every page has a slug `id` (→ `src/app/screens/<PascalId>Screen.tsx`), optional `title`/`description`, and `hidden` (out of the nav, reachable only as a tab). A nav page may also name a `group` (≤40 chars; pages sharing it are listed together as one nav section, gathered where the group first appears — a labelled sidebar section in the tailwind shell, a caret item opening a `DropdownMenu` in the Menora top bar) and an `icon` (one of the lucide names in `FullstackPageValidator.NAV_ICONS`, matched ignoring case; default: the type's own). Either on a hidden page is a 400. A record page is **always** hidden — it needs a record id — so `hidden: false` on one is a 400, and an entity gets at most one; a master-detail parent and a record entity both need a single PK. `FullstackPageValidator` checks references against the converted entities and canonicalizes names/enum constants (400 naming the page; `wizard` answers "not supported yet"). **No pages → the classic shell, byte for byte**: `EntityScaffoldContext.putPageContext` only sets `hasPages=false`, and `App.tsx` / `EntityPage.tsx` keep their old output under `{{^hasPages}}`. With pages, `putPageContext` (frontend context only) adds `pages`/`navPages`/`routePages` (nav + record pages, what the shell can show)/`recordPages`/`initialPageId`/`navIconImports` (the sorted lucide import list)/`navGroups` (+ `hasNavGroups`)/`hasDashboardPages`/`hasReportPages`/`hasWidgets`/`hasRecordPages`/`hasNavigatingScreens` and a per-page view-model (`PageName`, `pageTitleExpr` — a ready TS expression, user text escaped by `tsString` — `pageIs*`, `needsNavigate`, `usesT`, widget/tab/childTab lists, and the `PageLinks` lookups `hasRecordPage`/`recordPageId`/`recordPk` — where rows of an entity open — and `hasBack`/`backPageId`, an entity's visible list, master-detail or tabs "home"). Template files with `perPage=true` (column `per_page`, `V22`) render once per page against project context + page view-model, gated per type with the ordinary `gatedBy` (`pageIsEntityList`/`pageIsDashboard`/`pageIsTabs`/`pageIsMasterDetail`/`pageIsRecord`/`pageIsReport`); `widgets.tsx` (the shared charts and tiles) and `stats.ts` (the `/stats` client — kept separate because the generated lint's react-refresh rule lets a component file export only components) are gated on `hasWidgets`, which a report sets as well as a dashboard. Screens import `t`, enum `…Labels` and take `onNavigate` only when used (the generated lint rejects unused imports). In page mode `EntityPage` takes props (`<Entity>PageProps`): `initialFilters` (preset-filtered list pages) when `hasFilters`, `onOpenRecord` (a row opens its record page instead of the quick-look drawer), and — when `pageScopeable`, i.e. the entity has relations (set in `buildEntityContext`) — `scope` ({param, value}: a pinned relation filter that is merged into the query, hidden from the `FilterBar` and pre-filled into new records, so New inside a master-detail/record page links the row to its parent). **Routing is the URL hash** (`#/<page>[/<arg>][?<field>=<value>&…]`): `src/app/route.ts` (`route.ts.mustache`, gated `hasPages`, borrowed by the Menora set) holds the `View` union, `parseRoute`/`toHash` and a `useRoute()` hook that follows `hashchange`, so Back/Forward, reloads and bookmarks work; an unknown page — or a record page without an id — falls back to `initialPageId`. The shell hands screens one `goView(view, arg?, query?)`; each screen also gets its route props from the per-page `routeProps` string: a tabs page `tab`/`onTabChange` (the open tab is the route arg, `#/queue/tickets-open`) and a filterable list page (`listTakesQuery`) `filters` from the query, which it merges over its preset and keys the `EntityPage` on, so a new filter set is a fresh list. `react-menora-digital-crud` authors only `ScreenTabs` (the Menora `Tabs` port) and borrows the other screens + widgets — including `ScreenReport`, which picks up the Menora `FilterBar` for free. Examples carry optional `pages` + `settings` (JSON columns, `V22`; `settings` = `locale`/`dashboardTitle`/`dashboardOverview`/`backendTemplateSet`/`frontendTemplateSet`/`colorPalette`/`scaffold[]`), validated by `FullstackExampleAdminController.validatePages`/`validateSettings` on save and import and included in export. **Upgraded DBs** keep the pre-`V22` template rows (no screens, no `hasPages` branch) until the sets are re-seeded — a layout request there silently renders the classic shell. Pinned by `FullstackPagesIntegrationTests`; built for real by `GeneratedFrontendBuildSmokeTests.fullstack*PageLayouts*` (Orders on the tailwind set for the id-driven screens, Tickets on the Menora set for its Tabs port) and `fullstack*ReportLayout*` (Sales reporting on both sets, for the aggregate widgets and the report screen). Every `fullstackFrontend*` smoke test also runs `eslint --max-warnings 0` over the whole generated frontend — the generated pre-commit hook runs the same lint, so a template that leaves an unused import/local (e.g. the unselected i18n table, now exported as `STRINGS`) fails there.

**Generated aggregation endpoint (`GET /api/x/stats`).** Every entity with something to group,
bucket or reduce gets one: `?groupBy=` (an enum/boolean column, or a date column with
`&bucket=day|month|year`), `&agg=count|sum|avg|min|max` over `&field=`, plus the entity's own `q`
and filter params — so a chart always agrees with the list beneath it. It answers
`{"buckets":[{"key","value"}],"total"}`; `key` is the enum constant, `true`/`false`, a date bucket
(`2026-09`) or `""` when nothing is grouped. `Service.stats` reuses the list's `Specification`
through the extracted `specOf(...)`, and both `groupBy` and `field` are resolved through generated
whitelist `switch`es — anything else throws `IllegalArgumentException`, which the generated
`ApiExceptionHandler` already answers as 400. Date parts go through Hibernate's
`HibernateCriteriaBuilder.year/month/day`, which lower to `extract(<unit> from x)` and are rendered
per dialect; the portable-looking `cb.function("year", …)` emits a raw SQL `year()` that only H2 and
MySQL register, so **do not "simplify" it back**. There is no `week` bucket, because `extract(week
from …)` is the ISO week on PostgreSQL/Oracle but a locale-dependent one on H2. The gate
(`statsApplicable`, with the three whitelists) is derived in `EntityScaffoldContext.entityViewModel`
**from the entity model alone** — the backend render context never sees `pages`
(`putPageContext` is frontend-only), and both render paths have to agree on whether the endpoint
exists. The frontend's breakdown charts, trend charts and aggregate tiles all read it; a plain
record count still comes from the list's page metadata (`?size=1`), which is already exact. Pinned
by `fullstackEndpoint_generatesTheStatsRollupForChartableEntities`.

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

A dependency that fails to build (bad `compatibilityRange`, bad `scope`, …) is skipped rather than taking the catalog down, but it then silently vanishes from `/metadata/client`. The skipped rows are recorded in `DatabaseInitializrMetadataProvider.getLoadFailures()` and returned by `/admin/refresh` as `{"message": …, "failed": […]}`; the admin UI shows them as an error toast.

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

### Menora Digital design system (`design-menora-digital`)

The Menora Mivtachim customer-site brand (menoramivt.co.il), imported from the "Menora Mivtachim Design System" artifact. Unlike MUI/Chakra/Mantine it is **plain CSS, not palette-aware** (the tokens *are* the brand): `static-configs/frontend/design-menora-digital/` holds `tokens.css` (every colour/type/spacing/radius/shadow token as a `:root` custom property; `--font-sans: 'Almoni', 'Assistant', Arial`), `components.css` (the `.mn-*` classes — Button primary/header/outlined/text, NavLinks, ActionDisc, ServiceBubble, MagazineCard, CarouselArrow, ChatLauncher, Hero, plus the canvas's "proposed" set: SectionHeader, ActionPanel, Tabs, DropdownMenu, SearchField, ExpertTip, Chip, Footer, Table and the shared `.mn-panel` / `.mn-field` helpers), **17 typed React ports** of those components (`src/shared/ui/menora/*.tsx` + barrel; the source of truth is the "Menora Component Explorer" Claude Design canvas, https://claude.ai/artifact/K6M7i6ug16tJbhvNzPekMk — one artboard per component with markup, Props and Rules). The ports live in a dep with no i18n module, so **every string they show is a prop** (the fullstack overlay passes `t('…')`; the bundle components keep their canvas defaults, e.g. ChatLauncher's `פנו אלינו`), a `src/index.css` override (sortOrder 10, keeps `@tailwind` directives when `style-tailwind` is co-selected), a Hebrew showcase `HomePage.tsx` (sortOrder 20, overwrites the `__common__` one) and the 150×46 header logo. The substrate's `fe-app-tsx.mustache` has a `{{#hasDesignMenoraDigital}}` shell branch (white header with logo + `NavLinks` + header pill, page ground, dark footer band, fixed `ChatLauncher`) and `fe-main-tsx.mustache` imports `@fontsource/assistant/{300..700}.css` — Almoni is licensed and **not bundled**, Assistant is the design system's sanctioned fallback (self-hosted via npm, never a CDN link). The logo is a binary, so `FrontendProjectGenerator.copyStaticAssets(targetDir, depIds)` copies it to `public/menora-mivtachim-logo.png` only when the dep is selected. It CONFLICTS with the other four design systems and is React-version agnostic. **Dark mode:** `tokens.css` carries the design system's dark theme (designed from the brand colours — the site has none) under `:root[data-theme="dark"], :root.dark`; the standalone shell ships `useMenoraTheme` + a `ThemeToggle` pill (sets `data-theme` and the `dark` class, persists to localStorage), and the fullstack set's `index.css.mustache` `.dark` block re-points the Tailwind tokens to the same values (purple lifts to `#a393ff`, grounds go slate, shadows black-based). Yellow and its `--on-yellow` label never change; the dark-purple PNG logo always sits on a white chip (`.mn-logo-chip`) so it survives dark grounds. `components.css` deliberately drops the source bundle's `direction: rtl` on `.mn` so `<html dir>` (the `rtl` flag) governs layout; the generator UI turns the RTL toggle on when this design system is picked. A `menora-digital` colour palette (`#684eed`/`#ffc700`) is also seeded so the palette-driven design systems and the default fullstack set can be brand-coloured.

### Department (`{{department}}`)

An admin-managed list (`initializer_department`, `V20`; `DepartmentEntity` — `departmentId` is a
lower-case slug because it lands in k8s names) shared by the Backend, Frontend and Fullstack screens.
Seeded with `lts` as the default by `DataSeeder.seedDepartmentsIfMissing` (table-scoped, so a
deleted row is not resurrected). Admin CRUD at `/admin/departments` (`DepartmentAdminController`,
single-default rule like palettes), public list at `GET /metadata/departments`, included in admin
export/import (`departments`, skipped when an older export lacks it).

**Transport.** The request's id rides on `ProjectOptionsContext.department()`: the filter reads the
`department` query param (so `/starter.zip`, `/starter.preview` and `/starter-multimodule.*` need no
controller change), and the JSON endpoints call `optionsContext.setDepartment(body.department())`
after `populate(opts)` (wizard, fullstack). `/frontend/starter.*` takes it as a `@RequestParam` onto
`FrontendProjectDescription.department`. `ProjectOptionsContext.clear()` resets it, so the filter
backstop covers it.

**Rendering.** `DepartmentResolver.putVars(ctx, id)` adds `department` / `departmentUpper` /
`departmentName` to every context (backend `buildBaseContext`, fullstack backend
`FullstackProjectGenerationConfiguration`, frontend `renderInto` and fullstack `renderFrontend`).
Unknown/blank → the default row → `lts`. Used by both `k8s-values.mustache` (namespace, department,
imageRepo, cert secret, Vault namespace), the log4j2 Kafka topic and the ldap-auth group prefix
(`PermissionService` + the two ldap `application.yaml` fragments — `YAML_MERGE` rows now honour
`substitutionType: MUSTACHE`, and the admin content validator renders them before validating).
Pinned by `DepartmentIntegrationTests`. **Upgraded DBs** keep the old hardcoded-`lts` template rows
until re-seeded or edited in admin (the department table itself appears automatically).

### Frontend Compatibility Rules (REQUIRES / CONFLICTS / RECOMMENDS)

Inter-dependency rules live in `dependency_compatibility` and are tagged by `project_kind`. The endpoint `/metadata/compatibility?projectKind=FRONTEND` returns FE-scoped rules (omit the param to get every row). FE seed rules are in `DataSeeder.feCompat(...)` and cover design-system conflicts, state-mgmt conflicts, and `design-shadcn REQUIRES style-tailwind`.

**Server-side enforcement.** `FrontendCompatibilityResolver` runs inside `FrontendStarterController.buildDescription` after the React-version filter. It auto-adds REQUIRES targets (or drops the source if the target is missing from the catalog) and drops the later-selected dep in a CONFLICTS pair, both with warn logs. This is the safety net for direct API hits (curl, IntelliJ); the UI surfaces the same rules as banners via `useCompatibility('FRONTEND')` so users see the issue before clicking Generate. RECOMMENDS never alter the selection — they only render as suggestions.

### InitializrWebConfiguration

`src/main/java/com/menora/initializr/config/InitializrWebConfiguration.java`

A `@Component`, `@Order(Integer.MIN_VALUE)` servlet filter (extends `OncePerRequestFilter`) that runs before all other filters and wraps every request with three responsibilities:

1. **`configurationFileFormat` default** — injects `configurationFileFormat=properties` when absent
2. **`X-Forwarded-Port` sanitization** — returns empty string if absent/unparseable/`"null"`
3. **Sub-option context** — calls `optionsContext.populate(request)` before and `clear()` after the filter chain

### Shared Generation Helpers

The generation endpoints (`WizardStarterController`, `FullstackStarterController`,
`MultiModuleController`, `ProjectPreviewController`, `FrontendProjectGenerator`) each used to
carry private copies of the same filesystem code. Two shared classes own it now — add to these
rather than re-introducing a private copy:

- `config/GeneratedProjectFiles` — `zipDirectory`, `readSafely`, `copyDirectory` (public, since
  `FrontendProjectGenerator` is in another package)
- `config/PreviewTreeBuilder` — `buildTree(sortedPaths)`, the flat-paths → `TreeNode` shaping
  behind every preview response

### Per-Request ThreadLocal Contexts

Five `@Component` holders in `config/` carry per-request state into the generation child
context: `ProjectOptionsContext`, `SqlScriptsContext`, `OpenApiSpecContext`, `SoapSpecContext`,
`EntityDefinitionContext`. Controllers populate what they need **inside** their `try` and clear
it in the `finally`, but `InitializrWebConfiguration` (the `@Order(MIN_VALUE)` filter) clears
**all five** in its own `finally` as the unconditional backstop — no controller covers every
context, and an exception reaching `GlobalExceptionHandler` clears none. Without that backstop a
leftover context gets scaffolded into the next request served by the same pooled Tomcat thread.
Pinned by `config/GenerationContextCleanupTests`.

### Test Infrastructure

Tests use `src/test/resources/application.properties` which configures an in-memory H2 (`ddl-auto: validate`, schema managed by Flyway; `admin.password=test`). `DataSeeder` runs automatically at test startup and seeds the DB from the catalog manifests, so tests exercise the full DB-driven pipeline.

`src/test/java/com/menora/initializr/TestInvokerConfiguration.java` — a `@TestConfiguration` that provides a `ProjectGenerationInvoker<ProjectRequest>` bean. Test classes import it via `@Import(TestInvokerConfiguration.class)` to invoke project generation directly without HTTP.

**Always release an invoker-generated directory with `invoker.cleanTempFiles(dir)`, not `FileSystemUtils.deleteRecursively(dir)`.** `ProjectGenerationInvoker` is a singleton bean that registers every generated root in a private `temporaryFiles` map and only drops the entry in `cleanTempFiles` — a plain delete removes the files but leaks the map entry for the life of the JVM. `cleanTempFiles` deletes the tree too, so it replaces the delete rather than joining it. It only accepts paths the invoker produced (it NPEs on an unknown path), so directories from `Files.createTempDirectory` still use `FileSystemUtils.deleteRecursively`. Pinned by `InvokerTempFileCleanupTests`.

**Coverage:** `jacoco-maven-plugin` runs during `mvn test` → report at `target/site/jacoco/index.html`. A `check` execution gates the build at 78% instruction / 62% branch (bundle-wide) — set just under the measured 81.5% / 65.0%, so it catches a real regression without tripping on churn. Raise it as coverage rises; never lower it to make a build pass.

**Seeder / admin tests:**
- `db/DataSeederTest` — characterization test: asserts the observable seeded catalog (group/entry/file-contribution counts, representative content, compatibility/sub-option/template/palette/version counts). The regression oracle for catalog-manifest changes — together with `ProjectGenerationIntegrationTests` (actual generated file bytes) it pins seeding behavior.
- `admin/AdminApiIntegrationTests` (`MockMvc`) — auth gate (login, 401), validation (400) and orphan-conflict (409) error paths, `/admin/refresh`.
- `admin/ConfigurationExportImportServiceTest` — export → import round-trip preserves row counts and content (runs `@Transactional` so the destructive import rolls back).

**Regression guards added alongside the fixes they pin:**
- `InvokerTempFileCleanupTests` — the invoker's `temporaryFiles` map is empty after `cleanTempFiles` (reflective; the map is private).
- `config/GenerationContextCleanupTests` — the filter clears all five ThreadLocal contexts, on both the success and the exception path.
- `MultiModuleGenerationTests` — `/starter-multimodule.zip` and `.preview` over HTTP. The only generation tests that go through the servlet stack rather than calling the invoker in-process, which is what makes them able to catch request-binding bugs.
- `GlobalErrorHandlingTests.unknownPathReturns404NotAWrapped500` — the catch-all handler must not swallow `NoResourceFoundException`.
- In `ProjectGenerationIntegrationTests`: `blankYamlMerge*` (blank `YAML_MERGE` is a no-op), `deleteContributionResolvesPackagePathPlaceholder` and `deleteContributionGatedOnUnselectedSubOptionDoesNotFire` (the delete pass gates and resolves paths like the write pass).

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
