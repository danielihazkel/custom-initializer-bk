# Executed Implementation Plan — Menora Offline Spring Initializr

**Executed:** 2026-03-19
**Spring Boot Version:** 3.2.1
**Initializr Version:** 0.23.0
**Java Version:** 21

---

## What Was Built

A self-hosted, air-gapped Spring Initializr for Menora that runs fully offline on the corporate network, resolves all artifacts through on-prem Artifactory (`repo.menora.co.il`), and injects custom configuration classes and config files into generated projects based on selected dependencies.

---

## Files Created

### Root
| File | Purpose |
|------|---------|
| `pom.xml` | Maven build — Spring Boot 3.2.1 parent, initializr 0.23.0 deps, Artifactory + spring repos |
| `Dockerfile` | Multi-stage build (Maven 3.9 + JDK 21 → JRE 21 Alpine) |

### Main Application
| File | Purpose |
|------|---------|
| `src/main/java/com/menora/initializr/OfflineInitializrApplication.java` | Standard `@SpringBootApplication` entry point |
| `src/main/resources/application.yml` | Dependency catalog, Artifactory repo config, default metadata |
| `src/main/resources/META-INF/spring.factories` | Registers all `ProjectGenerationConfiguration` classes |

### Extension Classes (Custom Config Injection)
| File | Trigger | Injects |
|------|---------|---------|
| `extension/common/CommonProjectGenerationConfiguration.java` | Always (no condition) | `logback-spring.xml`, `.editorconfig`, Artifactory repos in generated pom.xml |
| `extension/kafka/KafkaProjectGenerationConfiguration.java` | `kafka` dependency | `application-kafka.yml`, `KafkaConfig.java` |
| `extension/security/SecurityProjectGenerationConfiguration.java` | `security` dependency | `application-security.yml`, `SecurityConfig.java` |
| `extension/jpa/JpaProjectGenerationConfiguration.java` | `data-jpa` dependency | `application-jpa.yml`, `JpaConfig.java` |
| `extension/observability/ObservabilityProjectGenerationConfiguration.java` | `actuator` dependency | `application-observability.yml` |
| `extension/rqueue/RqueueProjectGenerationConfiguration.java` | `rqueue` dependency | `application-rqueue.yml`, `RqueueConfig.java` |
| `extension/logging/LoggingProjectGenerationConfiguration.java` | `logging` dependency | `application-logging.yml` |

### Mustache Templates (Java class generation)
| File | Generated Class |
|------|----------------|
| `src/main/resources/templates/kafka-config.mustache` | `KafkaConfig.java` with producer/consumer/listener factory beans |
| `src/main/resources/templates/security-config.mustache` | `SecurityConfig.java` with OAuth2 login + JWT resource server |
| `src/main/resources/templates/jpa-config.mustache` | `JpaConfig.java` with `@EnableJpaAuditing`, `@EnableJpaRepositories`, `@EnableTransactionManagement` |
| `src/main/resources/templates/rqueue-config.mustache` | `RqueueConfig.java` with `RedisConnectionFactory` bean |

### Static Config Files (copied into generated projects)
| File | Destination in generated project |
|------|----------------------------------|
| `static-configs/common/logback-spring.xml` | `src/main/resources/logback-spring.xml` |
| `static-configs/common/.editorconfig` | `.editorconfig` |
| `static-configs/kafka/application-kafka.yml` | `src/main/resources/application-kafka.yml` |
| `static-configs/security/application-security.yml` | `src/main/resources/application-security.yml` |
| `static-configs/jpa/application-jpa.yml` | `src/main/resources/application-jpa.yml` |
| `static-configs/observability/application-observability.yml` | `src/main/resources/application-observability.yml` |
| `static-configs/rqueue/application-rqueue.yml` | `src/main/resources/application-rqueue.yml` |
| `static-configs/logging/application-logging.yml` | `src/main/resources/application-logging.yml` |

### Tests
| File | What It Tests |
|------|--------------|
| `src/test/java/com/menora/initializr/ProjectGenerationIntegrationTests.java` | 8 integration tests: metadata endpoint, Artifactory repo injection, logback, editorconfig, security/jpa/actuator/rqueue config injection |
| `src/test/java/com/menora/initializr/extension/kafka/KafkaProjectGenerationConfigurationTests.java` | Kafka-specific: files present when `kafka` selected, absent when not selected |

---

## Key Design Decisions

1. **No Mustache engine dependency** — templates use simple `String.replace("{{packageName}}", packageName)` to avoid adding Mustache at runtime. Simple and dependency-free.
2. **`@ConditionalOnRequestedDependency`** — initializr's built-in condition, cleaner than manual dependency checks.
3. **`spring.factories`** — registration mechanism used by initializr 0.23.x (not `AutoConfiguration.imports`).
4. **Static files vs templates** — YAML configs are static (copied as-is), Java config classes are templated (package substituted).
5. **`CommonProjectGenerationConfiguration` has no condition** — runs for every generated project to enforce Menora standards.
6. **`BuildCustomizer<MavenBuild>`** — injects Artifactory `<repository>` blocks into the generated `pom.xml`, not into `application.yml`.

---

## Version Changes Applied After Initial Generation

- Spring Boot downgraded from `3.4.3` → `3.2.1` (updated in `pom.xml`, `application.yml`, and both test files)
