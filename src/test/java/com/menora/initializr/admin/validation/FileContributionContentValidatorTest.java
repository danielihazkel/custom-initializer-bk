package com.menora.initializr.admin.validation;

import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.FileContributionEntity.FileType;
import com.menora.initializr.db.entity.FileContributionEntity.SubstitutionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileContributionContentValidatorTest {

    private final FileContributionContentValidator validator = new FileContributionContentValidator(
            new JavaContentValidator(),
            new YamlContentValidator(),
            new XmlContentValidator(),
            new JsonContentValidator(),
            new SqlContentValidator(),
            new PropertiesContentValidator()
    );

    // ── Java ──────────────────────────────────────────────────────────────────

    @Test
    void validJavaPasses() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/java/com/example/Hello.java",
                """
                package com.example;
                public class Hello {
                    public String greet() { return "hi"; }
                }
                """));
        assertThat(errors).isEmpty();
    }

    @Test
    void invalidJavaFailsWithMessage() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/java/com/example/Hello.java",
                """
                package com.example;
                public class Hello {
                    public String greet() { return "hi";
                }
                """));
        assertThat(errors).isNotEmpty();
    }

    // ── YAML ──────────────────────────────────────────────────────────────────

    @Test
    void validYamlPasses() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/resources/application.yaml",
                """
                spring:
                  application:
                    name: demo
                server:
                  port: 8080
                """));
        assertThat(errors).isEmpty();
    }

    @Test
    void invalidYamlFailsWithMessage() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/resources/application.yaml",
                """
                spring:
                  application:
                    name: demo
                   bad-indent: 1
                """));
        assertThat(errors).isNotEmpty();
    }

    // ── XML ───────────────────────────────────────────────────────────────────

    @Test
    void validXmlPasses() {
        List<String> errors = validator.validate(staticCopy(
                "pom.xml",
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion></project>"));
        assertThat(errors).isEmpty();
    }

    @Test
    void invalidXmlFailsWithMessage() {
        List<String> errors = validator.validate(staticCopy(
                "pom.xml",
                "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</project>"));
        assertThat(errors).isNotEmpty();
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    @Test
    void validJsonPasses() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/resources/config.json",
                "{\"name\":\"demo\",\"version\":1}"));
        assertThat(errors).isEmpty();
    }

    @Test
    void invalidJsonFailsWithMessage() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/resources/config.json",
                "{\"name\":\"demo\",}"));
        assertThat(errors).isNotEmpty();
    }

    // ── SQL ───────────────────────────────────────────────────────────────────

    @Test
    void validSqlPasses() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/resources/db/migration/V1__init.sql",
                "CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(200));"));
        assertThat(errors).isEmpty();
    }

    @Test
    void invalidSqlFailsWithMessage() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/resources/db/migration/V1__init.sql",
                "CRATE TABEL users id BIGINT;"));
        assertThat(errors).isNotEmpty();
    }

    // ── Properties ────────────────────────────────────────────────────────────

    @Test
    void validPropertiesPasses() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/resources/application.properties",
                "server.port=8080\nspring.application.name=demo\n"));
        assertThat(errors).isEmpty();
    }

    // ── Mustache + Java ───────────────────────────────────────────────────────

    @Test
    void validMustacheTemplateRenderingToValidJavaPasses() {
        List<String> errors = validator.validate(template(
                "src/main/java/{{packagePath}}/Hello.java",
                """
                package {{packageName}};
                public class Hello {
                    {{#hasKafka}}
                    public String kafka() { return "yes"; }
                    {{/hasKafka}}
                }
                """));
        assertThat(errors).isEmpty();
    }

    @Test
    void unmatchedMustacheSectionFailsWithMustacheError() {
        List<String> errors = validator.validate(template(
                "src/main/java/com/example/Hello.java",
                """
                package com.example;
                public class Hello {
                    {{#hasKafka}}
                    public String kafka() { return "yes"; }
                }
                """));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsIgnoringCase("mustache");
    }

    @Test
    void validMustacheSyntaxButRenderedJavaIsBrokenFails() {
        List<String> errors = validator.validate(template(
                "src/main/java/com/example/Hello.java",
                """
                package com.example;
                public class Hello {
                    public String greet() { return "hi";
                }
                """));
        assertThat(errors).isNotEmpty();
    }

    // ── Skip cases ────────────────────────────────────────────────────────────

    @Test
    void deleteFileTypeIsSkipped() {
        FileContributionEntity fc = new FileContributionEntity();
        fc.setFileType(FileType.DELETE);
        fc.setTargetPath("src/main/resources/application.properties");
        fc.setContent(null);
        assertThat(validator.validate(fc)).isEmpty();
    }

    @Test
    void unknownExtensionIsSkipped() {
        List<String> errors = validator.validate(staticCopy(
                "README.txt",
                "garbage content { [ } unbalanced"));
        assertThat(errors).isEmpty();
    }

    @Test
    void emptyContentIsSkipped() {
        List<String> errors = validator.validate(staticCopy(
                "src/main/java/com/example/Hello.java",
                ""));
        assertThat(errors).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FileContributionEntity staticCopy(String path, String content) {
        FileContributionEntity fc = new FileContributionEntity();
        fc.setFileType(FileType.STATIC_COPY);
        fc.setSubstitutionType(SubstitutionType.NONE);
        fc.setTargetPath(path);
        fc.setContent(content);
        return fc;
    }

    private FileContributionEntity template(String path, String content) {
        FileContributionEntity fc = new FileContributionEntity();
        fc.setFileType(FileType.TEMPLATE);
        fc.setSubstitutionType(SubstitutionType.MUSTACHE);
        fc.setTargetPath(path);
        fc.setContent(content);
        return fc;
    }
}
