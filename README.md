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
6. [Project Preview](#project-preview)
7. [Admin API](#admin-api)
   - [Hot-Reload Metadata](#hot-reload-metadata)
   - [Dependency Groups](#dependency-groups)
   - [Dependency Entries](#dependency-entries)
   - [File Contributions](#file-contributions)
   - [Build Customizations](#build-customizations)
   - [Sub-Options](#sub-options)
   - [Compatibility Rules](#compatibility-rules)
   - [Dependency Version Ranges](#dependency-version-ranges)
7. [Customization Guide](#customization-guide)
   - [Change the Spring Boot Version](#change-the-spring-boot-version)
   - [Change the Initializr Version](#change-the-initializr-version)
   - [Add a New Dependency (no custom config needed)](#add-a-new-dependency-no-custom-config-needed)
   - [Add a Static Config File to a Dependency](#add-a-static-config-file-to-a-dependency)
   - [Add a Generated Java Class to a Dependency](#add-a-generated-java-class-to-a-dependency)
   - [Add Sub-Options to a Dependency](#add-sub-options-to-a-dependency)
   - [Add a Compatibility Rule](#add-a-compatibility-rule)
   - [Restrict a Dependency to Specific Boot Versions](#restrict-a-dependency-to-specific-boot-versions)
   - [Edit an Existing Static Config File](#edit-an-existing-static-config-file)
   - [Edit an Existing Generated Java Class Template](#edit-an-existing-generated-java-class-template)
   - [Change the Artifactory URL](#change-the-artifactory-url)
   - [Add a New BOM (Bill of Materials)](#add-a-new-bom-bill-of-materials)
   - [Add a New Java Version Option](#add-a-new-java-version-option)
8. [Testing](#testing)
9. [Dependency Catalog Reference](#dependency-catalog-reference)
10. [Architecture Reference](#architecture-reference)

---

## How It Works

The application is built on the open-source [Spring Initializr framework](https://github.com/spring-io/initializr) (v0.23.x). It has three layers:

| Layer | What | Where |
|-------|------|-------|
| **Metadata** | Dependency catalog, versions, Artifactory URLs | H2 database (seeded from classpath on first start) |
| **Generation** | Custom config/code injection per dependency | `DynamicProjectGenerationConfiguration` reads rules from DB |
| **Web** | REST API understood by IntelliJ and curl | Inherited from `initializr-web` |

The dependency catalog, file contributions, build customizations, and sub-options all live in a persistent H2 database. On first startup, `DataSeeder` bootstraps the DB from the classpath resources in `static-configs/` and `templates/`. After that, all changes are made through the admin API — no code changes or restarts required.

---

## Project Structure

```
offline-spring-init/backend/
├── pom.xml                                      # Build: Spring Boot 3.2.1, initializr 0.23.x
├── src/main/
│   ├── java/com/menora/initializr/
│   │   ├── OfflineInitializrApplication.java    # Entry point
│   │   ├── admin/
│   │   │   └── AdminController.java             # REST CRUD for all DB tables + /admin/refresh
│   │   ├── config/
│   │   │   ├── DatabaseInitializrMetadataProvider.java  # Loads dep catalog from DB
│   │   │   ├── ExtensionMetadataController.java         # GET /metadata/extensions + /metadata/compatibility
│   │   │   ├── InitializrWebConfiguration.java          # Filter: opts-* params, format default
│   │   │   ├── MetadataProviderConfig.java              # Wires @Primary metadata provider bean
│   │   │   ├── ProjectOptionsContext.java               # ThreadLocal for sub-option selections
│   │   │   ├── ProjectPreviewConfig.java                # Ensures ProjectGenerationInvoker bean exists
│   │   │   └── ProjectPreviewController.java            # GET /starter.preview → JSON file tree
│   │   ├── db/
│   │   │   ├── DataSeeder.java                  # Seeds DB from classpath on first startup
│   │   │   ├── DependencyConfigService.java     # Query service for generation pipeline
│   │   │   ├── entity/                          # JPA entities (6 tables)
│   │   │   └── repository/                      # Spring Data repos (6 repos)
│   │   └── extension/dynamic/
│   │       └── DynamicProjectGenerationConfiguration.java  # Single config replacing 8 classes
│   └── resources/
│       ├── application.yml                      # Boot/Java versions, types, Artifactory URLs
│       ├── META-INF/spring.factories            # Registers DynamicProjectGenerationConfiguration
│       ├── templates/                           # Mustache-style templates → generated Java classes
│       │   ├── kafka-config.mustache
│       │   ├── kafka-consumer-example.mustache
│       │   ├── kafka-producer-example.mustache
│       │   ├── security-config.mustache
│       │   ├── jpa-config.mustache
│       │   ├── rqueue-config.mustache
│       │   ├── Dockerfile-java17.mustache
│       │   ├── Dockerfile-java21.mustache
│       │   ├── Jenkinsfile.mustache
│       │   ├── k8s-values.mustache
│       │   └── VERSION.mustache
│       └── static-configs/                      # YAML/XML files copied into generated projects
│           ├── common/log4j2-spring.xml
│           ├── common/.editorconfig
│           ├── common/entrypoint.sh
│           ├── common/settings.xml
│           ├── kafka/application-kafka.yml
│           ├── security/application-security.yml
│           ├── jpa/application-jpa.yml
│           ├── observability/application-observability.yml
│           ├── rqueue/application-rqueue.yml
│           └── logging/application-logging.yml
└── src/test/java/com/menora/initializr/
    └── ProjectGenerationIntegrationTests.java   # 12 integration tests
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

The service starts on **port 8080** by default. On first startup, `DataSeeder` populates the H2 database from classpath resources (takes a few seconds, logged at INFO).

The H2 database is persisted to `./data/initializr` (relative to the working directory).

To change the port:
```bash
java -jar target/offline-spring-init-1.0.0-SNAPSHOT.jar --server.port=9090
```

### Run with Docker

```bash
# Build the image
docker build -t menora/spring-init .

# Run the container (mount a volume to persist the H2 database)
docker run -p 8080:8080 -v /opt/menora/initializr-data:/app/data menora/spring-init
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
curl -H "Accept: application/json" http://localhost:8080/metadata/client
```

**Generate a project:**
```bash
# Web + Kafka
curl -o myproject.zip "http://localhost:8080/starter.zip?dependencies=web,kafka&groupId=com.menora&artifactId=myapp&packageName=com.menora.myapp"

# Web + Security + JPA + Actuator
curl -o myproject.zip "http://localhost:8080/starter.zip?dependencies=web,security,data-jpa,actuator&groupId=com.menora&artifactId=myapp"

# Kafka with sub-options (consumer + producer example classes)
curl -o myproject.zip "http://localhost:8080/starter.zip?dependencies=kafka&opts-kafka=consumer-example,producer-example&groupId=com.menora&artifactId=myapp"

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
| `log4j2-spring.xml` | `src/main/resources/log4j2-spring.xml` |
| `.editorconfig` | `.editorconfig` (project root) |
| `entrypoint.sh` | `entrypoint.sh` (project root) |
| `settings.xml` | `settings.xml` (project root) |
| `VERSION` | `VERSION` (project root, contains the version string) |
| `Dockerfile` | `Dockerfile` (version-specific: java17 or java21 template) |
| `Jenkinsfile` | `k8s/Jenkinsfile` |
| `values.yaml` | `k8s/values.yaml` |
| Artifactory repos | Inside the generated `pom.xml` |
| log4j2 dependency | `spring-boot-starter-log4j2` added, `spring-boot-starter-logging` excluded from `pom.xml` |
| Lombok | `lombok` added to `pom.xml` |
| `application.properties` | **Deleted** (framework writes it; we remove it) |

### Conditional (based on selected dependencies)

All per-dependency YAML config is **deep-merged** into `src/main/resources/application.yaml`. Selecting multiple dependencies (e.g. kafka + security + jpa) produces a single `application.yaml` that contains all their settings combined.

| Dependency ID | Files Injected |
|--------------|----------------|
| `kafka` | `application.yaml` (kafka section merged)<br>`src/main/java/.../config/KafkaConfig.java`<br>*(optional)* `KafkaConsumerExample.java` (sub-option: `consumer-example`)<br>*(optional)* `KafkaProducerExample.java` (sub-option: `producer-example`) |
| `security` | `application.yaml` (security section merged)<br>`src/main/java/.../config/SecurityConfig.java` |
| `data-jpa` | `application.yaml` (datasource section merged)<br>`src/main/java/.../config/JpaConfig.java` |
| `actuator` | `application.yaml` (management section merged) |
| `rqueue` | `application.yaml` (rqueue section merged)<br>`src/main/java/.../config/RqueueConfig.java` |
| `logging` | `application.yaml` (logging section merged) |

---

## Project Preview

Before downloading a ZIP you can inspect the full generated file tree and the contents of every file. Click the **Explore** button in the top-right corner of the UI — a full-screen modal opens with:

- **Left panel** — collapsible file tree (all folders expanded by default)
- **Right panel** — file content viewer with line numbers; click any file in the tree to display it
- **Download ZIP** button in the header to proceed with the actual download

### Preview API

The preview endpoint accepts the same query parameters as `/starter.zip`:

```bash
curl "http://localhost:8080/starter.preview?\
type=maven-project&language=java&bootVersion=3.2.1&\
groupId=com.menora&artifactId=demo&packageName=com.menora.demo&\
packaging=jar&javaVersion=21&dependencies=web,kafka" | python -m json.tool
```

Response shape:
```json
{
  "files": [
    { "path": "pom.xml",                                    "content": "..." },
    { "path": "src/main/java/com/menora/demo/DemoApplication.java", "content": "..." }
  ],
  "tree": [
    { "name": "pom.xml", "path": "pom.xml", "type": "file", "children": [] },
    { "name": "src",     "path": "src",     "type": "directory", "children": [
      { "name": "main", "path": "src/main", "type": "directory", "children": [ "..." ] }
    ]}
  ]
}
```

Sub-options work exactly as with `/starter.zip` — append `opts-{depId}=opt1,opt2`:
```bash
curl "http://localhost:8080/starter.preview?dependencies=kafka&opts-kafka=consumer-example&..."
```

---

## Admin API

The admin API manages the database that drives project generation. All changes take effect immediately after calling `/admin/refresh` — no restart needed.

> **Note:** The admin API has no authentication. Restrict access at the network or reverse-proxy level.

### Hot-Reload Metadata

```bash
# After any DB change, reload the dependency metadata cache:
POST /admin/refresh
```

```bash
curl -X POST http://localhost:8080/admin/refresh
# → "Metadata refreshed from database"
```

### Dependency Groups

Groups are categories shown in the UI (e.g. "Web", "Data", "Messaging").

```bash
GET    /admin/dependency-groups        # list all
POST   /admin/dependency-groups        # create
PUT    /admin/dependency-groups/{id}   # update
DELETE /admin/dependency-groups/{id}   # delete
```

Example — create a group:
```bash
curl -X POST http://localhost:8080/admin/dependency-groups \
  -H "Content-Type: application/json" \
  -d '{"name": "Caching", "sortOrder": 7}'
```

### Dependency Entries

Individual dependencies within a group.

```bash
GET    /admin/dependency-entries        # list all
POST   /admin/dependency-entries        # create
PUT    /admin/dependency-entries/{id}   # update
DELETE /admin/dependency-entries/{id}   # delete
```

Example — add a dependency with no custom injection (Spring Boot manages its coordinates):
```bash
curl -X POST http://localhost:8080/admin/dependency-entries \
  -H "Content-Type: application/json" \
  -d '{
    "group": {"id": 3},
    "depId": "cache",
    "name": "Spring Cache Abstraction",
    "description": "Spring caching support",
    "sortOrder": 0
  }'
```

Example — add a dependency with explicit coordinates from Menora Artifactory:
```bash
curl -X POST http://localhost:8080/admin/dependency-entries \
  -H "Content-Type: application/json" \
  -d '{
    "group": {"id": 1},
    "depId": "my-lib",
    "name": "My Internal Lib",
    "description": "Internal Menora library",
    "mavenGroupId": "com.menora.internal",
    "mavenArtifactId": "my-internal-lib",
    "version": "2.1.0",
    "repository": "menora-release",
    "sortOrder": 5
  }'
```

After creating, call `/admin/refresh` so the UI picks it up.

### File Contributions

Rules that write, merge, or delete files in generated projects. Every rule is tied to a `dependencyId` — use `__common__` for files injected into every project.

```bash
GET    /admin/file-contributions        # list all
POST   /admin/file-contributions        # create
PUT    /admin/file-contributions/{id}   # update
DELETE /admin/file-contributions/{id}   # delete
```

**Field reference:**

| Field | Values | Description |
|-------|--------|-------------|
| `dependencyId` | any dep ID, or `__common__` | When this dep is selected, this rule applies |
| `fileType` | `STATIC_COPY` | Write content verbatim to `targetPath` |
| | `YAML_MERGE` | Deep-merge content into the target YAML file |
| | `TEMPLATE` | Apply variable substitution, then write |
| | `DELETE` | Delete `targetPath` (runs after all writes) |
| `substitutionType` | `NONE` | No substitution |
| | `PROJECT` | Replace `{{artifactId}}`, `{{groupId}}`, `{{version}}` |
| | `PACKAGE` | Replace `{{packageName}}` |
| `targetPath` | path string | Destination in the generated project. May contain `{{packagePath}}` (e.g. `src/main/java/{{packagePath}}/config/MyConfig.java`) |
| `javaVersion` | `"17"`, `"21"`, or `null` | If set, only apply when the project's Java version matches |
| `subOptionId` | string or `null` | If set, only apply when this sub-option is selected |
| `sortOrder` | integer | Lower numbers run first |

Example — add a YAML merge rule for a new dependency:
```bash
curl -X POST http://localhost:8080/admin/file-contributions \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "cache",
    "fileType": "YAML_MERGE",
    "content": "spring:\n  cache:\n    type: redis\n",
    "targetPath": "src/main/resources/application.yaml",
    "substitutionType": "NONE",
    "sortOrder": 0
  }'
```

Example — add a Java class template for a new dependency:
```bash
curl -X POST http://localhost:8080/admin/file-contributions \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "cache",
    "fileType": "TEMPLATE",
    "content": "package {{packageName}}.config;\n\nimport org.springframework.context.annotation.Configuration;\n\n@Configuration\npublic class CacheConfig {\n}\n",
    "targetPath": "src/main/java/{{packagePath}}/config/CacheConfig.java",
    "substitutionType": "PACKAGE",
    "sortOrder": 1
  }'
```

### Build Customizations

Rules that modify the generated `pom.xml`. Also tied to a `dependencyId` (use `__common__` for always-applied rules).

```bash
GET    /admin/build-customizations        # list all
POST   /admin/build-customizations        # create
PUT    /admin/build-customizations/{id}   # update
DELETE /admin/build-customizations/{id}   # delete
```

**Customization types:**

`ADD_DEPENDENCY` — add a Maven dependency:
```bash
curl -X POST http://localhost:8080/admin/build-customizations \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "cache",
    "customizationType": "ADD_DEPENDENCY",
    "mavenGroupId": "org.springframework.boot",
    "mavenArtifactId": "spring-boot-starter-data-redis",
    "sortOrder": 0
  }'
```

`EXCLUDE_DEPENDENCY` — add a dependency with an exclusion:
```bash
curl -X POST http://localhost:8080/admin/build-customizations \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "__common__",
    "customizationType": "EXCLUDE_DEPENDENCY",
    "excludeFromGroupId": "org.springframework.boot",
    "excludeFromArtifactId": "spring-boot-starter",
    "mavenGroupId": "org.springframework.boot",
    "mavenArtifactId": "spring-boot-starter-logging",
    "sortOrder": 2
  }'
```

`ADD_REPOSITORY` — add a Maven repository:
```bash
curl -X POST http://localhost:8080/admin/build-customizations \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "__common__",
    "customizationType": "ADD_REPOSITORY",
    "repoId": "menora-release",
    "repoName": "Menora Artifactory Releases",
    "repoUrl": "https://repo.menora.co.il/artifactory/libs-release",
    "snapshotsEnabled": false,
    "sortOrder": 0
  }'
```

### Sub-Options

Optional extras within a dependency (e.g. "Consumer Example" for Kafka). Displayed as checkboxes in the UI after selecting the parent dependency.

```bash
GET    /admin/sub-options        # list all
POST   /admin/sub-options        # create
PUT    /admin/sub-options/{id}   # update
DELETE /admin/sub-options/{id}   # delete
```

```bash
curl -X POST http://localhost:8080/admin/sub-options \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "cache",
    "optionId": "redis-example",
    "label": "Redis Example",
    "description": "Add a RedisExample.java class",
    "sortOrder": 0
  }'
```

The sub-option only controls visibility in the UI. For it to actually inject a file, create a `FileContributionEntity` with the matching `dependencyId` and `subOptionId`.

### Compatibility Rules

Compatibility rules teach the UI about relationships between dependencies and display inline warnings as users build their selection. Three relationship types are supported:

| Type | UI behaviour |
|------|-------------|
| `CONFLICTS` | Red warning banner — "these two deps conflict, choose one" |
| `REQUIRES` | Yellow banner with an **Add** button — "source needs target to work" |
| `RECOMMENDS` | Blue banner with an **Add** button — "target is suggested alongside source" |

```bash
GET    /admin/compatibility        # list all rules
POST   /admin/compatibility        # create
PUT    /admin/compatibility/{id}   # update
DELETE /admin/compatibility/{id}   # delete
```

**Field reference:**

| Field | Description |
|-------|-------------|
| `sourceDepId` | The dependency that triggers the rule (e.g. `web`) |
| `targetDepId` | The dependency the rule points to (e.g. `webflux`) |
| `relationType` | `CONFLICTS`, `REQUIRES`, or `RECOMMENDS` |
| `description` | Human-readable message shown in the warning banner |
| `sortOrder` | Display order (lower = first) |

Example — mark two dependencies as conflicting:
```bash
curl -X POST http://localhost:8080/admin/compatibility \
  -H "Content-Type: application/json" \
  -d '{
    "sourceDepId": "web",
    "targetDepId": "webflux",
    "relationType": "CONFLICTS",
    "description": "Spring MVC and WebFlux use incompatible server models — choose one",
    "sortOrder": 0
  }'
```

Example — declare that one dependency requires another:
```bash
curl -X POST http://localhost:8080/admin/compatibility \
  -H "Content-Type: application/json" \
  -d '{
    "sourceDepId": "rqueue",
    "targetDepId": "data-jpa",
    "relationType": "REQUIRES",
    "description": "Sonus Rqueue requires a JPA datasource to persist job state",
    "sortOrder": 1
  }'
```

Rules are served to the browser at `GET /metadata/compatibility` (no auth required). The **Selected Dependencies** panel updates live as the user selects or removes dependencies — no page reload needed.

> **Note:** Rules are directional. A CONFLICTS rule from `web` → `webflux` does **not** automatically create the reverse. Add both directions if you want the warning to appear regardless of which dependency was selected first.

**Seeded defaults** (applied on first startup when the DB is empty):

| Source | Relation | Target | Reason |
|--------|----------|--------|--------|
| `web` | CONFLICTS | `webflux` | Incompatible server models |
| `data-jpa` | RECOMMENDS | `postgresql` | JPA needs a database driver |
| `actuator` | RECOMMENDS | `prometheus` | Metrics export for Prometheus |
| `rqueue` | REQUIRES | `data-jpa` | Rqueue persists state via JPA |
| `security` | REQUIRES | `web` | Security needs a web layer |

### Dependency Version Ranges

Each dependency entry has an optional `compatibilityRange` field. When set, the Spring Initializr framework automatically filters that dependency out of the metadata for any selected Boot version that falls outside the range, and the UI shows a version badge (e.g. **Boot [3.2.0,4.0.0)**) next to the dependency name.

**Range syntax** — mathematical interval notation:

| Syntax | Meaning |
|--------|---------|
| `[3.2.0,4.0.0)` | Boot ≥ 3.2.0 and < 4.0.0 (inclusive lower, exclusive upper — most common) |
| `3.2.0` | Boot ≥ 3.2.0 (open upper bound) |
| `[3.2.0,3.3.0]` | Boot ≥ 3.2.0 and ≤ 3.3.0 (inclusive on both ends) |

A blank or null range means the dependency is compatible with all Boot versions (the default).

Set via the admin API:
```bash
curl -X PUT http://localhost:8080/admin/dependency-entries/{id} \
  -H "Content-Type: application/json" \
  -d '{
    ...,
    "compatibilityRange": "[3.2.0,4.0.0)"
  }'
curl -X POST http://localhost:8080/admin/refresh
```

Or edit it in the Admin UI: **Configuration → Dependencies → edit → Compatibility Range**.

The range is validated by the framework at metadata-build time — a malformed range causes `/admin/refresh` to throw immediately with a clear error rather than silently producing bad metadata.

**Seeded example:** `rqueue` is seeded with `[3.2.0,4.0.0)`. It will be hidden from the catalog if a Spring Boot 4.x version is added to `application.yml`.

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

---

### Change the Initializr Version

In `pom.xml`:
```xml
<properties>
    <spring-initializr.version>0.23.0</spring-initializr.version>  <!-- change this -->
</properties>
```

---

### Add a New Dependency (no custom config needed)

Use the Admin API — no code changes, no restart.

```bash
# 1. Find or create the group
curl http://localhost:8080/admin/dependency-groups
# Use an existing group id, or POST a new one

# 2. Create the dependency entry (Spring Boot manages the coordinates)
curl -X POST http://localhost:8080/admin/dependency-entries \
  -H "Content-Type: application/json" \
  -d '{
    "group": {"id": 2},
    "depId": "hateoas",
    "name": "Spring HATEOAS",
    "description": "Hypermedia links in REST responses",
    "sortOrder": 2
  }'

# 3. Refresh metadata
curl -X POST http://localhost:8080/admin/refresh
```

For a library **not** managed by Spring Boot's BOM, include `mavenGroupId`, `mavenArtifactId`, and `version` in the entry body.

For a library from **Menora Artifactory** (not in Maven Central), also add `"repository": "menora-release"`.

---

### Add a Static Config File to a Dependency

To inject a YAML or XML file into a generated project when a dependency is selected:

**Option A — Admin API (no restart):**
```bash
curl -X POST http://localhost:8080/admin/file-contributions \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "my-dep-id",
    "fileType": "YAML_MERGE",
    "content": "myfeature:\n  enabled: true\n  endpoint: ${MYFEATURE_URL:http://localhost:9999}\n",
    "targetPath": "src/main/resources/application.yaml",
    "substitutionType": "NONE",
    "sortOrder": 0
  }'
```

**Option B — Permanent (survives a fresh DB):**

1. Create `src/main/resources/static-configs/myfeature/application-myfeature.yml`
2. Add a call in `DataSeeder.seedDependencyFileContributions()`:
   ```java
   fc("my-dep-id", FileContributionEntity.FileType.YAML_MERGE,
       readClasspath("static-configs/myfeature/application-myfeature.yml"),
       "src/main/resources/application.yaml",
       FileContributionEntity.SubstitutionType.NONE, null, null, 0);
   ```
3. Rebuild and restart (DataSeeder runs only when DB is empty, so delete `./data/` first to re-seed, or call the admin API directly)

---

### Add a Generated Java Class to a Dependency

To inject a Java class (with the project's package name substituted in):

**Option A — Admin API (no restart):**
```bash
curl -X POST http://localhost:8080/admin/file-contributions \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "my-dep-id",
    "fileType": "TEMPLATE",
    "content": "package {{packageName}}.config;\n\nimport org.springframework.context.annotation.Configuration;\n\n@Configuration\npublic class MyFeatureConfig {\n}\n",
    "targetPath": "src/main/java/{{packagePath}}/config/MyFeatureConfig.java",
    "substitutionType": "PACKAGE",
    "sortOrder": 1
  }'
```

**Option B — Permanent (survives a fresh DB):**

1. Create `src/main/resources/templates/myfeature-config.mustache`:
   ```java
   package {{packageName}}.config;

   import org.springframework.context.annotation.Configuration;

   @Configuration
   public class MyFeatureConfig {
   }
   ```
2. Add a call in `DataSeeder.seedDependencyFileContributions()`:
   ```java
   fc("my-dep-id", FileContributionEntity.FileType.TEMPLATE,
       readClasspath("templates/myfeature-config.mustache"),
       "src/main/java/{{packagePath}}/config/MyFeatureConfig.java",
       FileContributionEntity.SubstitutionType.PACKAGE, null, null, 1);
   ```
3. Rebuild and restart (or delete `./data/` to re-seed)

---

### Add Sub-Options to a Dependency

Sub-options are optional extras the user can tick after selecting a dependency. Selecting Kafka + ticking "Consumer Example" injects a `KafkaConsumerExample.java` into the generated project.

**Step 1 — Register the sub-option:**
```bash
curl -X POST http://localhost:8080/admin/sub-options \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "my-dep-id",
    "optionId": "my-option",
    "label": "My Option",
    "description": "Add a MyOptionExample.java class",
    "sortOrder": 0
  }'
```

**Step 2 — Create a file contribution gated on the sub-option:**
```bash
curl -X POST http://localhost:8080/admin/file-contributions \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "my-dep-id",
    "fileType": "TEMPLATE",
    "content": "package {{packageName}}.config;\n\n@Component\npublic class MyOptionExample {}\n",
    "targetPath": "src/main/java/{{packagePath}}/config/MyOptionExample.java",
    "substitutionType": "PACKAGE",
    "subOptionId": "my-option",
    "sortOrder": 2
  }'
```

**Step 3 — Refresh:**
```bash
curl -X POST http://localhost:8080/admin/refresh
```

The UI calls `GET /metadata/extensions` to discover sub-options and renders checkboxes automatically. When the user selects one, the UI appends `opts-{depId}=my-option` to the generation request URL.

---

### Add a Compatibility Rule

Use the admin API or the **Compatibility** tab in the admin UI:

```bash
# Warn when both deps are selected together
curl -X POST http://localhost:8080/admin/compatibility \
  -H "Content-Type: application/json" \
  -d '{
    "sourceDepId": "cache",
    "targetDepId": "data-jpa",
    "relationType": "RECOMMENDS",
    "description": "Caching often pairs with JPA to reduce database load",
    "sortOrder": 0
  }'
```

Rules take effect immediately — no refresh needed. The browser fetches `/metadata/compatibility` on page load and re-evaluates warnings client-side on every selection change.

For a rule that should survive a fresh DB, add a call to `DataSeeder.seedCompatibilityRules()` using the `compatibility(source, target, type, desc, order)` helper.

---

### Edit an Existing Static Config File

**Quick edit (no restart):** Fetch the file contribution record, update its `content` field, and PUT it back:

```bash
# Find the record
curl http://localhost:8080/admin/file-contributions | python -m json.tool | grep -A5 '"kafka"'

# Update content (replace {id} with the actual record ID)
curl -X PUT http://localhost:8080/admin/file-contributions/{id} \
  -H "Content-Type: application/json" \
  -d '{...full record with updated content...}'
```

**Permanent edit (survives a fresh DB):** Edit `src/main/resources/static-configs/kafka/application-kafka.yml` directly. Changes take effect for new DB installs (re-seeding).

---

### Edit an Existing Generated Java Class Template

Same approach as above: update the `content` field of the relevant `FileContributionEntity` via the admin API, or edit the `.mustache` file for permanent changes.

---

### Change the Artifactory URL

The Artifactory URL appears in three places that must be kept in sync:

**1. Where the Initializr app itself resolves artifacts** — `pom.xml`:
```xml
<repositories>
    <repository>
        <id>menora-release</id>
        <url>https://repo.menora.co.il/artifactory/libs-release</url>  <!-- change here -->
    </repository>
</repositories>
```

**2. What gets injected into generated projects** — update the `BUILD_CUSTOMIZATION` record for `__common__` via the admin API:
```bash
curl http://localhost:8080/admin/build-customizations
# Find the ADD_REPOSITORY record with repoId="menora-release", note its {id}

curl -X PUT http://localhost:8080/admin/build-customizations/{id} \
  -H "Content-Type: application/json" \
  -d '{...record with updated repoUrl...}'
```

And for permanent seeding, update `DataSeeder.seedBuildCustomizations()`.

**3. Exposed to IntelliJ as an available repository** — `application.yml`:
```yaml
initializr:
  env:
    repositories:
      menora-release:
        url: https://repo.menora.co.il/artifactory/libs-release  # change here
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

Then reference it from a dependency entry by including `"bom": "menora-internal-bom"` — the version comes from the BOM. (BOM config is still YAML-only; it is not yet stored in the database.)

---

### Restrict a Dependency to Specific Boot Versions

Use the `compatibilityRange` field on a dependency entry to control which Boot versions it appears for.

**Via admin API (no restart):**
```bash
# Find the entry's numeric id
curl http://localhost:8080/admin/dependency-entries | python -m json.tool

# Set the range — replaces the whole record, so include all existing fields
curl -X PUT http://localhost:8080/admin/dependency-entries/{id} \
  -H "Content-Type: application/json" \
  -d '{
    "group": {"id": 1},
    "depId": "rqueue",
    "name": "Sonus Rqueue",
    "description": "Sonus Rqueue messaging library",
    "mavenGroupId": "com.sonus",
    "mavenArtifactId": "sonus-rqueue",
    "version": "1.0.0",
    "repository": "menora-release",
    "compatibilityRange": "[3.2.0,4.0.0)",
    "sortOrder": 0
  }'

curl -X POST http://localhost:8080/admin/refresh
```

**Permanently (survives a fresh DB):** after the `entry(...)` call in `DataSeeder.seedDependencyCatalog()`, look up the entry and set the range:
```java
entryRepo.findAll().stream()
    .filter(e -> "rqueue".equals(e.getDepId()))
    .findFirst()
    .ifPresent(e -> { e.setCompatibilityRange("[3.2.0,4.0.0)"); entryRepo.save(e); });
```

To remove a range (make the dep visible for all versions), PUT the record with `"compatibilityRange": null` or `""` and refresh.

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

If you add a new Java version that needs a distinct Dockerfile, create `templates/Dockerfile-java23.mustache` and add a file contribution:
```bash
curl -X POST http://localhost:8080/admin/file-contributions \
  -H "Content-Type: application/json" \
  -d '{
    "dependencyId": "__common__",
    "fileType": "TEMPLATE",
    "content": "...",
    "targetPath": "Dockerfile",
    "substitutionType": "PROJECT",
    "javaVersion": "23",
    "sortOrder": 7
  }'
```

---

## Testing

Run all tests:
```bash
mvn test
```

Tests use an in-memory H2 database (`src/test/resources/application.properties`). `DataSeeder` seeds it automatically at startup, so the full DB-driven pipeline is exercised — no mocking.

**Test coverage (`ProjectGenerationIntegrationTests`):**
- `metadataEndpointReturnsOk` — `/metadata/client` returns HTTP 200 with `kafka` and `rqueue` in catalog
- `generatedProjectContainsArtifactoryRepo` — Artifactory repos present in generated `pom.xml`
- `generatedProjectContainsVersionDockerfileAndK8s` — VERSION, Dockerfile, Jenkinsfile, k8s/values.yaml injected
- `generatedProjectContainsLog4j2` — `log4j2-spring.xml` present, `logback-spring.xml` absent, `spring-boot-starter-log4j2` in pom
- `generatedProjectContainsEditorconfig` — `.editorconfig` present, `application.properties` absent
- `kafkaDependencyInjectsConfigFiles` — `application.yaml` contains `bootstrap-servers`, `KafkaConfig.java` present
- `withoutKafkaDependencyNoKafkaFiles` — `KafkaConfig.java` absent when kafka not selected
- `securityDependencyInjectsSecurityConfig` — `application.yaml` + `SecurityConfig.java`
- `jpaDependencyInjectsJpaConfig` — `application.yaml` + `JpaConfig.java`
- `actuatorDependencyInjectsObservabilityConfig` — `application.yaml` contains `management`
- `rqueueDependencyInjectsRqueueConfig` — `application.yaml` + `RqueueConfig.java`
- `multipleDependenciesInjectAllConfigs` — kafka + security + jpa + actuator combined, all files present

To add a test for a new dependency:
```java
@Test
void myDepInjectsConfig() throws Exception {
    WebProjectRequest request = createBaseRequest();
    request.getDependencies().add("my-dep-id");

    Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
    ProjectStructure project = new ProjectStructure(projectDir);

    assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
            .contains("my-key");
    assertThat(project).filePaths()
            .contains("src/main/java/com/menora/demo/config/MyFeatureConfig.java");
}
```

---

## Dependency Catalog Reference

| ID | Name | Category | Config Injected into `application.yaml` | Java Class | Boot Range |
|----|------|----------|-----------------------------------------|------------|------------|
| `web` | Spring Web | Web | — | — | — |
| `webflux` | Spring Reactive Web | Web | — | — | — |
| `data-jpa` | Spring Data JPA | Data | datasource, JPA properties | `JpaConfig.java` | — |
| `postgresql` | PostgreSQL Driver | Data | — | — | — |
| `kafka` | Spring for Apache Kafka | Messaging | kafka (bootstrap-servers, producer, consumer) | `KafkaConfig.java`<br>*(opt)* `KafkaConsumerExample.java`<br>*(opt)* `KafkaProducerExample.java` | — |
| `security` | Spring Security | Security | oauth2 resource server | `SecurityConfig.java` | — |
| `actuator` | Spring Boot Actuator | Observability | management endpoints | — | — |
| `prometheus` | Micrometer Prometheus | Observability | — | — | — |
| `logging` | Menora Logging Standards | Logging | logging config | — | — |
| `rqueue` | Sonus Rqueue | Menora Standards | rqueue properties | `RqueueConfig.java` | `[3.2.0,4.0.0)` |

---

## Architecture Reference

```
Request arrives  (?dependencies=kafka&opts-kafka=consumer-example)
       │
       ▼
InitializrWebConfiguration  (@Order MIN_VALUE servlet filter)
       │  ① populates ProjectOptionsContext (ThreadLocal) with opts-* params
       │  ② injects configurationFileFormat=properties default
       │  ③ sanitizes X-Forwarded-Port header
       ▼
initializr-web (REST layer)
       │  resolves metadata from DatabaseInitializrMetadataProvider
       │  (dependency catalog loaded from H2 DB, cached, refreshable via /admin/refresh)
       ▼
ProjectGenerationInvoker
       │  creates a child application context per generation request
       ▼
DynamicProjectGenerationConfiguration  (the only registered @ProjectGenerationConfiguration)
  ┌────────────────────────────────────────────────────────────────────────────┐
  │ dynamicBuildCustomizer                                                     │
  │   → reads BuildCustomizationEntity rows for selected deps + __common__     │
  │   → adds/excludes dependencies, adds repositories in pom.xml              │
  ├────────────────────────────────────────────────────────────────────────────┤
  │ dynamicFileContributor                                                     │
  │   → reads FileContributionEntity rows for selected deps + __common__       │
  │   → for each row: applies javaVersion and subOptionId gates               │
  │   → STATIC_COPY: writes verbatim                                          │
  │   → YAML_MERGE:  deep-merges into application.yaml                        │
  │   → TEMPLATE:    substitutes {{artifactId}}/{{packageName}} then writes    │
  ├────────────────────────────────────────────────────────────────────────────┤
  │ dynamicDeleteContributor  (@Order LOWEST_PRECEDENCE)                       │
  │   → runs last, after framework writes application.properties               │
  │   → deletes files registered with DELETE type                             │
  └────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
Temp directory zipped → filter clears ProjectOptionsContext → HTTP response
```

**Database tables:**

| Table | Purpose |
|-------|---------|
| `dependency_group` | Categories (Web, Data, Messaging, …) |
| `dependency_entry` | Individual dependencies with Maven coordinates |
| `file_contribution` | Files/YAML to inject per dependency |
| `build_customization` | pom.xml modifications per dependency |
| `dependency_sub_option` | Optional extras within a dependency |
| `dependency_compatibility` | REQUIRES / CONFLICTS / RECOMMENDS rules between deps |

**First-startup seeding:**
`DataSeeder` (a `CommandLineRunner`) runs when all tables are empty. It reads every classpath resource from `static-configs/` and `templates/` and inserts them as DB records. After seeding, operators manage everything through the admin API. To reset to defaults: stop the app, delete `./data/`, restart.
