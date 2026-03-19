# Menora Offline Spring Initializr

A self-hosted, air-gapped Spring Initializr for the Menora corporate network. It works identically to [start.spring.io](https://start.spring.io) but resolves all artifacts through on-prem Artifactory and injects Menora-standard configuration classes and YAML files into every generated project.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [Project Structure](#project-structure)
3. [Running the Application](#running-the-application)
   - [Prerequisites](#prerequisites)
   - [Run Locally (JAR)](#run-locally-jar)
   - [Run with Docker](#run-with-docker)
4. [Using the Initializr](#using-the-initializr)
   - [IntelliJ IDEA Integration](#intellij-idea-integration)
   - [REST API (curl)](#rest-api-curl)
5. [What Gets Injected Into Generated Projects](#what-gets-injected-into-generated-projects)
6. [Customization Guide](#customization-guide)
   - [Change the Spring Boot Version](#change-the-spring-boot-version)
   - [Change the Initializr Version](#change-the-initializr-version)
   - [Add a New Dependency (no custom config needed)](#add-a-new-dependency-no-custom-config-needed)
   - [Add a New Dependency with a Static Config File](#add-a-new-dependency-with-a-static-config-file)
   - [Add a New Dependency with a Generated Java Class](#add-a-new-dependency-with-a-generated-java-class)
   - [Edit an Existing Static Config File](#edit-an-existing-static-config-file)
   - [Edit an Existing Generated Java Class Template](#edit-an-existing-generated-java-class-template)
   - [Change the Artifactory URL](#change-the-artifactory-url)
   - [Add a New BOM (Bill of Materials)](#add-a-new-bom-bill-of-materials)
   - [Add a New Java Version Option](#add-a-new-java-version-option)
   - [Modify the Common Files (logback / .editorconfig)](#modify-the-common-files-logback--editorconfig)
7. [Testing](#testing)
8. [Dependency Catalog Reference](#dependency-catalog-reference)
9. [Architecture Reference](#architecture-reference)

---

## How It Works

The application is built on the open-source [Spring Initializr framework](https://github.com/spring-io/initializr) (v0.23.x). It has three layers:

| Layer | What | Where |
|-------|------|-------|
| **Metadata** | Dependency catalog, versions, Artifactory URLs | `application.yml` |
| **Generation** | Custom config/code injection per dependency | `@ProjectGenerationConfiguration` classes + templates |
| **Web** | REST API understood by IntelliJ and curl | Inherited from `initializr-web` |

When a project is generated, the initializr framework calls every registered `ProjectGenerationConfiguration` class. Each class decides whether it applies (based on which dependencies were selected) and then contributes files to the generated project ZIP.

---

## Project Structure

```
offline-spring-init/
├── pom.xml                                    # Build: Spring Boot 3.2.1, initializr 0.23.0
├── Dockerfile                                 # Multi-stage: Maven build → JRE Alpine
├── executed_plan.md                           # Record of what was built and why
├── src/main/
│   ├── java/com/menora/initializr/
│   │   ├── OfflineInitializrApplication.java  # Entry point
│   │   └── extension/
│   │       ├── common/CommonProjectGenerationConfiguration.java   # Always runs
│   │       ├── kafka/KafkaProjectGenerationConfiguration.java     # Triggered by: kafka
│   │       ├── security/SecurityProjectGenerationConfiguration.java # Triggered by: security
│   │       ├── jpa/JpaProjectGenerationConfiguration.java         # Triggered by: data-jpa
│   │       ├── observability/ObservabilityProjectGenerationConfiguration.java # Triggered by: actuator
│   │       ├── rqueue/RqueueProjectGenerationConfiguration.java   # Triggered by: rqueue
│   │       └── logging/LoggingProjectGenerationConfiguration.java # Triggered by: logging
│   └── resources/
│       ├── application.yml                    # Dependency catalog + Artifactory config
│       ├── META-INF/spring.factories          # Registers all generation configs
│       ├── templates/                         # Mustache-style templates → generated Java classes
│       │   ├── kafka-config.mustache
│       │   ├── security-config.mustache
│       │   ├── jpa-config.mustache
│       │   └── rqueue-config.mustache
│       └── static-configs/                    # YAML/XML files copied into generated projects
│           ├── common/logback-spring.xml
│           ├── common/.editorconfig
│           ├── kafka/application-kafka.yml
│           ├── security/application-security.yml
│           ├── jpa/application-jpa.yml
│           ├── observability/application-observability.yml
│           ├── rqueue/application-rqueue.yml
│           └── logging/application-logging.yml
└── src/test/java/com/menora/initializr/
    ├── ProjectGenerationIntegrationTests.java          # 8 integration tests
    └── extension/kafka/KafkaProjectGenerationConfigurationTests.java
```

---

## Running the Application

### Prerequisites

- Java 21+
- Maven 3.9+
- Access to `https://repo.menora.co.il/artifactory/libs-release` (for the build itself)

### Run Locally (JAR)

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/offline-spring-init-1.0.0-SNAPSHOT.jar
```

The service starts on **port 8080** by default.

To change the port:
```bash
java -jar target/offline-spring-init-1.0.0-SNAPSHOT.jar --server.port=9090
```

### Run with Docker

```bash
# Build the image
docker build -t menora/spring-init .

# Run the container
docker run -p 8080:8080 menora/spring-init
```

---

## Using the Initializr

### IntelliJ IDEA Integration

IntelliJ has built-in Spring Initializr support. To point it at this server:

1. **File → New → Project → Spring Initializr**
2. In the **Server URL** field, change `https://start.spring.io` to `http://localhost:8080` (or your server's host)
3. Select your dependencies normally — the Menora catalog appears automatically

### REST API (curl)

**Check the metadata (dependency catalog):**
```bash
curl http://localhost:8080/metadata/client
```

**Generate a project:**
```bash
# Web + Kafka
curl -o myproject.zip "http://localhost:8080/starter.zip?dependencies=web,kafka&groupId=com.menora&artifactId=myapp&packageName=com.menora.myapp"

# Web + Security + JPA + Actuator
curl -o myproject.zip "http://localhost:8080/starter.zip?dependencies=web,security,data-jpa,actuator&groupId=com.menora&artifactId=myapp"

# Unzip
unzip myproject.zip -d myapp/
```

**Health check:**
```bash
curl http://localhost:8080/actuator/health
```

---

## What Gets Injected Into Generated Projects

### Always (every generated project)

| File | Destination |
|------|------------|
| `logback-spring.xml` | `src/main/resources/logback-spring.xml` |
| `.editorconfig` | `.editorconfig` (project root) |
| Artifactory `<repository>` entries | Inside the generated `pom.xml` |

### Conditional (based on selected dependencies)

| Dependency ID | Files Injected |
|--------------|---------------|
| `kafka` | `src/main/resources/application-kafka.yml`<br>`src/main/java/.../config/KafkaConfig.java` |
| `security` | `src/main/resources/application-security.yml`<br>`src/main/java/.../config/SecurityConfig.java` |
| `data-jpa` | `src/main/resources/application-jpa.yml`<br>`src/main/java/.../config/JpaConfig.java` |
| `actuator` | `src/main/resources/application-observability.yml` |
| `rqueue` | `src/main/resources/application-rqueue.yml`<br>`src/main/java/.../config/RqueueConfig.java` |
| `logging` | `src/main/resources/application-logging.yml` |

---

## Customization Guide

### Change the Spring Boot Version

Two files need updating:

**`pom.xml`** — the parent version:
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.1</version>  <!-- change this -->
</parent>
```

**`src/main/resources/application.yml`** — the default offered to users:
```yaml
initializr:
  boot-versions:
    - id: 3.2.1       # change this
      name: 3.2.1
      default: true
```

To offer **multiple Boot versions** for users to choose from:
```yaml
initializr:
  boot-versions:
    - id: 3.3.5
      name: 3.3.5
    - id: 3.2.1
      name: 3.2.1
      default: true
```

---

### Change the Initializr Version

In `pom.xml`:
```xml
<properties>
    <spring-initializr.version>0.23.0</spring-initializr.version>  <!-- change this -->
</properties>
```

Check available versions at `https://repo.spring.io/release/io/spring/initializr/initializr-web/`.

---

### Add a New Dependency (no custom config needed)

Only touch `application.yml`. Find or create a category under `initializr.dependencies` and add an entry:

```yaml
initializr:
  dependencies:
    - name: Web          # existing category
      content:
        - name: Spring Web
          id: web
          description: Build web applications with Spring MVC
        # add your new dependency here:
        - name: Spring HATEOAS
          id: hateoas
          description: Hypermedia links in REST responses
          # groupId/artifactId are inferred from Spring Boot's dependency management
          # if managed by Spring Boot BOM, you don't need to specify them
```

For a library **not** managed by Spring Boot (no version in BOM), specify explicitly:
```yaml
        - name: MapStruct
          id: mapstruct
          groupId: org.mapstruct
          artifactId: mapstruct
          version: 1.5.5.Final
          description: Java bean mapping framework
```

For a library from **Menora Artifactory** (not in Maven Central):
```yaml
        - name: My Internal Lib
          id: my-internal-lib
          groupId: com.menora.internal
          artifactId: my-internal-lib
          version: 2.1.0
          description: Internal Menora library
          repository: menora-release   # must match a key in initializr.env.repositories
```

---

### Add a New Dependency with a Static Config File

This is for a dependency that needs a pre-filled YAML (or XML) file in the generated project.

**Step 1 — Add the dependency to `application.yml`** (same as above)

**Step 2 — Create the static config file:**
```
src/main/resources/static-configs/myfeature/application-myfeature.yml
```

Example content:
```yaml
myfeature:
  enabled: true
  endpoint: ${MYFEATURE_URL:http://localhost:9999}
  timeout: 5000
```

**Step 3 — Create the `ProjectGenerationConfiguration` class:**

Create `src/main/java/com/menora/initializr/extension/myfeature/MyFeatureProjectGenerationConfiguration.java`:

```java
package com.menora.initializr.extension.myfeature;

import com.menora.initializr.extension.common.CommonProjectGenerationConfiguration;
import io.spring.initializr.generator.condition.ConditionalOnRequestedDependency;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.springframework.context.annotation.Bean;

@ProjectGenerationConfiguration
@ConditionalOnRequestedDependency("my-dep-id")   // must match the "id" in application.yml
public class MyFeatureProjectGenerationConfiguration {

    @Bean
    ProjectContributor myFeatureYamlContributor() {
        return projectRoot -> {
            CommonProjectGenerationConfiguration.copyClasspathResource(
                    "static-configs/myfeature/application-myfeature.yml",
                    projectRoot.resolve("src/main/resources/application-myfeature.yml"));
        };
    }

}
```

**Step 4 — Register in `META-INF/spring.factories`:**

Open `src/main/resources/META-INF/spring.factories` and append your class:
```properties
io.spring.initializr.generator.project.ProjectGenerationConfiguration=\
  com.menora.initializr.extension.common.CommonProjectGenerationConfiguration,\
  ...existing entries...,\
  com.menora.initializr.extension.myfeature.MyFeatureProjectGenerationConfiguration
```

Rebuild and restart — done.

---

### Add a New Dependency with a Generated Java Class

This is for a dependency that needs a Java configuration class generated with the project's package name substituted in.

**Steps 1–4** are the same as above, plus:

**Step 5 — Create a template** in `src/main/resources/templates/myfeature-config.mustache`:

```java
package {{packageName}}.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyFeatureConfig {

    @Bean
    public SomeBean someBean() {
        return new SomeBean();
    }

}
```

The only substitution variable available is `{{packageName}}` — it is replaced with the user's chosen package name (e.g., `com.menora.myapp`).

**Step 6 — Add a second `ProjectContributor` bean** to your configuration class:

```java
@Bean
ProjectContributor myFeatureConfigClassContributor(ProjectDescription description) {
    return projectRoot -> {
        String packageName = description.getPackageName();
        String packagePath = packageName.replace('.', '/');
        Path configDir = projectRoot.resolve("src/main/java/" + packagePath + "/config");
        Files.createDirectories(configDir);

        // Read the template
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("templates/myfeature-config.mustache")) {
            String content = new String(in.readAllBytes())
                    .replace("{{packageName}}", packageName);
            Files.writeString(configDir.resolve("MyFeatureConfig.java"), content);
        }
    };
}
```

Add the import at the top of your class:
```java
import io.spring.initializr.generator.project.ProjectDescription;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
```

---

### Edit an Existing Static Config File

Just edit the file directly. No Java code changes needed. For example, to change the Kafka consumer group default:

**`src/main/resources/static-configs/kafka/application-kafka.yml`:**
```yaml
spring:
  kafka:
    consumer:
      group-id: my-new-default-group   # change this
```

Rebuild (`mvn package`) and restart. The next project generated with `kafka` selected will use the new defaults.

---

### Edit an Existing Generated Java Class Template

Edit the `.mustache` file. For example, to add a default topic to `KafkaConfig.java`:

**`src/main/resources/templates/kafka-config.mustache`:**
```java
// Add at the class level:
public static final String DEFAULT_TOPIC = "menora.events";
```

The `{{packageName}}` placeholder is the only dynamic value. Everything else is literal Java.

---

### Change the Artifactory URL

The Artifactory URL appears in two places:

**1. Where the Initializr app itself resolves artifacts** — `pom.xml`:
```xml
<repositories>
    <repository>
        <id>menora-release</id>
        <url>https://repo.menora.co.il/artifactory/libs-release</url>  <!-- change here -->
    </repository>
</repositories>
```

**2. What gets injected into generated projects** — two places:

`application.yml` (exposed to IntelliJ UI as available repositories):
```yaml
initializr:
  env:
    repositories:
      menora-release:
        url: https://repo.menora.co.il/artifactory/libs-release  # change here
```

`CommonProjectGenerationConfiguration.java` (what actually goes into the generated pom.xml):
```java
@Bean
BuildCustomizer<MavenBuild> artifactoryBuildCustomizer() {
    return build -> {
        build.repositories().add("menora-release",
            MavenRepository.withIdAndUrl("menora-release",
                "https://repo.menora.co.il/artifactory/libs-release")  // change here
                .name("Menora Artifactory Releases")
                .build());
    };
}
```

---

### Add a New BOM (Bill of Materials)

If you have an internal BOM that manages versions for a group of internal libraries, declare it in `application.yml`:

```yaml
initializr:
  env:
    boms:
      menora-internal-bom:
        groupId: com.menora.bom
        artifactId: menora-internal-bom
        version: 2.0.0
        repositories:
          - menora-release
```

Then reference it from a dependency:
```yaml
    - name: My Internal Lib
      id: my-internal-lib
      groupId: com.menora
      artifactId: my-internal-lib
      bom: menora-internal-bom   # version comes from the BOM
```

---

### Add a New Java Version Option

In `application.yml`:
```yaml
initializr:
  java-versions:
    - id: 21
      default: true
    - id: 17
    - id: 23      # add new version here
```

---

### Modify the Common Files (logback / .editorconfig)

These files are always injected into every generated project (no dependency condition).

- **Logback config:** `src/main/resources/static-configs/common/logback-spring.xml`
- **Editor config:** `src/main/resources/static-configs/common/.editorconfig`

Edit them directly. No Java code changes needed. They are copied verbatim into each generated project.

If you want to **stop injecting one of these files** (e.g., skip `.editorconfig`), remove the corresponding `@Bean` from `CommonProjectGenerationConfiguration.java`:

```java
// Remove or comment out this bean:
@Bean
ProjectContributor editorConfigContributor() { ... }
```

---

## Testing

Run all tests:
```bash
mvn test
```

The test suite covers:
- `/metadata/client` returns HTTP 200 with the full dependency catalog
- Every generated project contains `logback-spring.xml`, `.editorconfig`, and Artifactory repo in `pom.xml`
- Each dependency triggers its corresponding config injection
- Selecting `kafka` → `application-kafka.yml` + `KafkaConfig.java` present
- Not selecting `kafka` → those files absent
- Multiple dependencies simultaneously all inject correctly

To add a test for a new dependency you added, follow this pattern in `ProjectGenerationIntegrationTests.java`:

```java
@Test
void myFeatureDependencyInjectsConfig() throws Exception {
    WebProjectRequest request = createBaseRequest();
    request.getDependencies().add("my-dep-id");  // must match id in application.yml

    Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
    ProjectStructure project = new ProjectStructure(projectDir);

    assertThat(project).filePaths()
            .contains("src/main/resources/application-myfeature.yml")
            .contains("src/main/java/com/menora/demo/config/MyFeatureConfig.java");
}
```

---

## Dependency Catalog Reference

| ID | Name | Category | Custom Config Injected |
|----|------|----------|----------------------|
| `web` | Spring Web | Web | No |
| `webflux` | Spring Reactive Web | Web | No |
| `data-jpa` | Spring Data JPA | Data | `application-jpa.yml`, `JpaConfig.java` |
| `postgresql` | PostgreSQL Driver | Data | No |
| `kafka` | Spring for Apache Kafka | Messaging | `application-kafka.yml`, `KafkaConfig.java` |
| `security` | Spring Security | Security | `application-security.yml`, `SecurityConfig.java` |
| `actuator` | Spring Boot Actuator | Observability | `application-observability.yml` |
| `prometheus` | Micrometer Prometheus | Observability | No |
| `logging` | Menora Logging Standards | Logging | `application-logging.yml` |
| `rqueue` | Sonus Rqueue | Menora Standards | `application-rqueue.yml`, `RqueueConfig.java` |

---

## Architecture Reference

```
Request arrives
       │
       ▼
initializr-web (REST layer)
       │
       ▼
ProjectGenerationInvoker
       │  creates a child application context per generation request
       ▼
All registered ProjectGenerationConfigurations evaluated:
  ┌─────────────────────────────────────────────┐
  │ CommonProjectGenerationConfiguration        │ ← always runs
  │   → logback, .editorconfig, Artifactory repo│
  └─────────────────────────────────────────────┘
  ┌─────────────────────────────────────────────┐
  │ KafkaProjectGenerationConfiguration         │ ← only if "kafka" requested
  │   → application-kafka.yml, KafkaConfig.java │
  └─────────────────────────────────────────────┘
  ... (etc for each extension)
       │
       ▼
ProjectContributors write files to a temp directory
       │
       ▼
Temp directory zipped and returned as HTTP response
```

**Registration flow** — the framework discovers `ProjectGenerationConfiguration` classes via `META-INF/spring.factories`. Each one is a Spring `@Configuration` that is loaded in the child context for that generation request. `@ConditionalOnRequestedDependency("id")` gates the whole class on whether the user selected that dependency.
