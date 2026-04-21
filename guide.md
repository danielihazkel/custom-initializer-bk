# How to Add a New Dependency to Menora Spring Initializr

This guide walks through adding a new dependency that injects Maven dependencies, configuration files, and Java classes into generated projects.

## Overview

The system is **database-driven** — all dependency definitions, files, and build customizations live in the DB. You can add everything through the **Admin UI** (no code changes, no restart needed). If you also want changes to survive a fresh database, update `DataSeeder.java` as well.

## Method 1: Admin UI (Runtime — No Restart)

### Step 1: Create a Dependency Group (if needed)

Go to **Admin → Dependency Groups** tab → **New Group**.

| Field | Example |
|-------|---------|
| Name | `Communication` |
| Sort Order | `7` |

Skip this step if a suitable group already exists.

### Step 2: Create the Dependency Entry

Go to **Admin → Dependencies** tab → **New Entry**.

| Field | Example | Notes |
|-------|---------|-------|
| Group | `Communication` | select from dropdown |
| Dependency ID | `mail-sampler` | unique key — used in URL params (`?dependencies=mail-sampler`) |
| Display Name | `Mail Sampler` | shown in the UI dependency picker |
| Description | `Outlook mail integration` | tooltip text |
| Maven Group ID | *(leave blank)* | only if you want the framework to auto-add one "primary" dependency |
| Maven Artifact ID | *(leave blank)* | same as above |
| Compatibility Range | *(leave blank)* | blank = all Boot versions; or use `[3.2.0,4.0.0)` syntax |
| Sort Order | `0` | display order within the group |

> **Tip:** Leave Maven fields blank if you'll add dependencies via Build Customizations (Step 4). This gives you control over multiple dependencies.

### Step 3: Add File Contributions

Go to **Admin → File Contributions** tab. Create **one record per file** to inject.

#### 3a. YAML config (merged into `application.yaml`)

| Field | Value |
|-------|-------|
| Dependency ID | `mail-sampler` |
| File Type | `YAML_MERGE` |
| Target Path | `src/main/resources/application.yaml` |
| Substitution Type | `NONE` |
| Content | your YAML config block (see example below) |

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.office365.com}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
```

#### 3b. Java config class

| Field | Value |
|-------|-------|
| Dependency ID | `mail-sampler` |
| File Type | `TEMPLATE` |
| Target Path | `src/main/java/{{packagePath}}/config/MailConfig.java` |
| Substitution Type | `MUSTACHE` |
| Content | your Java class (use `{{packageName}}` for the package declaration) |

```java
package {{packageName}}.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class MailConfig {
    // your config here
}
```

#### 3c. Service class (optional)

Same pattern — `File Type: TEMPLATE`, `Substitution Type: MUSTACHE`, target path under `{{packagePath}}/service/`.

### Step 4: Add Build Customizations (Maven dependencies)

Go to **Admin → Build Customizations** tab → **New Customization**.

| Field | Value |
|-------|-------|
| Dependency ID | `mail-sampler` |
| Customization Type | `ADD_DEPENDENCY` |
| Maven Group ID | `org.springframework.boot` |
| Maven Artifact ID | `spring-boot-starter-mail` |
| Version | *(leave blank for Spring-managed)* |

Repeat for each additional Maven dependency you need.

### Step 5: Add Sub-Options (optional)

Sub-options are checkboxes within a dependency. For example, "Include HTML email example".

Go to **Admin → Sub-Options** tab → **New Sub-Option**.

| Field | Value |
|-------|-------|
| Dependency ID | `mail-sampler` |
| Option ID | `html-example` |
| Label | `HTML Email Example` |
| Description | `Add an HtmlEmailService example class` |

Then create a **File Contribution** with `Sub-Option ID = html-example`. That file will only be included when the user checks the sub-option.

### Step 6: Add Compatibility Rules (optional)

Go to **Admin → Compatibility** tab to define relationships between dependencies:

| Relation Type | Meaning |
|---------------|---------|
| `REQUIRES` | Selection auto-adds the target dependency |
| `RECOMMENDS` | Shows a suggestion to the user |
| `CONFLICTS` | Prevents selecting both dependencies |

### Step 7: Refresh Metadata

Click the **"Refresh Metadata"** button in the admin UI, or call:

```bash
curl -X POST http://localhost:8080/admin/refresh
```

**No server restart needed.** The dependency appears immediately in the UI.

---

## Method 2: DataSeeder (Permanent — Survives Fresh DB)

To make a dependency part of the default seed (new deployments, fresh databases), edit `backend/src/main/java/com/menora/initializr/db/DataSeeder.java`.

### Step 1: Add classpath resource files

Create these files under `src/main/resources/`:

```
static-configs/<dep-id>/application-<name>.yml    # YAML config
templates/<dep-id>-config.mustache                 # Java config class template
templates/<dep-id>-service.mustache                # Service class template (optional)
```

Template files use `{{packageName}}` for the Java package and `{{packagePath}}` for the directory path. These are **not** real Mustache — just simple string replacement.

### Step 2: Register the dependency in `seedDependencyCatalog()`

```java
DependencyGroupEntity myGroup = group("My Group", 7);
entry(myGroup, "my-dep", "My Dependency", "Description",
      null, null, null, null, null, 0);
```

Parameters: `(group, depId, name, description, mavenGroupId, mavenArtifactId, version, scope, repository, sortOrder)`

### Step 3: Add file contributions in `seedDependencyFileContributions()`

```java
// YAML config
fc("my-dep", FileContributionEntity.FileType.YAML_MERGE,
        readClasspath("static-configs/my-dep/application-my-dep.yml"),
        "src/main/resources/application.yaml",
        FileContributionEntity.SubstitutionType.NONE, null, null, 0);

// Java config class
fc("my-dep", FileContributionEntity.FileType.TEMPLATE,
        readClasspath("templates/my-dep-config.mustache"),
        "src/main/java/{{packagePath}}/config/MyDepConfig.java",
        FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);
```

Parameters: `(depId, fileType, content, targetPath, substitutionType, javaVersion, subOptionId, sortOrder)`

### Step 4: Add build customizations in `seedBuildCustomizations()`

```java
addDep("my-dep", "org.example", "my-library", "1.0.0", null, 0);
```

### Step 5: Add sub-options in `seedSubOptions()` (optional)

```java
subOption("my-dep", "example-code", "Include Example", "Add example class", 0);
```

Then add a file contribution with `subOptionId = "example-code"` — it will only be included when the user selects that sub-option.

### Step 6: Add compatibility rules in `seedCompatibilityRules()` (optional)

```java
compatibility("my-dep", "web", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
        "My Dep works best with Spring Web", 5);
```

---

## Template Variable Reference

### Substitution Type: `MUSTACHE`

TEMPLATE contributions with `substitutionType = MUSTACHE` are rendered through jmustache. The context exposes:

| Variable | Resolves To | Example |
|----------|-------------|---------|
| `{{artifactId}}` | Project artifact ID | `demo` |
| `{{groupId}}` | Project group ID | `com.menora` |
| `{{version}}` | Project version | `0.0.1-SNAPSHOT` |
| `{{packageName}}` | Java package name | `com.menora.demo` |
| `{{packagePath}}` | Directory path (also usable in Target Path) | `com/menora/demo` |
| `{{javaVersion}}` | Selected JVM version | `21` |
| `{{packaging}}` | Project packaging | `jar` |
| `{{#has<Dep>}}…{{/has<Dep>}}` | Renders when that dep is selected. Dep id PascalCased | `{{#hasKafka}}…{{/hasKafka}}` |
| `{{#opt<Dep><Option>}}…{{/opt<Dep><Option>}}` | Renders when that sub-option is selected | `{{#optKafkaConsumerExample}}…{{/optKafkaConsumerExample}}` |

Use `{{^…}}…{{/…}}` (inverted section) to render when the flag is *absent*.

### File Types
| Type | Behavior |
|------|----------|
| `STATIC_COPY` | Write content verbatim |
| `YAML_MERGE` | Deep-merge into target YAML file |
| `TEMPLATE` | Apply substitution variables, then write |
| `DELETE` | Delete the target file (runs last) |

---

## Testing Your Changes

### Via curl

```bash
curl -o test.zip "http://localhost:8080/starter.zip?dependencies=mail-sampler&groupId=com.menora&artifactId=demo"
unzip -l test.zip
```

### Verify the generated project contains

- `pom.xml` with `spring-boot-starter-mail` dependency
- `src/main/resources/application.yaml` with mail config block
- `src/main/java/com/menora/demo/config/MailConfig.java`
- `src/main/java/com/menora/demo/service/MailService.java` (if added)

### Check metadata

```bash
curl -H "Accept: application/json" http://localhost:8080/metadata/client | python -m json.tool | grep mail-sampler
```
