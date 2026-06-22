# Mustache Templating Guide

This is the complete reference for authoring the `.mustache` templates that generate files into
projects produced by this Initializr backend. It answers two questions a template author keeps
hitting:

1. **Where do values like `{{packageName}}`, `{{#hasScaffoldedEntities}}`, or `{{dsRepositoryPackage}}` come from?**
2. **What is the full set of variable names I'm allowed to use, and in which file?**

All file:line citations point at real source as of this writing — if a builder method moves, grep
for the `ctx.put(...)` / `view.put(...)` call that sets the key.

> Quick-reference summary lives in `CLAUDE.md` → *Template Substitution*. This document is the
> exhaustive version.

---

## 1. How rendering works

### The engine

Templates are rendered with **jmustache** (`com.samskivert:jmustache`), compiled once per file with
HTML escaping **off**:

```java
private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);
```

This exact line appears in every render path:
- `DynamicProjectGenerationConfiguration.java:60` (backend dependency templates)
- `FrontendMustacheContext.java` / `FrontendProjectGenerator.java:51` (frontend templates)
- `FullstackRenderer.java` (fullstack entity templates)

`escapeHTML(false)` is deliberate: we emit Java, YAML, Dockerfile, TypeScript — never HTML — so
characters like `<`, `>`, `&`, `"` must pass through verbatim. A practical consequence: **`{{{triple}}}`
and `{{double}}` braces are equivalent here.** Use double braces.

### Supported syntax

Standard Mustache. The pieces you'll use:

| Syntax | Meaning |
|--------|---------|
| `{{var}}` | Interpolate a value. Missing key → **empty string, not an error.** |
| `{{#flag}}…{{/flag}}` | Section. Renders the body when `flag` is truthy (`true`, non-empty list, non-null non-false value). |
| `{{^flag}}…{{/flag}}` | Inverted section. Renders the body when `flag` is **falsy** (`false`, `null`, empty list). The "else" branch. |
| `{{#list}}…{{/list}}` | Iterate. The body renders once per element, with the element's own keys in scope. |
| `{{.}}` | The current element itself (inside a list of scalars). |
| `{{!comment}}` | Comment. Not rendered. |

Because a missing key renders empty rather than throwing, a typo in a variable name fails **silently**
— the file just generates with a blank where you expected text. Cross-check names against the tables
below.

### Two substitutions, not one: path vs. content

A file contribution has a **target path** and a **content body**. These are rendered *separately and
with different rules*:

- **Content** is rendered with the **full context** described in this guide (when
  `substitutionType = MUSTACHE`).
- **Target path** gets a **narrow, hardcoded** substitution — only a couple of placeholders, not the
  full context:
  - Backend dependency files: only `{{packagePath}}` (`DynamicProjectGenerationConfiguration.resolveTargetPath`, `:398`).
  - Frontend files: only `{{projectName}}` (`FrontendProjectGenerator.resolveTargetPath`).
  - Fullstack entity-template files: the `path` **is** rendered through the full entity context
    (so `{{EntityName}}`, `{{entityPackagePath}}`, etc. all work in the path — see the manifest in §6).

So `{{javaVersion}}` works inside a backend file's *content* but **not** in its *target path*.

### The three contexts

There is no single global context. Three independent builders each produce a `Map<String,Object>`,
and which one runs depends on which file you are editing:

| # | Context builder | Feeds |
|---|-----------------|-------|
| 1 | `DynamicProjectGenerationConfiguration.buildBaseContext` | Backend **dependency** templates (`catalog/file-contributions.json`) |
| 2 | `EntityScaffoldContext` (`buildProjectContext` + `buildEntityContext`) | Fullstack **entity** templates (`templates/fullstack/<set>/*`) |
| 3 | `FrontendMustacheContext.build` | **Frontend** templates (`catalog/frontend/file-contributions.json`) |

On the fullstack **frontend** path, contexts #2 and #3 are **merged** (#3 overlaid on #2), so an
entity template on the frontend side sees both entity variables *and* the frontend dep/palette/version
variables.

---

## 2. Which context am I in?

Find the file you're editing in the left column; that tells you which variable tables apply.

| File you're editing | Render path | Context(s) available | Tables |
|---|---|---|---|
| A `contentResource`/`content` referenced from `catalog/file-contributions.json` | Backend dependency generation | #1 only | §3 |
| A `contentResource`/`content` referenced from `catalog/frontend/file-contributions.json` | Frontend generation | #3 only | §5 |
| A `source` file in `templates/fullstack/<backend-set>/` (e.g. `spring-jpa-crud`) | Fullstack backend | #2 (+ `optScaffold*`, `hasValidation`) | §4 |
| A `source` file in `templates/fullstack/<frontend-set>/` (e.g. `react-tailwind-crud`) | Fullstack frontend | #2 **merged with** #3 | §4 + §5 |
| `templates/h2-config-primary.mustache` and other backend dep templates | Backend dependency generation | #1 only | §3 |

---

## 3. Backend dependency context (`buildBaseContext`)

Source: `src/main/java/com/menora/initializr/extension/dynamic/DynamicProjectGenerationConfiguration.java`,
method `buildBaseContext` (`:428`). This is the context for **every backend `TEMPLATE` file** declared
in `catalog/file-contributions.json` — including the H2/Oracle/Postgres datasource config classes.

### Project fields (always present)

| Variable | Type | Derived from | Notes |
|---|---|---|---|
| `artifactId` | String | `description.getArtifactId()` (`:434`) | e.g. `demo` |
| `groupId` | String | `description.getGroupId()` (`:435`) | e.g. `com.menora` |
| `version` | String | `description.getVersion()` (`:436`) | project version |
| `packageName` | String | `description.getPackageName()` (`:437`) | dotted, e.g. `com.menora.demo` |
| `packagePath` | String | `packageName.replace('.', '/')` (`:438`) | slash form, e.g. `com/menora/demo`. Also the one placeholder allowed in target paths. |
| `javaVersion` | String | `description.getLanguage().jvmVersion()` (`:439`) | e.g. `"17"`, `"21"` |
| `packaging` | String | `description.getPackaging().id()` (`:440`) | e.g. `"jar"`, `"war"`; null if unset |

### Dependency & sub-option flags (dynamic)

For every **selected** dependency, a boolean flag `has<Dep>` is set to `true`; for every selected
**sub-option**, `opt<Dep><Option>` is set to `true` (`:442–447`). Unselected deps/options have **no
key at all** (so `{{#hasKafka}}` is false when kafka isn't selected — the inverted-section / missing-key
behavior).

The id → flag transform is `toPascalCase` (`:486`), which uppercases the first letter and every letter
after a `-`, `_`, or `.`:

| Dependency / option id | Flag |
|---|---|
| `kafka` | `hasKafka` |
| `security` | `hasSecurity` |
| `mail-sampler` | `hasMailSampler` |
| `spring-boot-starter` | `hasSpringBootStarter` |
| sub-option `consumer-example` of `kafka` | `optKafkaConsumerExample` |
| sub-option `streams-example` of `kafka` | `optKafkaStreamsExample` |

> The same `has<Dep>` / `opt<Dep><Option>` convention is used by the frontend context (§5) — it is the
> universal "is X selected?" mechanism across the whole project.

### Scaffolded-datasource keys (the `ds*` family)

These exist so a per-driver datasource config class (H2/Oracle/Postgres) can scan **where the
generated entities actually live** instead of a hardcoded `<packageName>.<driver>` convention. Set at
`:449–481`:

| Variable | Type | When non-null / true | Value |
|---|---|---|---|
| `hasScaffoldedEntities` | boolean | true when the request scaffolds entities (fullstack **or** SQL wizard); false otherwise | — |
| `dsEntityPackage` | String | non-null only when `hasScaffoldedEntities` | Fullstack: `<domainPackage>.entity`. SQL wizard: `<packageName>.<subPackage>` (default `entity`). |
| `dsRepositoryPackage` | String | non-null only when `hasScaffoldedEntities` | Fullstack: `<domainPackage>.repository`. SQL wizard: `<packageName>.repository`. |

The two branches (`:455` fullstack via `EntityDefinitionContext`, `:463` SQL wizard via
`SqlScriptsContext`) both set `hasScaffold = true`; if neither applies, all three stay at
`false`/`null`. **This is exactly why the H2Config template pairs a `{{#hasScaffoldedEntities}}` branch
with a `{{^hasScaffoldedEntities}}` fallback** — see the walkthrough in §7.

---

## 4. Fullstack entity context (`EntityScaffoldContext`)

Source: `src/main/java/com/menora/initializr/fullstack/EntityScaffoldContext.java`. Built in two stages:
`buildProjectContext(...)` (project-wide) and `buildEntityContext(...)` (project-wide **plus** one
entity's view-model). A `perEntity: true` template file is rendered once per user entity with the
per-entity context; a `perEntity: false` file is rendered once with the project context.

### 4a. Project-wide variables

`artifactId`, `groupId`, `version`, `packageName`, `packagePath`, `javaVersion`, `packaging` — same
meaning as §3.

Plus the CRUD **layer packages** (each also gets a `…Path` slash-form variant):

| Variable | `…Path` variant | Value (`domainPackage` defaults to `packageName`) |
|---|---|---|
| `domainPackage` | — | base package for generated CRUD classes |
| `entityPackage` | `entityPackagePath` | `<domain>.entity` |
| `repositoryPackage` | `repositoryPackagePath` | `<domain>.repository` |
| `dtoPackage` | `dtoPackagePath` | `<domain>.dto` |
| `servicePackage` | `servicePackagePath` | `<domain>.service` |
| `controllerPackage` | `controllerPackagePath` | `<domain>.controller` |

| Variable | Type | Notes |
|---|---|---|
| `entities` | List | Every entity as a full per-entity view-model (see 4b), each tagged `first`/`last`. Use in `perEntity: false` files to loop all entities. |

> Internal-only keys `__entitySummaries` and `__inverseRelations` exist in the map but are lookups for
> the builder — **they are not meant for templates.** Don't reference them.

### 4b. Per-entity variables

Set in `entityViewModel` (`:199`). Naming comes in four cases plus plural forms (`:213–220`):

| Variable | Example (`OrderItem`) |
|---|---|
| `EntityName` | `OrderItem` |
| `entityName` | `orderItem` |
| `entity_name` | `order_item` |
| `entityNameKebab` | `order-item` |
| `EntityNamePlural` | `OrderItems` |
| `entityNamePlural` | `orderItems` |
| `entityNamePluralKebab` | `order-items` |
| `entity_name_plural` | `order_items` |

Table / key metadata:

| Variable | Type | Source |
|---|---|---|
| `tableName` | String | `entity.tableName()` or plural snake fallback (`:221`) |
| `schema` | String | `entity.schema()` (`:222`) |
| `hasSchema` | boolean | schema present & non-blank (`:223`) |
| `pkPath` | String | Path-var segment(s) joined, e.g. `/{orderId}/{lineNo}` (`:269–273`) |
| `hasCompositePk` | boolean | more than one PK field (`:265`, `:278`). **Also usable as a `gatedBy` flag** — see §6. |
| `keyClassName` | String | `<EntityName>Id` (`:266`) |
| `pkType` | String | single PK's `javaType`, or `keyClassName` for composite (`:282`) |

Field collections (each element is a field view-model, 4c):

| Variable | Contents |
|---|---|
| `fields` | All fields, tagged `first`/`last` |
| `pkField` | First PK field (single-PK back-compat); null if no PK |
| `pkFields` | All PK fields, each with its own `first`/`last` |
| `nonPkFields` | Non-PK fields, each tagged `lastNonPk` |
| `stringFields` | String-typed fields, each tagged `lastString` |
| `imports` | Distinct non-`java.lang` Java imports the fields need; each `{ name }` (`:366`) |

Relations & inverse collections:

| Variable | Type | Notes |
|---|---|---|
| `relations` | List | `MANY_TO_ONE` FK relations (4d) |
| `hasRelations` | boolean | `:335` |
| `inverseRelations` | List | Derived `@OneToMany` back-references (opt-in, `:339`) |
| `hasInverseRelations` | boolean | `:342` |

Constraint-aggregation flags — each is true iff **some** field needs that Bean Validation import, so a
DTO/entity template imports only what it uses (`:344–364`):

`hasEnumFields`, `hasStringFields`, `hasNotNullFields`, `hasSizeFields`, `hasMinFields`, `hasMaxFields`,
`hasDecimalMinFields`, `hasDecimalMaxFields`, `hasPatternFields`, `hasEmailFields`.

Per-entity derived flag (set in `buildEntityContext`):

| Variable | Type | Notes |
|---|---|---|
| `softDeleteApplicable` | boolean | `optScaffoldSoftDelete && !hasCompositePk` — soft delete only fires for single-PK entities |

### 4c. Per-field variables (inside `{{#fields}}`, `{{#pkFields}}`, …)

From `fieldViewModel` (`:376`):

| Variable | Type | Meaning |
|---|---|---|
| `name` | String | field name as authored, e.g. `birthDate` |
| `Name` | String | PascalCase, e.g. `BirthDate` |
| `column` | String | snake_case DB column, e.g. `birth_date` |
| `javaType` | String | `String`, `Long`, `LocalDate`, … (for ENUM: `<Entity><Field>Type`) |
| `tsType` | String | TypeScript type: `string`, `number`, `boolean`, `Date` |
| `enumTypeName` | String | generated enum type name for ENUM fields, else null |
| `isPrimaryKey`, `isGenerated`, `isRequired`, `isUnique` | boolean | column flags |
| `isString`, `isNumeric`, `isIntegral`, `isBigDecimal`, `isBoolean`, `isTemporal`, `isDate`, `isDateTime`, `isEnum` | boolean | type predicates |
| `hasLength`, `length` | boolean / Integer | string length constraint |
| `hasMin`, `min`, `hasMax`, `max` | boolean / Long | numeric bounds |
| `hasPattern`, `pattern`, `patternEscaped` | boolean / String | regex; `patternEscaped` is escaped for Java/JS string literals |
| `isEmail` | boolean | email constraint |
| `enumValues` | List | `{ value, last }` per enum constant |
| `first`, `last` | boolean | position within `fields` (used for comma logic) |
| `lastNonPk` | boolean | present in `nonPkFields` iteration |
| `lastString` | boolean | present in `stringFields` iteration |

### 4d. Per-relation variables (inside `{{#relations}}`)

From `:301–331`:

| Variable | Example (`Order.customer → Customer`) |
|---|---|
| `fieldName` | `customer` |
| `FieldName` | `Customer` |
| `fkFieldName` | `customerId` (DTO property) |
| `joinColumn` | `customer_id` (DB column) |
| `targetEntity` / `targetEntityCamel` / `targetEntityKebab` / `targetEntityKebabPlural` | `Customer` / `customer` / `customer` / `customers` |
| `targetPkName` / `TargetPkName` | `id` / `Id` |
| `targetPkJavaType` / `targetPkTsType` | `Long` / `number` |
| `isTargetPkNumeric` | true |
| `targetLabelField` / `hasTargetLabel` | first non-PK string field on the target (option label), else null |
| `required` | the relation's `required` flag |
| `isManyToOne` | true (only relation type in v1) |
| `last` | last in the list |

Inverse-collection variables (inside `{{#inverseRelations}}`, `:95–118`): `childEntity`,
`childEntityCamel`, `mappedBy`, `collectionField`, `CollectionField`, `last`.

### 4e. Opt-in scaffold flags

Driven by the request `opts` map (`{ "scaffold": ["tests","audit",…] }`) and exposed as
`optScaffold<Option>`. **Set in both render paths** —
`FullstackProjectGenerationConfiguration` (backend) **and** `FullstackStarterController.renderFrontend`
(frontend) — because the two paths don't share a context. A frontend-only opt set on just the backend
silently never fires.

| Flag | `opts.scaffold` value |
|---|---|
| `optScaffoldTests` | `tests` |
| `optScaffoldAudit` | `audit` |
| `optScaffoldSoftDelete` | `softDelete` |
| `optScaffoldInverse` | `inverseCollections` |

Also on the backend fullstack path: `hasValidation` (true when the `validation` starter is selected) —
gate generated Bean Validation annotations on it so imports resolve.

---

## 5. Frontend context (`FrontendMustacheContext.build`)

Source: `src/main/java/com/menora/initializr/extension/frontend/FrontendMustacheContext.java`. Feeds
every **frontend `TEMPLATE`** file in `catalog/frontend/file-contributions.json`, and is merged onto
the entity context for fullstack frontend templates.

Project & versions:

| Variable | Notes |
|---|---|
| `projectName`, `description`, `scope`, `appTitle` | project identity |
| `packageJsonName` | scoped (`@scope/name`) or plain name |
| `reactVersion`, `reactPackageVersion`, `reactDomPackageVersion`, `reactTypesVersion`, `reactDomTypesVersion` | React + `@types` versions |
| `nodeVersion` | `"18"` / `"20"` / `"22"` |
| `packageManager`, `isNpm`, `isPnpm` | package manager + convenience flags |
| `typescriptVersion`, `viteVersion`, `basePath` | tooling |

Backend pairing:

| Variable | Notes |
|---|---|
| `apiBaseUrl` | paired backend URL, e.g. `http://localhost:8080` (empty if unpaired) |
| `backendArtifactId` | paired backend artifact id |
| `hasBackendPair` | true when `apiBaseUrl` is set |
| `hasBackendArtifactId` | true when `backendArtifactId` non-blank |

Color palette — a nested `palette` map plus gates:

| Variable | Notes |
|---|---|
| `palette.id`, `palette.name` | identity |
| `palette.primary`, `palette.secondary`, `palette.accent`, `palette.error` | hex colors (accent/error empty if unset) |
| `palette.primaryHsl`, `palette.secondaryHsl`, `palette.accentHsl`, `palette.errorHsl` | `"H S% L%"` form for CSS variables |
| `hasPaletteAccent`, `hasPaletteError` | gates |

Dependency / sub-option flags: same `has<Dep>` / `opt<Dep><Option>` PascalCase convention as §3
(e.g. `hasRouterReactRouter`, `optAuthMsalInitConfig`).

### Node-version gating

The frontend mirror of backend `javaVersion` gating. `FrontendProjectGenerator.nodeVersionMismatch`
skips a file contribution unless its `nodeVersion` column equals the selected Node version. A row with
no `nodeVersion` applies to all versions. This lets one `targetPath` (e.g. `Dockerfile`) resolve to a
different content row per Node version.

---

## 6. The data model behind a template

A backend/frontend dependency template is a `FileContributionEntity` row
(`src/main/java/com/menora/initializr/db/entity/FileContributionEntity.java`). Relevant columns:

| Field | Meaning |
|---|---|
| `dependencyId` | dep id, or `__common__` for "every project" |
| `fileType` | `STATIC_COPY` (verbatim) / `YAML_MERGE` (deep-merge YAML) / `TEMPLATE` (render) / `DELETE` (remove target) |
| `content` | inline content (or null when loaded from a resource) |
| `targetPath` | destination; supports `{{packagePath}}` (backend) / `{{projectName}}` (frontend) |
| `substitutionType` | `MUSTACHE` (render content) / `NONE` (write as-is). Defaults to `NONE`. |
| `javaVersion` / `nodeVersion` | pin the row to one runtime version; null = all versions |
| `subOptionId` | include the row only when that sub-option is selected; null = always |
| `sortOrder` | execution order within a dependency (lower first) |
| `projectKind` | `BACKEND` / `FRONTEND` |

In the **seed manifests** these rows are JSON. The content lives either inline (`content`) or in a
classpath file referenced by `contentResource` (under `static-configs/*` or `templates/*`). Real rows
from `catalog/file-contributions.json`:

```json
{ "depId": "__common__", "fileType": "TEMPLATE", "contentResource": "templates/application-base.mustache", "targetPath": "src/main/resources/application.yaml", "substitutionType": "MUSTACHE", "sortOrder": -1 }
{ "depId": "kafka", "fileType": "TEMPLATE", "contentResource": "templates/kafka-config.mustache", "targetPath": "src/main/java/{{packagePath}}/config/KafkaConfig.java", "substitutionType": "MUSTACHE", "sortOrder": 1 }
{ "depId": "kafka", "fileType": "TEMPLATE", "contentResource": "templates/kafka-consumer-example.mustache", "targetPath": "src/main/java/{{packagePath}}/config/KafkaConsumerExample.java", "substitutionType": "MUSTACHE", "subOptionId": "consumer-example", "sortOrder": 2 }
{ "depId": "__common__", "fileType": "TEMPLATE", "contentResource": "templates/Dockerfile-java17.mustache", "targetPath": "Dockerfile", "substitutionType": "MUSTACHE", "javaVersion": "17", "sortOrder": 5 }
```

A **fullstack entity template** is an `EntityTemplateFileEntity` row inside a set, seeded from
`templates/fullstack/<set>/manifest.json`. Its file entries differ slightly: `source` (content file),
`path` (rendered through the **full** entity context), `perEntity` (once-per-entity vs. once), and
`gatedBy` (a context-flag name; the file is skipped unless that flag is truthy). Real entries from
`templates/fullstack/spring-jpa-crud/manifest.json`:

```json
{ "source": "Entity.java.mustache", "path": "src/main/java/{{entityPackagePath}}/{{EntityName}}.java", "perEntity": true, "substitutionType": "MUSTACHE", "fileType": "TEMPLATE", "sortOrder": 10 }
{ "source": "EntityId.java.mustache", "path": "src/main/java/{{entityPackagePath}}/{{EntityName}}Id.java", "perEntity": true, "substitutionType": "MUSTACHE", "fileType": "TEMPLATE", "sortOrder": 15, "gatedBy": "hasCompositePk" }
{ "source": "EntityControllerTest.java.mustache", "path": "src/test/java/{{controllerPackagePath}}/{{EntityName}}ControllerTest.java", "perEntity": true, "substitutionType": "MUSTACHE", "fileType": "TEMPLATE", "sortOrder": 60, "gatedBy": "optScaffoldTests" }
```

Note `gatedBy` can name **any** truthy context flag — `optScaffoldTests` (an opt-in) or `hasCompositePk`
(a derived per-entity flag).

---

## 7. Walkthrough: decoding the H2Config example

`templates/h2-config-primary.mustache` (a backend dependency template, context **#1**) contains:

```mustache
@EnableJpaRepositories(
        basePackages = "{{#hasScaffoldedEntities}}{{dsRepositoryPackage}}{{/hasScaffoldedEntities}}{{^hasScaffoldedEntities}}{{packageName}}.h2.repository{{/hasScaffoldedEntities}}",
        ...
)
...
em.setPackagesToScan("{{#hasScaffoldedEntities}}{{dsEntityPackage}}{{/hasScaffoldedEntities}}{{^hasScaffoldedEntities}}{{packageName}}.h2{{/hasScaffoldedEntities}}");
```

Reading it against §3:

- `{{#hasScaffoldedEntities}}…{{/hasScaffoldedEntities}}` — the **section** renders only when
  `hasScaffoldedEntities` is true. That happens when the request scaffolds entities (fullstack request,
  or the SQL wizard). In that branch it emits `{{dsRepositoryPackage}}` / `{{dsEntityPackage}}`, the
  packages where the generated repositories/entities actually landed
  (`<domainPackage>.repository` / `<domainPackage>.entity`).
- `{{^hasScaffoldedEntities}}…{{/hasScaffoldedEntities}}` — the **inverted section** is the "else":
  when no entities were scaffolded, `dsRepositoryPackage`/`dsEntityPackage` are null, so the template
  falls back to the legacy convention `{{packageName}}.h2.repository` / `{{packageName}}.h2`.

So a plain (non-fullstack) project gets `com.menora.demo.h2.repository`; a fullstack project gets the
real scaffolded package. The three keys are set together at
`DynamicProjectGenerationConfiguration.java:479–481`.

---

## 8. Walkthrough: add a new backend TEMPLATE file

Goal: drop a new Java config file into every project that selects, say, `security`.

1. **Author the content** at `src/main/resources/templates/my-feature-config.mustache`. Use context #1
   variables freely:

   ```mustache
   package {{packageName}}.config;

   import org.springframework.context.annotation.Configuration;

   @Configuration
   public class MyFeatureConfig {
       // generated for {{artifactId}} on Java {{javaVersion}}
   }
   ```

2. **Register it** by adding a row to `src/main/resources/catalog/file-contributions.json`:

   ```json
   { "depId": "security", "fileType": "TEMPLATE", "contentResource": "templates/my-feature-config.mustache", "targetPath": "src/main/java/{{packagePath}}/config/MyFeatureConfig.java", "substitutionType": "MUSTACHE", "sortOrder": 10 }
   ```

   `{{packagePath}}` in `targetPath` is resolved by `resolveTargetPath` (`:398`); `substitutionType:
   MUSTACHE` makes the content render.

3. **For an already-running instance** (no rebuild, DB already seeded) use the admin API instead of the
   manifest: `POST /admin/file-contributions` with the same fields, then `POST /admin/refresh` to
   invalidate the metadata cache. The manifest edit is the path that survives a fresh DB; the admin API
   is the runtime path. (DB is the source of truth — the manifest only seeds an empty DB.)

4. **Verify**: regenerate and inspect.
   ```bash
   curl -o test.zip "http://localhost:8080/starter.zip?dependencies=security"
   unzip -p test.zip "**/config/MyFeatureConfig.java"
   ```

---

## 9. Walkthrough: conditional content & gating

Three levers, from coarsest to finest:

1. **Whole file by dependency** — a `file-contributions.json` row's `depId` already scopes it to that
   dep. (`__common__` = every project.)

2. **Whole file by sub-option** — set `subOptionId` on the row; it's written only when that sub-option
   is selected. This is how `kafka`'s `KafkaConsumerExample.java` row carries
   `"subOptionId": "consumer-example"` (§6).

3. **A block inside a file** — gate with a section using the `has<Dep>` / `opt<Dep><Option>` flags:

   ```mustache
   {{#hasSecurity}}
   // emitted only when the security dependency is selected
   http.authorizeHttpRequests(...);
   {{/hasSecurity}}

   {{#optKafkaConsumerExample}}
   // emitted only when kafka's consumer-example sub-option is selected
   @KafkaListener(topics = "demo")
   void listen(String msg) { }
   {{/optKafkaConsumerExample}}
   ```

   Block-gating lets one template replace what used to need several near-duplicate rows.

For **fullstack entity templates**, the file-level gate is the manifest's `gatedBy` flag (§6) — e.g.
`gatedBy: "optScaffoldTests"`. Remember the both-paths rule (§4e): an `optScaffold*` flag that affects
the frontend must be set in both render configs or it silently never fires.

---

## 10. Walkthrough: version-gated file

One `targetPath`, different content per runtime version. The framework picks the matching row by the
`javaVersion` (backend) or `nodeVersion` (frontend) column; a null column = "all versions".

Backend `Dockerfile`, two rows both targeting `Dockerfile`:

```json
{ "depId": "__common__", "fileType": "TEMPLATE", "contentResource": "templates/Dockerfile-java17.mustache", "targetPath": "Dockerfile", "substitutionType": "MUSTACHE", "javaVersion": "17", "sortOrder": 5 }
{ "depId": "__common__", "fileType": "TEMPLATE", "contentResource": "templates/Dockerfile-java21.mustache", "targetPath": "Dockerfile", "substitutionType": "MUSTACHE", "javaVersion": "21", "sortOrder": 6 }
```

A project on Java 17 gets the first row's content; Java 21 gets the second. The same pattern with
`nodeVersion` (`"18"`/`"20"`/`"22"`) drives the frontend `Dockerfile` — **and a version with no matching
row generates no file at all**, so to add a Node version you must seed a matching `Dockerfile-node<v>`
row.

---

## 11. Gotchas

- **`{{{ }}}` ≡ `{{ }}`** — HTML escaping is off everywhere; you never need triple braces.
- **A missing/typo'd key renders empty, silently** — no error. If a value is blank in the output,
  suspect a misspelled variable name. Check it against the tables above.
- **Path substitution ≠ content substitution** — target paths only honor `{{packagePath}}` (backend) /
  `{{projectName}}` (frontend); fullstack entity `path` honors the full entity context. Don't expect
  arbitrary content variables in a backend/frontend target path.
- **`__`-prefixed keys are internal** (`__entitySummaries`, `__inverseRelations`) — not for templates.
- **New entity variables go in `EntityScaffoldContext` only** — `buildProjectContext`,
  `buildEntityContext`, or the per-field/-relation view-model builders. Adding a `view.put(...)` /
  `ctx.put(...)` there is what makes a new `{{name}}` usable; there's nowhere else to wire it.
- **`optScaffold*` must be set on both render paths** — backend
  (`FullstackProjectGenerationConfiguration`) and frontend (`FullstackStarterController.renderFrontend`).
- **`pkField` can be null** — an entity with no PK leaves `pkField` null; guard with
  `{{#pkField}}…{{/pkField}}`.

---

## Source map

| Concern | File |
|---|---|
| Backend dep context | `extension/dynamic/DynamicProjectGenerationConfiguration.java` (`buildBaseContext`, `toPascalCase`, `resolveTargetPath`) |
| Fullstack entity context | `fullstack/EntityScaffoldContext.java` |
| Frontend context | `extension/frontend/FrontendMustacheContext.java`, `FrontendProjectGenerator.java` |
| Opt flags wiring | `extension/fullstack/FullstackProjectGenerationConfiguration.java`, `config/FullstackStarterController.java` |
| Row model | `db/entity/FileContributionEntity.java`, `db/entity/EntityTemplateFileEntity.java` |
| Seed manifests | `resources/catalog/file-contributions.json`, `resources/catalog/frontend/file-contributions.json`, `resources/templates/fullstack/<set>/manifest.json` |
