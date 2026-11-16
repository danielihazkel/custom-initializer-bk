package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.menora.initializr.fullstack.patch.GitPatchWriterTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A plain backend keeps the model it was generated from ({@code menora-model.json}) and can be
 * updated from it: {@code POST /starter.patch} compares what the old model generates with what the
 * new one does. The fullstack counterpart is {@link FullstackPatchTests}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class BackendPatchTests {

    private static final String SQL = "CREATE TABLE teams (id BIGINT PRIMARY KEY, name VARCHAR(60) NOT NULL);\n";
    private static final String ENTITY = "src/main/java/com/menora/club/entity/";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    /** A REST API over one table. */
    private static Map<String, Object> base() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "club");
        body.put("packageName", "com.menora.club");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web", "data-jpa", "h2"));
        body.put("sqlByDep", Map.of("h2", SQL));
        body.put("sqlOptions", Map.of("h2", Map.of("apiMode", "INLINE_DTO")));
        return body;
    }

    /** The same, with a second table that points at the first, validation and Kafka. */
    private static Map<String, Object> target() {
        Map<String, Object> body = base();
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "kafka"));
        body.put("sqlByDep", Map.of("h2", SQL
                + "CREATE TABLE players (id BIGINT PRIMARY KEY, team_id BIGINT NOT NULL REFERENCES teams(id), nickname VARCHAR(30));\n"));
        return body;
    }

    private Map<String, byte[]> generate(Object body) throws Exception {
        ResponseEntity<byte[]> response = rest.postForEntity("/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        return FullstackExampleRequests.unzip(response.getBody());
    }

    private ResponseEntity<byte[]> update(Object body) {
        return rest.postForEntity("/starter.patch", body, byte[].class);
    }

    private static String text(Map<String, byte[]> files, String path) throws Exception {
        assertThat(files).containsKey(path);
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void aProjectKeepsTheModelItWasGeneratedFromAndGeneratesAgainFromIt() throws Exception {
        Map<String, byte[]> project = generate(target());
        JsonNode model = mapper.readTree(project.get("club/menora-model.json"));
        assertThat(model.path("format").asText()).isEqualTo("menora-backend-model/1");
        assertThat(model.path("kind").asText()).isEqualTo("backend");
        assertThat(model.path("generator").path("catalogHash").asText()).matches("[0-9a-f]{64}");
        JsonNode request = model.path("request");
        assertThat(request.path("artifactId").asText()).isEqualTo("club");
        assertThat(request.path("bootVersion").asText()).isEqualTo("3.2.1");
        // The request as it was resolved: what the wizard added is in it.
        assertThat(request.path("dependencies")).extracting(JsonNode::asText)
                .containsExactly("web", "data-jpa", "h2", "validation", "kafka");
        assertThat(request.path("sqlByDep").path("h2").asText()).contains("CREATE TABLE players");
        assertThat(request.path("sqlOptions").path("h2").path("apiMode").asText()).isEqualTo("INLINE_DTO");
        // HELP.md says what the file is for.
        assertThat(text(project, "club/HELP.md")).contains("# Reopening and updating this project").contains("menora-model.json");

        // Generated again from its own model, the project is the same, byte for byte.
        Map<String, byte[]> again = generate(request);
        assertThat(again.keySet()).containsExactlyElementsOf(project.keySet());
        again.forEach((path, bytes) -> assertThat(bytes).as(path).isEqualTo(project.get(path)));

        // /starter.zip, which has no body, keeps one too.
        Map<String, byte[]> plain = FullstackExampleRequests.unzip(rest.getForEntity(
                "/starter.zip?artifactId=plain&groupId=com.menora&packageName=com.menora.plain&bootVersion=3.2.1&javaVersion=21"
                        + "&dependencies=web,kafka&opts-kafka=consumer-example&department=lts&baseDir=plain", byte[].class).getBody());
        JsonNode plainRequest = mapper.readTree(plain.get("plain/menora-model.json")).path("request");
        assertThat(plainRequest.path("dependencies")).extracting(JsonNode::asText).containsExactly("web", "kafka");
        assertThat(plainRequest.path("opts").path("kafka")).extracting(JsonNode::asText).containsExactly("consumer-example");
        assertThat(plainRequest.path("department").asText()).isEqualTo("lts");
        assertThat(plainRequest.has("sqlByDep")).isFalse();
        Map<String, byte[]> plainAgain = generate(plainRequest);
        assertThat(plainAgain.keySet()).containsExactlyElementsOf(plain.keySet());
        plainAgain.forEach((path, bytes) -> assertThat(bytes).as(path).isEqualTo(plain.get(path)));
    }

    @Test
    void anUpdateIsAPatchTheChangedFilesAndNotes() throws Exception {
        ResponseEntity<byte[]> response = update(Map.of("base", base(), "target", target()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("club-update.zip");
        assertThat(Integer.parseInt(response.getHeaders().getFirst("X-Update-Added"))).isGreaterThan(4);
        assertThat(Integer.parseInt(response.getHeaders().getFirst("X-Update-Changed"))).isGreaterThan(1);
        Map<String, byte[]> update = FullstackExampleRequests.unzip(response.getBody());

        String patch = text(update, "club-update/update.patch");
        assertThat(patch)
                // The new table's stack, the Kafka dependency's files…
                .contains("+++ b/" + ENTITY + "Players.java")
                .contains("+++ b/src/main/java/com/menora/club/config/KafkaConfig.java")
                // …and what the change does to files that were there: the pom, the DTO's constraints.
                .contains("--- a/pom.xml").contains("+\t\t\t<artifactId>spring-kafka</artifactId>")
                .contains("+        @NotNull @Size(max = 60) String name")
                // The model file is not patched: it is delivered whole.
                .doesNotContain("menora-model.json");
        assertThat(update).containsKeys("club-update/files/menora-model.json", "club-update/files/" + ENTITY + "Players.java",
                "club-update/files/pom.xml");
        assertThat(text(update, "club-update/UPDATE.md"))
                .contains("# Update for club")
                // A plain project's root, not a fullstack one's.
                .contains("From the project's root (the folder holding `pom.xml`)")
                .doesNotContain("frontend/")
                .contains("changes the generator's catalog has had since the project was first generated");

        // A model file as it is on disk is taken too, and its catalog hash is the base's.
        Map<String, byte[]> project = generate(base());
        ObjectNode onDisk = (ObjectNode) mapper.readTree(project.get("club/menora-model.json"));
        ResponseEntity<byte[]> fromFile = update(Map.of("base", onDisk, "target", target()));
        assertThat(fromFile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(text(FullstackExampleRequests.unzip(fromFile.getBody()), "club-update/UPDATE.md"))
                .contains("The catalog is the one the project was generated with");
        // A project generated from an older catalog is told so.
        ResponseEntity<byte[]> older = update(Map.of("base", base(), "target", target(), "baseCatalogHash", "0".repeat(64)));
        assertThat(text(FullstackExampleRequests.unzip(older.getBody()), "club-update/UPDATE.md"))
                .contains("The catalog has changed since the project was generated (`000000000000` then");

        // The same model twice: nothing to apply.
        ResponseEntity<byte[]> nothing = update(Map.of("base", base(), "target", base()));
        assertThat(nothing.getHeaders().getFirst("X-Update-Changed")).isEqualTo("0");
        assertThat(text(FullstackExampleRequests.unzip(nothing.getBody()), "club-update/UPDATE.md")).contains("there is nothing to apply");
    }

    @Test
    void aMultiModuleProjectKeepsOneModelAtItsRootAndIsUpdatedFromIt() throws Exception {
        Map<String, byte[]> project = FullstackExampleRequests.unzip(rest.getForEntity(
                "/starter-multimodule.zip?modules=api,core&artifactId=shop&groupId=com.menora&packageName=com.menora.shop"
                        + "&bootVersion=3.2.1&javaVersion=21&dependencies=kafka", byte[].class).getBody());
        // One model, at the root: a module is part of the project, not one of its own.
        assertThat(project.keySet()).filteredOn(p -> p.endsWith("menora-model.json")).containsExactly("shop/menora-model.json");
        JsonNode model = mapper.readTree(project.get("shop/menora-model.json"));
        assertThat(model.path("kind").asText()).isEqualTo("multimodule");
        assertThat(model.path("request").path("modules")).extracting(JsonNode::asText).containsExactly("api", "core");
        assertThat(model.path("request").path("dependencies")).extracting(JsonNode::asText).containsExactly("kafka");
        assertThat(text(project, "shop/shop-api/HELP.md")).doesNotContain("menora-model.json");

        // A module more: the patch adds it and changes the parent POM.
        ObjectNode target = model.deepCopy();
        ((ObjectNode) target.path("request")).putArray("modules").add("api").add("core").add("persistence");
        ResponseEntity<byte[]> response = update(Map.of("base", model, "target", target));
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        String patch = text(FullstackExampleRequests.unzip(response.getBody()), "shop-update/update.patch");
        assertThat(patch)
                .contains("+++ b/shop-persistence/pom.xml")
                .contains("--- a/pom.xml").contains("+        <module>shop-persistence</module>")
                // The new module's database joins the project's compose services, and the entry
                // module's compose profile follows; nothing else of an existing module moves.
                .contains("--- a/compose.yaml").contains("+  postgres:")
                .contains("--- a/shop-api/src/main/resources/application-compose.yaml");
        assertThat(patch.lines().filter(l -> l.startsWith("--- a/shop-api/")).toList())
                .containsExactly("--- a/shop-api/src/main/resources/application-compose.yaml");
    }

    @Test
    void aProblemNamesTheModelItIsIn() throws Exception {
        assertThat(update(Map.of("base", base())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> broken = target();
        broken.put("sqlOptions", Map.of("h2", Map.of("apiMode", "DTO")));
        ResponseEntity<byte[]> response = update(Map.of("base", base(), "target", broken));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("side").asText()).isEqualTo("target");
        assertThat(body.path("detail").asText()).contains("Unknown SQL wizard apiMode: 'DTO'");

        Map<String, Object> oldVersion = base();
        oldVersion.put("bootVersion", "2.7.0");
        JsonNode base = mapper.readTree(update(Map.of("base", oldVersion, "target", target())).getBody());
        assertThat(base.path("side").asText()).isEqualTo("base");
        assertThat(base.path("detail").asText()).contains("bootVersion");

        // Still a 400 for a document a wizard cannot read, with its reasons.
        Map<String, Object> notAsyncApi = target();
        notAsyncApi.put("asyncApiByDep", Map.of("kafka", "openapi: 3.0.0"));
        JsonNode refused = mapper.readTree(update(Map.of("base", base(), "target", notAsyncApi)).getBody());
        assertThat(refused.path("side").asText()).isEqualTo("target");
        assertThat(refused.path("messages").get(0).asText()).contains("no 'asyncapi' version field");
    }

    /**
     * The whole journey, with git: a team generates the project, commits it, edits a file by
     * hand, changes the model and applies the update. Their edit survives; everything else is
     * what the new model generates.
     */
    @Test
    void gitMergesTheUpdateIntoAProjectTheTeamHasEdited(@TempDir Path work) throws Exception {
        assumeTrue(GitPatchWriterTest.gitAvailable(), "git is not on the PATH");
        Path repo = Files.createDirectories(work.resolve("club"));
        write(work, generate(base()));
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=team", "-c", "user.email=team@example.com", "commit", "-q", "-m", "Generated");

        // The team's own work, in a file the update changes too (the DTO gains its constraints)…
        Path dto = repo.resolve("src/main/java/com/menora/club/dto/TeamsDto.java");
        String theirs = "// Reviewed by the team.\n";
        Files.writeString(dto, theirs + Files.readString(dto));
        // …and in one it does not.
        Path entity = repo.resolve(ENTITY + "Teams.java");
        Files.writeString(entity, Files.readString(entity) + "// Ours.\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=team", "-c", "user.email=team@example.com", "commit", "-q", "-m", "Ours");

        Map<String, byte[]> update = FullstackExampleRequests.unzip(update(Map.of("base", base(), "target", target())).getBody());
        Path patch = work.resolve("update.patch");
        Files.write(patch, update.get("club-update/update.patch"));
        git(repo, "apply", "--check", "--3way", patch.toString());
        git(repo, "apply", "--3way", "--whitespace=nowarn", patch.toString());
        // The files delivered whole: the model, and HELP.md — git ignores it, so no patch could reach it.
        Files.write(repo.resolve("menora-model.json"), update.get("club-update/files/menora-model.json"));
        Files.write(repo.resolve("HELP.md"), update.get("club-update/files/HELP.md"));

        Map<String, byte[]> expected = new TreeMap<>();
        generate(target()).forEach((path, bytes) -> expected.put(path.substring("club/".length()), bytes));
        Map<String, byte[]> actual = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(repo)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(f -> !repo.relativize(f).startsWith(".git")).toList()) {
                actual.put(repo.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        // Exactly the new model's files: nothing left behind, nothing missing.
        assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
        for (Map.Entry<String, byte[]> e : expected.entrySet()) {
            String path = e.getKey();
            String generated = new String(e.getValue(), StandardCharsets.UTF_8);
            if (path.endsWith("dto/TeamsDto.java")) {
                assertThat(new String(actual.get(path), StandardCharsets.UTF_8)).as(path).isEqualTo(theirs + generated);
            } else if (path.equals(ENTITY + "Teams.java")) {
                assertThat(new String(actual.get(path), StandardCharsets.UTF_8)).as(path).isEqualTo(generated + "// Ours.\n");
            } else {
                assertThat(actual.get(path)).as(path).isEqualTo(e.getValue());
            }
        }
        assertThat(git(repo, "status", "--porcelain")).doesNotContain("UU ");
    }

    private static void write(Path dir, Map<String, byte[]> files) throws Exception {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
    }

    private static String git(Path dir, String... args) throws Exception {
        return GitPatchWriterTest.git(dir, args);
    }
}
