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
- **`dynamicBuildCustomizer`** (`BuildCustomizer<MavenBuild>`) — applies all `BuildCustomizationEntity` records (add dependency, exclude dependency, add repository)

### FileContributionEntity — File Types

| Type | Behavior |
|------|----------|
| `STATIC_COPY` | Writes content verbatim to target path |
| `YAML_MERGE` | Deep-merges YAML into the target file (creates if absent) |
| `TEMPLATE` | Applies substitution variables then writes |
| `DELETE` | Deletes target file (runs at LOWEST_PRECEDENCE, after framework writes) |

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

Runs at startup as a `CommandLineRunner`. If all DB tables are empty it reads every classpath resource (`static-configs/*`, `templates/*`) and inserts them as DB records. This bootstraps the system from the existing files. After seeding, records can be modified via the admin API without touching the filesystem.

### Adding or Modifying a Dependency

The DB is the source of truth. Use the admin API or edit the `DataSeeder` for the initial seed:

1. **New dependency** — POST to `/admin/dependency-groups` + `/admin/dependency-entries`
2. **New file to inject** — POST to `/admin/file-contributions` with `dependencyId`, `fileType`, `content`, `targetPath`
3. **New build customization** — POST to `/admin/build-customizations`
4. **Hot-reload** — POST to `/admin/refresh` (no restart needed)

For a permanent change that survives a fresh DB (e.g. new deployment), also update `DataSeeder.java`.

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

Set via the admin UI (Dependencies tab → edit → Compatibility Range field) or directly in `DataSeeder` for fresh-DB seeds. The range is validated by `dep.resolve()` at metadata-build time — a malformed range throws immediately on refresh.

### InitializrWebConfiguration

`src/main/java/com/menora/initializr/config/InitializrWebConfiguration.java`

A `@Component`, `@Order(Integer.MIN_VALUE)` servlet filter (extends `OncePerRequestFilter`) that runs before all other filters and wraps every request with three responsibilities:

1. **`configurationFileFormat` default** — injects `configurationFileFormat=properties` when absent
2. **`X-Forwarded-Port` sanitization** — returns empty string if absent/unparseable/`"null"`
3. **Sub-option context** — calls `optionsContext.populate(request)` before and `clear()` after the filter chain

### Test Infrastructure

Tests use `src/test/resources/application.properties` which configures an in-memory H2 with `ddl-auto: create-drop`. `DataSeeder` runs automatically at test startup and seeds the DB from classpath, so tests exercise the full DB-driven pipeline.

`src/test/java/com/menora/initializr/TestInvokerConfiguration.java` — a `@TestConfiguration` that provides a `ProjectGenerationInvoker<ProjectRequest>` bean. Test classes import it via `@Import(TestInvokerConfiguration.class)` to invoke project generation directly without HTTP.

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
