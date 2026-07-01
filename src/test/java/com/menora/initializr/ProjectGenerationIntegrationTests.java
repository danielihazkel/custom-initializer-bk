package com.menora.initializr;

import com.menora.initializr.config.DatabaseInitializrMetadataProvider;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.VersionDefinitionEntity;
import com.menora.initializr.db.entity.VersionKind;
import com.menora.initializr.db.repository.FileContributionRepository;
import com.menora.initializr.db.repository.VersionDefinitionRepository;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.generator.test.project.ProjectStructure;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class ProjectGenerationIntegrationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProjectGenerationInvoker<ProjectRequest> invoker;

    @Autowired
    private FileContributionRepository fileContribRepo;

    @Autowired
    private ProjectOptionsContext optionsContext;

    @Autowired
    private VersionDefinitionRepository versionRepo;

    @Autowired
    private InitializrMetadataProvider metadataProvider;

    @Test
    void metadataEndpointReturnsOk() {
        ResponseEntity<String> response = restTemplate.getForEntity("/metadata/client", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("kafka");
        assertThat(response.getBody()).contains("rqueue");
    }

    @Test
    void starterZipEndpointReturnsValidZip() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                "/starter.zip?dependencies=web&groupId=com.menora&artifactId=demo&type=maven-project",
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            List<String> names = new ArrayList<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            assertThat(names).anyMatch(n -> n.endsWith("pom.xml"));
        }
    }

    @Test
    void generatedProjectContainsArtifactoryRepo() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pomContent).contains("repo.menora.co.il/artifactory/libs-release");
    }

    @Test
    void generatedProjectContainsVersionDockerfileAndK8s() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("VERSION")
                .contains("Dockerfile")
                .contains("entrypoint.sh")
                .contains("settings.xml")
                .contains("k8s/Jenkinsfile")
                .contains("k8s/values.yaml");

        assertThat(Files.readString(projectDir.resolve("VERSION")).trim()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(Files.readString(projectDir.resolve("Dockerfile"))).contains("demo");
        assertThat(Files.readString(projectDir.resolve("k8s/values.yaml"))).contains("com.menora");
    }

    @Test
    void generatedProjectContainsLog4j2ByDefault() throws Exception {
        // Menora JSON logging is now a __common__ default — no `logging` dep needed.
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths().contains("src/main/resources/log4j2-spring.xml");
        assertThat(project).filePaths().doesNotContain("src/main/resources/logback-spring.xml");

        // Base JSON console logging (JsonTemplateLayout) ships by default, no Kafka appender.
        String log4j2 = Files.readString(projectDir.resolve("src/main/resources/log4j2-spring.xml"));
        assertThat(log4j2).contains("JsonTemplateLayout");
        assertThat(log4j2).contains("classpath:logFormat.json");
        assertThat(log4j2).contains("name=\"com.menora.demo.controller\"");  // templated from packageName
        assertThat(log4j2).contains("name=\"com.menora.demo\"");
        assertThat(log4j2).doesNotContain("<Kafka");                         // logging dep not selected

        String logFormat = Files.readString(projectDir.resolve("src/main/resources/logFormat.json"));
        assertThat(logFormat).contains("\"appName\": \"demo\"");             // templated from artifactId
        assertThat(project).filePaths().contains("src/main/resources/detailedLogFormat.json");

        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pomContent).contains("spring-boot-starter-log4j2");
        assertThat(pomContent).contains("spring-boot-starter-logging");      // present as exclusion
        assertThat(pomContent).contains("log4j-layout-template-json");       // JSON layout, now __common__
        assertThat(pomContent).doesNotContain("kafka-clients");              // no Kafka shipping by default
    }

    @Test
    void loggingDependencyAddsKafkaLogShippingAppender() throws Exception {
        // The `logging` dep is now the opt-in Kafka log-shipping appender (base JSON logging is default).
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("logging");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();

        String log4j2 = Files.readString(projectDir.resolve("src/main/resources/log4j2-spring.xml"));
        assertThat(log4j2).contains("<Kafka name=\"TechnicalKafka\"");
        assertThat(log4j2).contains("classpath:detailedLogFormat.json");
        assertThat(log4j2).contains("<AppenderRef ref=\"TechnicalAsyncKafka\" />");
        assertThat(log4j2).contains("<AppenderRef ref=\"DetailedAsyncKafka\" />");

        String pom = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pom).contains("kafka-clients");
        // log4j2 starter comes from __common__ only — logging is a non-starter marker, so no duplicate
        assertThat(countOccurrences(pom, "<artifactId>spring-boot-starter-log4j2</artifactId>"))
                .isEqualTo(1);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void generatedProjectContainsEditorconfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths().contains(".editorconfig");
        assertThat(project).filePaths().doesNotContain("src/main/resources/application.properties");
    }

    @Test
    void kafkaDependencyInjectsConfigFiles() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("kafka");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("bootstrap-servers");
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/KafkaConfig.java");
    }

    @Test
    void securityDependencyInjectsSecurityConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("security");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("oauth2");
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/SecurityConfig.java");
    }

    @Test
    void ldapAuthDependencyInjectsAuthFiles() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("ldap-auth");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        String yaml = Files.readString(projectDir.resolve("src/main/resources/application.yaml"));
        assertThat(yaml).contains("ldap:");
        assertThat(yaml).contains("header:");

        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/LdapConfiguration.java")
                .contains("src/main/java/com/menora/demo/security/PermissionAspect.java")
                .contains("src/main/java/com/menora/demo/security/RequiresPermission.java")
                .contains("src/main/java/com/menora/demo/security/Base64Utils.java");

        // Spring Boot 3 uses jakarta, not javax — regression guard against the original example.
        assertThat(Files.readString(projectDir.resolve(
                "src/main/java/com/menora/demo/security/PermissionAspect.java")))
                .contains("jakarta.servlet.http.HttpServletRequest")
                .doesNotContain("javax.servlet");

        String pom = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pom).contains("lts.ldap.util");
        assertThat(pom).contains("spring-boot-starter-aop");
    }

    @Test
    void ldapAuthRestDependencyInjectsRestVariant() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("ldap-auth-rest");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        String yaml = Files.readString(projectDir.resolve("src/main/resources/application.yaml"));
        assertThat(yaml).contains("ldap-rest:");
        assertThat(yaml).contains("base-url:");
        assertThat(yaml).contains("header:");
        // The REST variant carries no direct-LDAP connection block.
        assertThat(yaml).doesNotContain("search-base");

        // Shared security classes are present; the direct-LDAP-only files are not.
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/security/LdapService.java")
                .contains("src/main/java/com/menora/demo/security/PermissionAspect.java")
                .contains("src/main/java/com/menora/demo/security/RequiresPermission.java")
                .doesNotContain("src/main/java/com/menora/demo/config/LdapConfiguration.java")
                .doesNotContain("src/main/java/com/menora/demo/security/Base64Utils.java");

        // LdapService resolves groups over REST, not via LdapUtil.
        String ldapService = Files.readString(projectDir.resolve(
                "src/main/java/com/menora/demo/security/LdapService.java"));
        assertThat(ldapService)
                .contains("findGroupsByCn")
                .contains("RestClient")
                .doesNotContain("LdapUtil");

        String pom = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pom).contains("spring-boot-starter-aop");
        assertThat(pom).doesNotContain("lts.ldap.util");
    }

    @Test
    void ldapAuthSampleControllerSubOption() throws Exception {
        optionsContext.populate(Map.of("ldap-auth", List.of("sample-controller")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("web");
            request.getDependencies().add("ldap-auth");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths()
                    .contains("src/main/java/com/menora/demo/web/SampleSecuredController.java");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void ldapAuthWithoutSampleControllerOptionOmitsController() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("ldap-auth");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .doesNotContain("src/main/java/com/menora/demo/web/SampleSecuredController.java");
    }

    @Test
    void jpaDependencyInjectsJpaConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("data-jpa");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

//        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
//                .contains("datasource");
//        assertThat(project).filePaths()
//                .contains("src/main/java/com/menora/demo/config/JpaConfig.java");
    }

    @Test
    void actuatorDependencyInjectsObservabilityConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("actuator");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("management");
    }

    @Test
    void rqueueDependencyInjectsRqueueConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("rqueue");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("rqueue");
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/RqueueConfig.java");
    }

    @Test
    void withoutKafkaDependencyNoKafkaFiles() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .doesNotContain("src/main/java/com/menora/demo/config/KafkaConfig.java");
    }

    @Test
    void mustacheSectionGatesContentOnSelectedDependency() throws Exception {
        // Seed a TEMPLATE contribution under __common__ that branches on hasKafka.
        FileContributionEntity fc = new FileContributionEntity();
        fc.setDependencyId("__common__");
        fc.setFileType(FileContributionEntity.FileType.TEMPLATE);
        fc.setSubstitutionType(FileContributionEntity.SubstitutionType.MUSTACHE);
        fc.setTargetPath("mustache-section-test.txt");
        fc.setContent("{{#hasKafka}}kafka-yes{{/hasKafka}}{{^hasKafka}}kafka-no{{/hasKafka}}");
        fc.setSortOrder(9999);
        FileContributionEntity saved = fileContribRepo.save(fc);
        try {
            WebProjectRequest withKafka = createBaseRequest();
            withKafka.getDependencies().add("kafka");
            Path withDir = invoker.invokeProjectStructureGeneration(withKafka).getRootDirectory();
            assertThat(Files.readString(withDir.resolve("mustache-section-test.txt")))
                    .isEqualTo("kafka-yes");

            WebProjectRequest noKafka = createBaseRequest();
            noKafka.getDependencies().add("web");
            Path noDir = invoker.invokeProjectStructureGeneration(noKafka).getRootDirectory();
            assertThat(Files.readString(noDir.resolve("mustache-section-test.txt")))
                    .isEqualTo("kafka-no");
        } finally {
            fileContribRepo.deleteById(saved.getId());
        }
    }

    @Test
    void multipleDependenciesInjectAllConfigs() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("kafka");
        request.getDependencies().add("security");
        request.getDependencies().add("data-jpa");
        request.getDependencies().add("actuator");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/application.yaml")
                .contains("src/main/resources/log4j2-spring.xml")
                .contains(".editorconfig")
                .contains("src/main/java/com/menora/demo/config/KafkaConfig.java")
                .contains("src/main/java/com/menora/demo/config/SecurityConfig.java")
//                .contains("src/main/java/com/menora/demo/config/JpaConfig.java")
        ;

        String appYaml = Files.readString(projectDir.resolve("src/main/resources/application.yaml"));
        assertThat(appYaml)
                .contains("bootstrap-servers")
                .contains("oauth2")
//                .contains("datasource")
                .contains("management");
    }

    @Test
    void sqlWizardGeneratesEntitiesAndRepositoriesAndLombokDep() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("postgresql", "data-jpa"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", """
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP
                );
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    total NUMERIC(10,2)
                );
                """));
        body.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "entity",
                "tables", List.of(
                        Map.of("name", "users", "generateRepository", true),
                        Map.of("name", "orders", "generateRepository", false)))));

        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);

        Map<String, String> files = unzip(response.getBody());
        String usersEntity = files.get("demo/src/main/java/com/menora/demo/entity/Users.java");
        assertThat(usersEntity)
                .as("Users entity")
                .isNotNull()
                .contains("@Entity")
                .contains("@Table(name = \"users\")")
                .contains("@Data")
                .contains("private Long id;")
                .contains("private String email;")
                .contains("private LocalDateTime createdAt;");

        assertThat(files)
                .containsKey("demo/src/main/java/com/menora/demo/entity/Orders.java")
                .containsKey("demo/src/main/java/com/menora/demo/repository/UsersRepository.java");
        // orders has generateRepository=false → no repo
        assertThat(files)
                .doesNotContainKey("demo/src/main/java/com/menora/demo/repository/OrdersRepository.java");

        String pom = files.get("demo/pom.xml");
        assertThat(pom).isNotNull().contains("<artifactId>lombok</artifactId>");
    }

    @Test
    void sqlWizardEmitsManyToOneAcrossKnownTables() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("postgresql", "data-jpa"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", """
                CREATE TABLE authors (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(200) NOT NULL
                );
                CREATE TABLE books (
                    id BIGSERIAL PRIMARY KEY,
                    title VARCHAR(200) NOT NULL,
                    author_id BIGINT NOT NULL,
                    FOREIGN KEY (author_id) REFERENCES authors(id)
                );
                """));
        body.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "entity",
                "tables", List.of(
                        Map.of("name", "authors", "generateRepository", false),
                        Map.of("name", "books", "generateRepository", false)))));

        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        String books = files.get("demo/src/main/java/com/menora/demo/entity/Books.java");
        assertThat(books)
                .as("Books entity")
                .isNotNull()
                .contains("import jakarta.persistence.ManyToOne;")
                .contains("import jakarta.persistence.JoinColumn;")
                .contains("import jakarta.persistence.FetchType;")
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("@JoinColumn(name = \"author_id\", nullable = false)")
                .contains("private Authors author;")
                .doesNotContain("// TODO: map as @ManyToOne")
                .doesNotContain("private Long authorId;");
    }

    @Test
    void sqlWizardDatasourceConfigScansScaffoldedEntityPackage() throws Exception {
        // SQL wizard with a db driver but NO explicit datasource role opt: the controller must
        // default db2 to its primary role so Db2Config renders, and the config must scan the
        // wizard's actual entity/repository packages (custom subPackage "model" / .repository),
        // not the legacy .db2 subpackage.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("data-jpa", "web", "db2"));
        body.put("sqlByDep", Map.of("db2", """
                CREATE TABLE users (
                    id BIGINT NOT NULL PRIMARY KEY,
                    email VARCHAR(200) NOT NULL
                );
                """));
        body.put("sqlOptions", Map.of("db2", Map.of(
                "subPackage", "model",
                "tables", List.of())));

        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        assertThat(files.get("demo/src/main/java/com/menora/demo/config/Db2Config.java"))
                .as("Db2Config")
                .isNotNull()
                .contains("basePackages = \"com.menora.demo.repository\"")
                .contains("em.setPackagesToScan(\"com.menora.demo.model\")")
                .doesNotContain(".db2.repository");
        // The entity and repository actually land in those packages.
        assertThat(files).containsKey("demo/src/main/java/com/menora/demo/model/Users.java");
        assertThat(files.keySet()).anyMatch(p -> p.endsWith("/repository/UsersRepository.java"));
    }

    @Test
    void sqlWizardMapstructModeAutoAddsDependencyAndGeneratesRestStack() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        // Note: no "mapstruct" here — the wizard must auto-add it for MAPSTRUCT_DTO.
        body.put("dependencies", List.of("web", "postgresql", "data-jpa"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", """
                CREATE TABLE authors (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(200) NOT NULL
                );
                CREATE TABLE books (
                    id BIGSERIAL PRIMARY KEY,
                    title VARCHAR(200) NOT NULL,
                    author_id BIGINT NOT NULL,
                    FOREIGN KEY (author_id) REFERENCES authors(id)
                );
                """));
        body.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "entity",
                "apiMode", "MAPSTRUCT_DTO",
                "tables", List.of())));

        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());

        // Auto-added mapstruct dependency brings its annotation-processor wiring.
        String pom = files.get("demo/pom.xml");
        assertThat(pom).isNotNull()
                .contains("<artifactId>mapstruct</artifactId>")
                .contains("<artifactId>mapstruct-processor</artifactId>")
                .contains("<artifactId>lombok-mapstruct-binding</artifactId>");

        // Full REST stack per entity + the shared MapstructConfig from the dep.
        assertThat(files)
                .containsKey("demo/src/main/java/com/menora/demo/config/MapstructConfig.java")
                .containsKey("demo/src/main/java/com/menora/demo/service/BooksService.java")
                .containsKey("demo/src/main/java/com/menora/demo/dto/BooksDto.java")
                .containsKey("demo/src/main/java/com/menora/demo/controller/BooksController.java");

        String mapper = files.get("demo/src/main/java/com/menora/demo/mapper/BooksMapper.java");
        assertThat(mapper).isNotNull()
                .contains("@Mapper(config = MapstructConfig.class)")
                .contains("@Mapping(target = \"authorId\", source = \"author.id\")")
                .contains("default Authors authorFromId(Long id)");
    }

    @Test
    void sqlWizardAutoAddsJpaAndWebWhenOmitted() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        // Only the JDBC driver — no data-jpa, no web. The wizard must add both
        // because the entities/repositories need JPA and the controller needs web.
        body.put("dependencies", List.of("postgresql"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", """
                CREATE TABLE widgets (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(120) NOT NULL
                );
                """));
        body.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "entity",
                "apiMode", "ENTITY_DIRECT",
                "tables", List.of())));

        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        String pom = files.get("demo/pom.xml");
        assertThat(pom).isNotNull()
                .contains("<artifactId>spring-boot-starter-data-jpa</artifactId>")
                .contains("<artifactId>spring-boot-starter-web</artifactId>");
        assertThat(files)
                .containsKey("demo/src/main/java/com/menora/demo/controller/WidgetsController.java");
    }

    @Test
    void openApiWizardGeneratesController() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", openApiPetstoreBody(), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);

        Map<String, String> files = unzip(response.getBody());
        String controller = files.get("demo/src/main/java/com/menora/demo/api/PetsController.java");
        assertThat(controller)
                .as("PetsController.java")
                .isNotNull()
                .contains("@RestController")
                .contains("@GetMapping(\"/pets/{id}\")")
                .contains("@PathVariable")
                .contains("throw new UnsupportedOperationException");
    }

    @Test
    void openApiWizardGeneratesRecord() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", openApiPetstoreBody(), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        String dto = files.get("demo/src/main/java/com/menora/demo/dto/Pet.java");
        assertThat(dto)
                .as("Pet.java")
                .isNotNull()
                .contains("public record Pet(")
                .contains("Long id")
                .contains("String name");
    }

    @Test
    void openApiWizardParseErrorReturns400() {
        Map<String, Object> body = openApiBaseBody();
        body.put("specByDep", Map.of("web", "this is not: valid: yaml: at: all: ["));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void openApiMetadataEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/metadata/openapi-capable-deps", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("web");
    }

    @Test
    void combinedSqlAndOpenApiGenerateTogether() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("postgresql", "data-jpa", "web"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", """
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(200) NOT NULL
                );
                """));
        body.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "entity",
                "tables", List.of(Map.of("name", "users", "generateRepository", true)))));
        body.put("specByDep", Map.of("web", """
                openapi: 3.0.3
                info:
                  title: Petstore
                  version: 1.0.0
                paths:
                  /pets/{id}:
                    get:
                      tags: [pets]
                      operationId: getPetById
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:
                            type: integer
                            format: int64
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Pet'
                components:
                  schemas:
                    Pet:
                      type: object
                      required: [id, name]
                      properties:
                        id:
                          type: integer
                          format: int64
                        name:
                          type: string
                """));
        body.put("openApiOptions", Map.of("web", Map.of(
                "apiSubPackage", "api",
                "dtoSubPackage", "dto")));

        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        // SQL wizard output
        assertThat(files)
                .as("SQL entity and repository should be present")
                .containsKey("demo/src/main/java/com/menora/demo/entity/Users.java")
                .containsKey("demo/src/main/java/com/menora/demo/repository/UsersRepository.java");
        // OpenAPI wizard output
        assertThat(files)
                .as("OpenAPI controller and DTO should be present in the same ZIP")
                .containsKey("demo/src/main/java/com/menora/demo/api/PetsController.java")
                .containsKey("demo/src/main/java/com/menora/demo/dto/Pet.java");
    }

    private Map<String, Object> openApiBaseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web"));
        body.put("opts", Map.of());
        return body;
    }

    private Map<String, Object> openApiPetstoreBody() {
        Map<String, Object> body = openApiBaseBody();
        body.put("specByDep", Map.of("web", """
                openapi: 3.0.3
                info:
                  title: Petstore
                  version: 1.0.0
                paths:
                  /pets/{id}:
                    get:
                      tags: [pets]
                      operationId: getPetById
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:
                            type: integer
                            format: int64
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Pet'
                components:
                  schemas:
                    Pet:
                      type: object
                      required: [id, name]
                      properties:
                        id:
                          type: integer
                          format: int64
                        name:
                          type: string
                """));
        body.put("openApiOptions", Map.of("web", Map.of(
                "apiSubPackage", "api",
                "dtoSubPackage", "dto")));
        return body;
    }

    @Test
    void soapWizardEmitsEndpointAndConfig() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", soapCountryBody("ENDPOINTS"), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        assertThat(files)
                .as("WSDL should be dropped into resources")
                .containsKey("demo/src/main/resources/wsdl/country-service.wsdl");

        String endpoint = files.get("demo/src/main/java/com/menora/demo/endpoint/CountryServiceEndpoint.java");
        assertThat(endpoint)
                .as("CountryServiceEndpoint.java")
                .isNotNull()
                .contains("@Endpoint")
                .contains("@PayloadRoot")
                .contains("namespace = \"http://example.com/country\"")
                .contains("localPart = \"getCountryRequest\"")
                .contains("public GetCountryResponse getCountry")
                .contains("throw new UnsupportedOperationException");

        String config = files.get("demo/src/main/java/com/menora/demo/endpoint/WebServiceConfig.java");
        assertThat(config)
                .as("WebServiceConfig.java")
                .isNotNull()
                .contains("@EnableWs")
                .contains("MessageDispatcherServlet")
                .contains("SimpleWsdl11Definition")
                .contains("country-service.wsdl");

        String pom = files.get("demo/pom.xml");
        assertThat(pom)
                .as("pom.xml should declare the JAX-WS plugin and web-services starter")
                .contains("spring-boot-starter-web-services")
                .contains("jaxws-maven-plugin")
                .contains("wsimport")
                .contains("country-service.wsdl");
    }

    @Test
    void soapWizardClientMode() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-wizard.zip", soapCountryBody("CLIENT"), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        String client = files.get("demo/src/main/java/com/menora/demo/client/CountryServiceClient.java");
        assertThat(client)
                .as("CountryServiceClient.java")
                .isNotNull()
                .contains("extends WebServiceGatewaySupport")
                .contains("public GetCountryResponse getCountry(GetCountryRequest request)")
                .contains("marshalSendAndReceive(request)");

        String config = files.get("demo/src/main/java/com/menora/demo/client/SoapClientConfig.java");
        assertThat(config)
                .as("SoapClientConfig.java")
                .isNotNull()
                .contains("Jaxb2Marshaller")
                .contains("\"com.menora.demo.generated\"")
                .contains("@Value(\"${soap.client.base-url}\")");

        String yaml = files.get("demo/src/main/resources/application.yaml");
        assertThat(yaml)
                .as("application.yaml should contain the soap.client.base-url key")
                .isNotNull()
                .contains("soap")
                .contains("base-url");
    }

    @Test
    void soapWizardParseErrorReturns400() {
        Map<String, Object> body = soapBaseBody();
        body.put("wsdlByDep", Map.of("web-services", "<not><valid>wsdl"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/starter-wizard.zip", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void soapMetadataEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/metadata/soap-capable-deps", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("web-services");
    }

    @Test
    void soapDetectServicesEndpoint() {
        Map<String, Object> req = Map.of("wsdl", SAMPLE_WSDL);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/starter-wizard.detect-services", req, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("CountryService").contains("getCountry");
    }

    private Map<String, Object> soapBaseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web-services"));
        body.put("opts", Map.of());
        return body;
    }

    private Map<String, Object> soapCountryBody(String mode) {
        Map<String, Object> body = soapBaseBody();
        body.put("wsdlByDep", Map.of("web-services", SAMPLE_WSDL));
        body.put("soapOptions", Map.of("web-services", Map.of("mode", mode)));
        return body;
    }

    private static final String SAMPLE_WSDL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="http://example.com/country"
                              targetNamespace="http://example.com/country">
              <wsdl:types>
                <xsd:schema targetNamespace="http://example.com/country"
                            xmlns="http://example.com/country"
                            elementFormDefault="qualified">
                  <xsd:element name="getCountryRequest">
                    <xsd:complexType>
                      <xsd:sequence>
                        <xsd:element name="name" type="xsd:string"/>
                      </xsd:sequence>
                    </xsd:complexType>
                  </xsd:element>
                  <xsd:element name="getCountryResponse">
                    <xsd:complexType>
                      <xsd:sequence>
                        <xsd:element name="population" type="xsd:int"/>
                      </xsd:sequence>
                    </xsd:complexType>
                  </xsd:element>
                </xsd:schema>
              </wsdl:types>
              <wsdl:message name="getCountryRequest">
                <wsdl:part name="parameters" element="tns:getCountryRequest"/>
              </wsdl:message>
              <wsdl:message name="getCountryResponse">
                <wsdl:part name="parameters" element="tns:getCountryResponse"/>
              </wsdl:message>
              <wsdl:portType name="CountryPort">
                <wsdl:operation name="getCountry">
                  <wsdl:input message="tns:getCountryRequest"/>
                  <wsdl:output message="tns:getCountryResponse"/>
                </wsdl:operation>
              </wsdl:portType>
              <wsdl:binding name="CountryBinding" type="tns:CountryPort">
                <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="getCountry">
                  <soap:operation soapAction=""/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>
              <wsdl:service name="CountryService">
                <wsdl:port name="CountryPort" binding="tns:CountryBinding">
                  <soap:address location="http://localhost:8080/ws"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
            """;

    private Map<String, String> unzip(byte[] zipBytes) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] buf = zis.readAllBytes();
                out.put(e.getName(), new String(buf, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private WebProjectRequest createBaseRequest() {
        WebProjectRequest request = new WebProjectRequest();
        request.setGroupId("com.menora");
        request.setArtifactId("demo");
        request.setPackageName("com.menora.demo");
        request.setBootVersion("3.2.1");
        request.setLanguage("java");
        request.setJavaVersion("21");
        request.setType("maven-project");
        request.setPackaging("jar");
        request.setConfigurationFileFormat("properties");
        return request;
    }

    // ── New dependency coverage ──────────────────────────────────────────────

    @Test
    void flywayDependencyAddsMigrationFile() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("flyway");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/db/migration/V1__init.sql");
        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("flyway");
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                .contains("flyway-core");
    }

    @Test
    void liquibaseDependencyAddsChangelogFiles() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("liquibase");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/db/changelog/db.changelog-master.xml")
                .contains("src/main/resources/db/changelog/db.changelog-1.0.xml");
        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("liquibase");
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                .contains("liquibase-core");
    }

    @Test
    void openapiDependencyAddsConfigAndSwaggerSettings() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("openapi");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/OpenApiConfig.java");
        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("springdoc")
                .contains("swagger-ui");
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                .contains("springdoc-openapi-starter-webmvc-ui");
    }

    @Test
    void redisDependencyAddsConfigAndYaml() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("redis");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/RedisConfig.java");
        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("redis");
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                .contains("spring-boot-starter-data-redis");
    }

    @Test
    void cacheDependencyAddsCaffeineAndCacheConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("cache");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/CacheConfig.java");
        String yaml = Files.readString(projectDir.resolve("src/main/resources/application.yaml"));
        assertThat(yaml)
                .contains("cache")
                .contains("caffeine");
        String pom = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pom)
                .contains("spring-boot-starter-cache")
                .contains("caffeine");
    }

    @Test
    void resilience4jDependencyAddsConfigYaml() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("resilience4j");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("resilience4j")
                .contains("circuitbreaker");
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                .contains("resilience4j-spring-boot3");
    }

    @Test
    void validationDependencyAddsExceptionHandler() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("validation");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/web/ValidationExceptionHandler.java");
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                .contains("spring-boot-starter-validation");
    }

    @Test
    void quartzDependencyAddsConfigAndYaml() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("quartz");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/QuartzConfig.java");
        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("quartz");
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                .contains("spring-boot-starter-quartz");
    }

    // ── New sub-option coverage ──
    // Sub-options are normally populated by InitializrWebConfiguration filter from
    // `opts-{depId}=...` query params. For invoker-based tests we populate the
    // ProjectOptionsContext directly in the test thread.

    @Test
    void webRestExampleSubOptionAddsController() throws Exception {
        optionsContext.populate(Map.of("web", List.of("rest-example", "global-exception-handler")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("web");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths()
                    .contains("src/main/java/com/menora/demo/web/HelloController.java")
                    .contains("src/main/java/com/menora/demo/web/GlobalExceptionHandler.java");
            assertThat(Files.readString(projectDir.resolve("src/main/java/com/menora/demo/web/HelloController.java")))
                    .contains("@RestController")
                    .contains("@RequestMapping(\"/api\")")
                    .contains("@GetMapping(\"/hello\")");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void resilience4jCircuitBreakerSubOptionAddsService() throws Exception {
        optionsContext.populate(Map.of("resilience4j", List.of("circuit-breaker-example")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("resilience4j");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths()
                    .contains("src/main/java/com/menora/demo/service/ResilientService.java");
            assertThat(Files.readString(projectDir.resolve("src/main/java/com/menora/demo/service/ResilientService.java")))
                    .contains("@CircuitBreaker")
                    .contains("@Retry")
                    .contains("fallback");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void quartzJobExampleSubOptionAddsJobFiles() throws Exception {
        optionsContext.populate(Map.of("quartz", List.of("job-example")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("quartz");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths()
                    .contains("src/main/java/com/menora/demo/job/HelloJob.java")
                    .contains("src/main/java/com/menora/demo/job/HelloJobConfig.java");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void jpaSampleEntitySubOptionAddsEntityAndRepo() throws Exception {
        optionsContext.populate(Map.of("data-jpa", List.of("sample-entity", "auditing-example")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("data-jpa");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths()
                    .contains("src/main/java/com/menora/demo/domain/User.java")
                    .contains("src/main/java/com/menora/demo/repository/UserRepository.java")
                    .contains("src/main/java/com/menora/demo/config/JpaAuditingConfig.java");
            assertThat(Files.readString(projectDir.resolve("src/main/java/com/menora/demo/domain/User.java")))
                    .contains("@Entity")
                    .contains("@EntityListeners");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void prometheusGrafanaDashboardSubOptionAddsJson() throws Exception {
        optionsContext.populate(Map.of("prometheus", List.of("grafana-dashboard")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("prometheus");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths().contains("k8s/grafana-dashboard.json");
            assertThat(Files.readString(projectDir.resolve("k8s/grafana-dashboard.json")))
                    .contains("Spring Boot")
                    .contains("Prometheus")
                    .contains("jvm_memory_used_bytes");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void actuatorHealthGroupsSubOptionMergesYaml() throws Exception {
        optionsContext.populate(Map.of("actuator", List.of("health-groups", "info-contributor")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("actuator");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths()
                    .contains("src/main/java/com/menora/demo/config/BuildInfoContributor.java");
            String yaml = Files.readString(projectDir.resolve("src/main/resources/application.yaml"));
            assertThat(yaml)
                    .contains("readiness")
                    .contains("liveness")
                    .contains("probes");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void h2DefaultDoesNotEmitTopLevelH2DatasourceBlock() throws Exception {
        // Single-datasource (no sub-option): only the spring.* block should be
        // merged. The top-level h2.* mirror (sentinel key: hbm2ddl-auto) backs the
        // generated H2Config.java and must NOT appear when no H2Config is generated.
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("h2");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        String yaml = Files.readString(projectDir.resolve("src/main/resources/application.yaml"));
        assertThat(yaml)
                .contains("org.h2.Driver")       // spring.datasource block is present
                .doesNotContain("hbm2ddl-auto");  // top-level h2.* mirror is absent
        assertThat(project).filePaths()
                .doesNotContain("src/main/java/com/menora/demo/config/H2Config.java");
    }

    @Test
    void h2PrimarySubOptionEmitsTopLevelH2BlockAndConfig() throws Exception {
        // Multi-datasource (h2-primary): the gated merge must restore the top-level
        // h2.* block that H2Config.java binds via @ConfigurationProperties("h2.datasource").
        optionsContext.populate(Map.of("h2", List.of("h2-primary")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("h2");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            ProjectStructure project = new ProjectStructure(projectDir);

            assertThat(project).filePaths()
                    .contains("src/main/java/com/menora/demo/config/H2Config.java");
            assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                    .contains("hbm2ddl-auto");  // top-level h2.* mirror restored
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void standaloneDatasourceConfigKeepsLegacyDriverSubpackageWithoutScaffoldedEntities() throws Exception {
        // No entity scaffolding (plain /starter.zip with db2-primary): the datasource config must
        // retain its legacy per-driver subpackage convention (.db2 / .db2.repository) so the
        // multi-datasource user files their own entities there. Guards the Mustache inverted
        // section default added for the fullstack/SQL-wizard fix.
        optionsContext.populate(Map.of("db2", List.of("db2-primary")));
        try {
            WebProjectRequest request = createBaseRequest();
            request.getDependencies().add("db2");

            Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();

            assertThat(Files.readString(projectDir.resolve(
                    "src/main/java/com/menora/demo/config/Db2Config.java")))
                    .contains("basePackages = \"com.menora.demo.db2.repository\"")
                    .contains("em.setPackagesToScan(\"com.menora.demo.db2\")");
        } finally {
            optionsContext.clear();
        }
    }

    @Test
    void newJavaVersionFromDbAppearsInMetadataAfterRefresh() {
        // A Java version inserted into version_definition + refresh() should be
        // visible in /metadata/client.javaVersion.values without restarting the app.
        // This is the proof that Java versions are DB-managed (not YAML-bound).
        VersionDefinitionEntity v = new VersionDefinitionEntity();
        v.setKind(VersionKind.JAVA);
        v.setVersionId("25");
        v.setDisplayName("25");
        v.setDefault(false);
        v.setSortOrder(99);
        v.setEnabled(true);
        VersionDefinitionEntity saved = versionRepo.save(v);
        try {
            ((DatabaseInitializrMetadataProvider) metadataProvider).refresh();
            String body = restTemplate.getForEntity("/metadata/client", String.class).getBody();
            assertThat(body).contains("\"25\"");
        } finally {
            versionRepo.deleteById(saved.getId());
            ((DatabaseInitializrMetadataProvider) metadataProvider).refresh();
        }
    }

    @Test
    void newDependenciesAppearInMetadata() {
        ResponseEntity<String> response = restTemplate.getForEntity("/metadata/client", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body)
                .contains("flyway")
                .contains("liquibase")
                .contains("openapi")
                .contains("redis")
                .contains("cache")
                .contains("resilience4j")
                .contains("validation")
                .contains("quartz");
    }
}
