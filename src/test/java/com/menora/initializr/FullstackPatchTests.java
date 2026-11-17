package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.fullstack.patch.GitPatchWriterTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code POST /starter-fullstack.patch}: what a model change does to a project a team already
 * has. The model here loses an entity and a relation, gains an entity, a relation and a field.
 */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackPatchTests {

    private static final String ROOT = "press-update/";
    private static final String ENTITY = "backend/src/main/java/com/menora/press/entity/";

    @Autowired
    private TestRestTemplate rest;

    /** Posts, their author and their tags. */
    private static Map<String, Object> base() {
        return FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
    }

    /** The same project after the model moved on: tags are gone, posts have a category and a summary. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> target() {
        Map<String, Object> body = base();
        List<Map<String, Object>> entities = new ArrayList<>((List<Map<String, Object>>) body.get("entities"));
        Map<String, Object> id = Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("name", "Category");
        category.put("fields", List.of(id, Map.of("name", "name", "type", "STRING", "required", true)));
        Map<String, Object> post = new LinkedHashMap<>(entities.get(2));
        post.put("fields", List.of(id, Map.of("name", "title", "type", "STRING", "required", true),
                Map.of("name", "summary", "type", "STRING")));
        post.put("relations", List.of(
                Map.of("type", "MANY_TO_ONE", "fieldName", "author", "targetEntity", "Author"),
                Map.of("type", "MANY_TO_ONE", "fieldName", "category", "targetEntity", "Category")));
        body.put("entities", List.of(entities.get(0), category, post));
        return body;
    }

    private ResponseEntity<byte[]> update(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.patch", HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }

    private static String text(Map<String, byte[]> files, String path) {
        assertThat(files).containsKey(path);
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void anUpdateIsAPatchTheChangedFilesAndNotes() throws Exception {
        ResponseEntity<byte[]> response = update(Map.of("base", base(), "target", target()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("press-update.zip");
        Map<String, byte[]> zip = FullstackExampleRequests.unzip(response.getBody());

        String patch = text(zip, ROOT + "update.patch");
        // A new entity is new files, a removed one removed files, a changed one hunks.
        assertThat(patch)
                .contains("diff --git a/" + ENTITY + "Category.java b/" + ENTITY + "Category.java\nnew file mode 100644\n")
                .contains("diff --git a/" + ENTITY + "Tag.java b/" + ENTITY + "Tag.java\ndeleted file mode 100644\n")
                .contains("--- a/" + ENTITY + "Post.java\n+++ b/" + ENTITY + "Post.java\n")
                .contains("+    private String summary;")
                .contains("+    private Category category;")
                .contains("-    private java.util.Set<Tag> tags = new java.util.LinkedHashSet<>();")
                // The frontend too.
                .contains("+++ b/frontend/src/features/post-form/ui/PostForm.tsx")
                // The multi-pick control leaves with the last many-to-many.
                .contains("diff --git a/frontend/src/shared/ui/MultiRelationPicker.tsx b/frontend/src/shared/ui/MultiRelationPicker.tsx\ndeleted file mode");
        // What the change does not touch is not in it.
        assertThat(patch).doesNotContain("Author.java").doesNotContain("pom.xml").doesNotContain("README.md");
        // The model file is replaced whole, never patched: nobody edits it, every generation rewrites it.
        assertThat(patch).doesNotContain("menora-model.json");
        assertThat(zip).containsKey(ROOT + "files/menora-model.json");

        // The new and changed files, whole.
        assertThat(text(zip, ROOT + "files/" + ENTITY + "Category.java")).contains("public class Category");
        assertThat(text(zip, ROOT + "files/" + ENTITY + "Post.java")).contains("private String summary;");
        assertThat(zip.keySet()).noneMatch(p -> p.endsWith("/Tag.java") || p.endsWith("/Author.java"));
        assertThat(text(zip, ROOT + "DELETED.txt").lines())
                .contains(ENTITY + "Tag.java", "frontend/src/shared/ui/MultiRelationPicker.tsx")
                .doesNotContain(ENTITY + "Post.java");

        String notes = text(zip, ROOT + "UPDATE.md");
        assertThat(notes)
                .contains("# Update for press")
                .contains("git apply --3way --whitespace=nowarn path/to/update.patch")
                .contains("- `menora-model.json` (changed)")
                .contains("## Added").contains("- `" + ENTITY + "Category.java`")
                .contains("## Removed").contains("- `" + ENTITY + "Tag.java`")
                .contains("## Changed").contains("- `" + ENTITY + "Post.java`");
        long added = notes.lines().dropWhile(l -> !l.equals("## Added")).skip(2).takeWhile(l -> l.startsWith("- ")).count();
        assertThat(response.getHeaders().getFirst("X-Update-Added")).isEqualTo(String.valueOf(added));
        assertThat(Integer.parseInt(response.getHeaders().getFirst("X-Update-Deleted"))).isPositive();
        assertThat(Integer.parseInt(response.getHeaders().getFirst("X-Update-Changed"))).isPositive();
    }

    @Test
    void theSameModelTwiceIsNothingToApply() throws Exception {
        ResponseEntity<byte[]> response = update(Map.of("base", base(), "target", base()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Update-Added")).isEqualTo("0");
        assertThat(response.getHeaders().getFirst("X-Update-Changed")).isEqualTo("0");
        assertThat(response.getHeaders().getFirst("X-Update-Deleted")).isEqualTo("0");
        Map<String, byte[]> zip = FullstackExampleRequests.unzip(response.getBody());
        assertThat(zip.keySet()).containsExactlyInAnyOrder(ROOT + "UPDATE.md", ROOT + "update.patch");
        assertThat(text(zip, ROOT + "update.patch")).isEmpty();
        assertThat(text(zip, ROOT + "UPDATE.md")).contains("there is nothing to apply");
    }

    @Test
    void theNotesSayWhetherTheTemplatesAreTheOnesTheProjectWasGeneratedWith() throws Exception {
        // Every generated project says what it was rendered with…
        ResponseEntity<byte[]> generated = FullstackExampleRequests.post(rest, base());
        Map<String, byte[]> project = FullstackExampleRequests.unzip(generated.getBody());
        JsonNode model = new ObjectMapper().readTree(project.get("press/menora-model.json"));
        String hash = model.path("generator").path("templatesHash").asText();
        assertThat(hash).matches("[0-9a-f]{64}");
        // The response says it too, for a client that keeps the model rather than the zip.
        assertThat(generated.getHeaders().getFirst("X-Templates-Hash")).isEqualTo(hash);
        // …and ships the line-ending rule an update relies on.
        assertThat(text(project, "press/.gitattributes")).contains("* text=auto eol=lf").contains("*.cmd text eol=crlf");

        Map<String, Object> same = new LinkedHashMap<>(Map.of("base", base(), "target", target()));
        same.put("baseTemplatesHash", hash);
        assertThat(text(FullstackExampleRequests.unzip(update(same).getBody()), ROOT + "UPDATE.md"))
                .contains("The templates are the ones the project was generated with (`" + hash.substring(0, 12) + "`)");

        Map<String, Object> older = new LinkedHashMap<>(same);
        older.put("baseTemplatesHash", "0".repeat(64));
        assertThat(text(FullstackExampleRequests.unzip(update(older).getBody()), ROOT + "UPDATE.md"))
                .contains("The templates have changed since the project was generated (`000000000000` then, `"
                        + hash.substring(0, 12) + "` now)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProblemNamesTheModelItIsIn() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> bad = target();
        List<Map<String, Object>> entities = (List<Map<String, Object>>) bad.get("entities");
        Map<String, Object> post = new LinkedHashMap<>(entities.get(2));
        post.put("fields", List.of(Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "NOPE")));
        bad.put("entities", List.of(entities.get(0), entities.get(1), post));

        ResponseEntity<String> inTarget = rest.exchange("/starter-fullstack.patch", HttpMethod.POST,
                new HttpEntity<>(Map.of("base", base(), "target", bad), headers), String.class);
        assertThat(inTarget.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inTarget.getBody()).contains("\"side\":\"target\"").contains("\"path\":\"target.entities[2].fields[1]");

        ResponseEntity<String> inBase = rest.exchange("/starter-fullstack.patch", HttpMethod.POST,
                new HttpEntity<>(Map.of("base", bad, "target", base()), headers), String.class);
        assertThat(inBase.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inBase.getBody()).contains("\"side\":\"base\"").contains("\"path\":\"base.entities[2].fields[1]");

        ResponseEntity<String> oneSided = rest.exchange("/starter-fullstack.patch", HttpMethod.POST,
                new HttpEntity<>(Map.of("target", base()), headers), String.class);
        assertThat(oneSided.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(oneSided.getBody()).contains("An update needs both models");

        // Neither side's entities stay behind for the next request on this thread.
        assertThat(FullstackExampleRequests.post(rest, base()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The whole journey, with git: a team generates the project, commits it, edits a file by
     * hand, changes the model and applies the update. Their edit survives; everything else is
     * what the new model generates.
     */
    @Test
    void gitMergesTheUpdateIntoAProjectTheTeamHasEdited(@TempDir Path work) throws Exception {
        assumeTrue(GitPatchWriterTest.gitAvailable(), "git is not on the PATH");
        Path repo = Files.createDirectories(work.resolve("press"));
        write(work, FullstackExampleRequests.unzip(FullstackExampleRequests.post(rest, base()).getBody()));
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=team", "-c", "user.email=team@example.com", "commit", "-q", "-m", "Generated");

        // The team's own work, in a file the update changes too…
        Path post = repo.resolve(ENTITY + "Post.java");
        String theirs = "// Reviewed by the team.\n";
        Files.writeString(post, theirs + Files.readString(post));
        // …and in one it does not.
        Path author = repo.resolve(ENTITY + "Author.java");
        Files.writeString(author, Files.readString(author) + "// Ours.\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=team", "-c", "user.email=team@example.com", "commit", "-q", "-m", "Ours");

        Map<String, byte[]> update = FullstackExampleRequests.unzip(update(Map.of("base", base(), "target", target())).getBody());
        Path patch = work.resolve("update.patch");
        Files.write(patch, update.get(ROOT + "update.patch"));
        git(repo, "apply", "--check", "--3way", patch.toString());
        git(repo, "apply", "--3way", "--whitespace=nowarn", patch.toString());
        // The one file delivered whole.
        Files.write(repo.resolve("menora-model.json"), update.get(ROOT + "files/menora-model.json"));

        Map<String, byte[]> expected = new TreeMap<>();
        FullstackExampleRequests.unzip(FullstackExampleRequests.post(rest, target()).getBody())
                .forEach((path, bytes) -> expected.put(path.substring("press/".length()), bytes));
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
            if (path.equals(ENTITY + "Post.java") || path.equals(ENTITY + "Author.java")) {
                String generated = new String(e.getValue(), StandardCharsets.UTF_8);
                assertThat(new String(actual.get(path), StandardCharsets.UTF_8)).as(path)
                        .isEqualTo(path.endsWith("Post.java") ? theirs + generated : generated + "// Ours.\n");
            } else {
                // Bytes, not text: the Maven wrapper's jar and the logo are in there too.
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
