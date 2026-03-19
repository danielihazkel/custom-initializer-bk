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

Templates in `resources/templates/*.mustache` are **not** processed by a Mustache engine. They use plain `String.replace("{{packageName}}", packageName)`. The only substitution variable is `{{packageName}}`. If more variables are needed in the future, either extend `copyClasspathResource` or add a proper Mustache dependency.

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
