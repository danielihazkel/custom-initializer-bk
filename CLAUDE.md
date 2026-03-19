# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=KafkaProjectGenerationConfigurationTests

# Run a single test method
mvn test -Dtest=ProjectGenerationIntegrationTests#kafkaDependencyInjectsConfigFiles

# Run the application
java -jar target/offline-spring-init-1.0.0-SNAPSHOT.jar

# Verify the running service
curl http://localhost:8080/metadata/client
curl http://localhost:8080/actuator/health

# Generate a test project via API
curl -o test.zip "http://localhost:8080/starter.zip?dependencies=web,kafka"
```

## Architecture

This app wraps the Spring Initializr framework (`initializr-web` + `initializr-generator-spring` v0.23.x). The framework handles the REST API and ZIP generation; this codebase only adds:

1. **A dependency catalog** (`application.yml`) — defines what users can select
2. **Extension classes** (`extension/*/`) — inject Menora-standard files into generated projects

### Generation Pipeline

When a project is generated, the framework spins up a child Spring application context for that request and calls every `ProjectGenerationConfiguration` registered in `META-INF/spring.factories`. Each configuration class contributes `@Bean`s of two types:

- **`ProjectContributor`** — writes files into the generated project directory (YAML configs, Java classes, XML)
- **`BuildCustomizer<MavenBuild>`** — modifies the generated `pom.xml` (used in `CommonProjectGenerationConfiguration` to inject Artifactory `<repository>` blocks)

`@ConditionalOnRequestedDependency("id")` on a class gates the entire configuration on whether the user selected that dependency ID. `CommonProjectGenerationConfiguration` has no condition — it runs for every project.

### Adding a New Dependency with Custom Config

The full pattern is documented in `README.md`. The mandatory steps in order:
1. Add entry to `application.yml` under `initializr.dependencies`
2. Create `static-configs/<name>/application-<name>.yml` (and/or a `.mustache` template if a Java class is needed)
3. Create `extension/<name>/<Name>ProjectGenerationConfiguration.java` with `@ProjectGenerationConfiguration` + `@ConditionalOnRequestedDependency("<id>")`
4. Register the class in `META-INF/spring.factories`

Skipping step 4 means the class is silently ignored — the framework won't auto-scan it.

### Template Substitution

There are **two distinct substitution systems** — do not confuse them:

1. **`renderTemplate()` in `CommonProjectGenerationConfiguration`** — used for common project files (Dockerfile, Jenkinsfile, k8s-values.yaml, VERSION). Replaces three variables via `String.replace`:
   - `{{artifactId}}` — from `ProjectDescription.getArtifactId()`
   - `{{groupId}}` — from `ProjectDescription.getGroupId()`
   - `{{version}}` — from `ProjectDescription.getVersion()`

2. **`String.replace("{{packageName}}", packageName)`** — used in extension configs when generating Java classes (KafkaConfig, SecurityConfig, JpaConfig, RqueueConfig). Only the `{{packageName}}` variable is supported here.

Neither system uses a real Mustache engine. The `.mustache` file extension is cosmetic only.

### CommonProjectGenerationConfiguration — Files Injected for Every Project

This configuration runs unconditionally. It injects **9 items**:

| Bean | Source (classpath) | Destination in generated project |
|---|---|---|
| `log4j2Contributor` | `static-configs/common/log4j2-spring.xml` | `src/main/resources/log4j2-spring.xml` |
| `editorConfigContributor` | `static-configs/common/.editorconfig` | `.editorconfig` |
| `entrypointContributor` | `static-configs/common/entrypoint.sh` | `entrypoint.sh` |
| `settingsXmlContributor` | `static-configs/common/settings.xml` | `settings.xml` |
| `versionFileContributor` | `templates/VERSION.mustache` (rendered) | `VERSION` |
| `dockerfileContributor` | `templates/Dockerfile.mustache` (rendered) | `Dockerfile` |
| `jenkinsfileContributor` | `templates/Jenkinsfile.mustache` (rendered) | `k8s/Jenkinsfile` |
| `k8sValuesContributor` | `templates/k8s-values.mustache` (rendered) | `k8s/values.yaml` |
| `artifactoryBuildCustomizer` | *(no file)* | Injects `menora-release` + `menora-snapshot` repos into `pom.xml` |

**log4j2 note:** `log4j2BuildCustomizer` also adds `spring-boot-starter-log4j2` and excludes `spring-boot-starter-logging` from the generated `pom.xml`. `logback-spring.xml` no longer exists — it was replaced by `log4j2-spring.xml`.

### Extension Quick Reference

| Dependency ID | Config class | Files injected into generated project |
|---|---|---|
| *(any)* | `CommonProjectGenerationConfiguration` | See table above (9 items) |
| `kafka` | `KafkaProjectGenerationConfiguration` | `application-kafka.yml`, `KafkaConfig.java` |
| `security` | `SecurityProjectGenerationConfiguration` | `application-security.yml`, `SecurityConfig.java` |
| `data-jpa` | `JpaProjectGenerationConfiguration` | `application-jpa.yml`, `JpaConfig.java` |
| `actuator` | `ObservabilityProjectGenerationConfiguration` | `application-observability.yml` |
| `rqueue` | `RqueueProjectGenerationConfiguration` | `application-rqueue.yml`, `RqueueConfig.java` |
| `logging` | `LoggingProjectGenerationConfiguration` | `application-logging.yml` |

YAML configs land in `src/main/resources/`. Java classes land in `src/main/java/<packagePath>/config/`.

### InitializrWebConfiguration

`src/main/java/com/menora/initializr/config/InitializrWebConfiguration.java`

A `@Component`, `@Order(Integer.MIN_VALUE)` servlet filter (extends `OncePerRequestFilter`) that runs before all other filters and wraps every request with two fixes:

1. **`configurationFileFormat` default** — injects `configurationFileFormat=properties` when the parameter is absent. Some clients (e.g. IntelliJ) don't send this param and the framework would otherwise fail.

2. **`X-Forwarded-Port` sanitization** — returns an empty string if the header is absent, unparseable as an integer, or the literal string `"null"`. Prevents the Initializr from concatenating `null` into URLs (e.g. `"8080null"`), which happens when running behind a Vite dev server proxy.

### Test Infrastructure

`src/test/java/com/menora/initializr/TestInvokerConfiguration.java` — a `@TestConfiguration` that provides a `ProjectGenerationInvoker<ProjectRequest>` bean. Test classes import it via `@Import(TestInvokerConfiguration.class)` to invoke project generation directly without HTTP.

**Test coverage summary (`ProjectGenerationIntegrationTests`):**
- `metadataEndpointReturnsOk` — HTTP smoke test; checks `kafka` and `rqueue` appear in metadata
- `generatedProjectContainsArtifactoryRepo` — verifies Artifactory repos in generated `pom.xml`
- `generatedProjectContainsVersionDockerfileAndK8s` — checks VERSION content, Dockerfile artifact ID substitution, k8s/values.yaml group ID substitution
- `generatedProjectContainsLog4j2` — verifies `log4j2-spring.xml` present, `logback-spring.xml` absent, `spring-boot-starter-log4j2` in pom
- `generatedProjectContainsEditorconfig` — checks `.editorconfig` present
- `securityDependencyInjectsSecurityConfig` — checks `application-security.yml` + `SecurityConfig.java`
- `jpaDependencyInjectsJpaConfig` — checks `application-jpa.yml` + `JpaConfig.java`
- `actuatorDependencyInjectsObservabilityConfig` — checks `application-observability.yml`
- `rqueueDependencyInjectsRqueueConfig` — checks `application-rqueue.yml` + `RqueueConfig.java`
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
3. `CommonProjectGenerationConfiguration.java` `artifactoryBuildCustomizer()` — what is written into generated `pom.xml` files
