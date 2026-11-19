package com.menora.initializr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.config.GeneratedSnapshots;
import com.menora.initializr.db.entity.GeneratedSnapshotEntity;
import com.menora.initializr.db.repository.GeneratedSnapshotRepository;
import com.menora.initializr.observability.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every generated project names its files ({@code generator.snapshot}, {@code X-Snapshot}); a
 * download keeps them; and an update starts from the kept files, so its patch carries what the
 * generator writes differently since — not only the model's change. See
 * {@link GeneratedSnapshots} and {@link SnapshotStore}.
 */
@IntegrationTest
class GeneratedSnapshotTests {

    private static final String BASE_QUERY = "groupId=com.menora&artifactId=snap&name=snap&packageName=com.menora.snap"
            + "&type=maven-project&language=java&bootVersion=3.2.1&packaging=jar&javaVersion=21";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SnapshotStore store;

    @Autowired
    private GeneratedSnapshotRepository repo;

    @Test
    void everyKindOfProjectNamesItsFilesAndADownloadKeepsThem() throws Exception {
        check(rest.getForEntity("/starter.zip?" + BASE_QUERY + "&dependencies=web,actuator", byte[].class));
        check(rest.postForEntity("/starter-wizard.zip", wizardBody(), byte[].class));
        check(rest.getForEntity("/starter-multimodule.zip?modules=api,core&" + BASE_QUERY, byte[].class));
        check(FullstackExampleRequests.post(rest, fullstackBody()));
    }

    /** The id is the files' (model file aside), the header says it, and the files are kept as they were sent. */
    private void check(ResponseEntity<byte[]> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, byte[]> project = withoutRoot(FullstackExampleRequests.unzip(response.getBody()));
        String id = GeneratedSnapshots.snapshotOf(project.get(GeneratedSnapshots.MODEL_FILE));
        assertThat(id).as("generator.snapshot").isNotNull();
        assertThat(GeneratedSnapshots.idOf(project)).isEqualTo(id);
        assertThat(response.getHeaders().getFirst(GeneratedSnapshots.HEADER)).isEqualTo(id);

        Map<String, byte[]> kept = store.find(id).orElseThrow();
        Map<String, byte[]> files = new TreeMap<>(project);
        files.remove(GeneratedSnapshots.MODEL_FILE);
        assertThat(kept.keySet()).isEqualTo(files.keySet());
        files.forEach((path, bytes) -> assertThat(kept.get(path)).as(path).isEqualTo(bytes));
    }

    @Test
    void aPreviewIsNotKept() throws Exception {
        Map<String, Object> body = wizardBody();
        body.put("artifactId", "looking");
        ResponseEntity<String> response = rest.postForEntity("/starter-wizard.preview", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var preview = mapper.readTree(response.getBody());
        String model = null;
        for (var file : preview.path("files")) {
            if (file.path("path").asText().endsWith(GeneratedSnapshots.MODEL_FILE)) model = file.path("content").asText();
        }
        String id = GeneratedSnapshots.snapshotOf(model.getBytes(StandardCharsets.UTF_8));
        assertThat(id).isNotNull();
        assertThat(repo.existsById(id)).isFalse();
    }

    /**
     * The kept files stand for what an older generator wrote: one of them is changed here, and the
     * update of the unchanged model brings it back to what the generator writes today.
     */
    @Test
    void aBackendUpdateStartsFromTheKeptFiles() throws Exception {
        Map<String, Object> body = wizardBody();
        body.put("artifactId", "older");
        String path = "src/main/java/com/menora/snap/entity/Teams.java";
        String older = olderSnapshot(rest.postForEntity("/starter-wizard.zip", body, byte[].class), path);

        ResponseEntity<byte[]> update = rest.postForEntity("/starter.patch",
                Map.of("base", body, "target", body, "baseSnapshot", older), byte[].class);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getHeaders().getFirst("X-Update-Base")).isEqualTo("snapshot");
        Map<String, byte[]> files = FullstackExampleRequests.unzip(update.getBody());
        String patch = new String(files.get("older-update/update.patch"), StandardCharsets.UTF_8);
        assertThat(patch).contains("a/" + path).contains("-// written by an older generator");
        assertThat(new String(files.get("older-update/UPDATE.md"), StandardCharsets.UTF_8)).contains("## Where it starts");

        // A snapshot the server does not have: the old model is generated again, and the same
        // model gives nothing to apply.
        ResponseEntity<byte[]> again = rest.postForEntity("/starter.patch",
                Map.of("base", body, "target", body, "baseSnapshot", "0".repeat(64)), byte[].class);
        assertThat(again.getHeaders().getFirst("X-Update-Base")).isEqualTo("regenerated");
        assertThat(new String(FullstackExampleRequests.unzip(again.getBody()).get("older-update/UPDATE.md"), StandardCharsets.UTF_8))
                .contains("nothing to apply");
    }

    @Test
    void aFullstackUpdateStartsFromTheKeptFilesAndNamesItsResult() throws Exception {
        Map<String, Object> body = fullstackBody();
        String path = "frontend/src/main.tsx";
        String older = olderSnapshot(FullstackExampleRequests.post(rest, body), path);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("base", body);
        request.put("target", body);
        request.put("baseSnapshot", older);
        ResponseEntity<byte[]> update = rest.postForEntity("/starter-fullstack.patch", request, byte[].class);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getHeaders().getFirst("X-Update-Base")).isEqualTo("snapshot");
        Map<String, byte[]> files = FullstackExampleRequests.unzip(update.getBody());
        assertThat(new String(files.get("tasks-update/update.patch"), StandardCharsets.UTF_8))
                .contains("a/" + path).contains("-// written by an older generator");
        // What the project is once updated is named, in its new model file and the header — and kept.
        String result = GeneratedSnapshots.snapshotOf(files.get("tasks-update/files/menora-model.json"));
        assertThat(result).isNotNull().isNotEqualTo(older);
        assertThat(update.getHeaders().getFirst(GeneratedSnapshots.HEADER)).isEqualTo(result);
        assertThat(store.find(result)).isPresent();
    }

    @Test
    void snapshotsUnusedForTheRetentionPeriodGo() {
        GeneratedSnapshotEntity row = new GeneratedSnapshotEntity();
        row.setId("1".repeat(64));
        row.setKind("backend");
        row.setCreatedAt(Instant.now().minus(1000, ChronoUnit.DAYS));
        row.setLastUsedAt(Instant.now().minus(800, ChronoUnit.DAYS));
        row.setFileCount(1);
        byte[] zip = GeneratedSnapshots.zip(Map.of("a.txt", "a".getBytes(StandardCharsets.UTF_8)));
        row.setSizeBytes(zip.length);
        row.setContent(zip);
        repo.save(row);

        assertThat(store.purgeUnusedFor(730)).isGreaterThanOrEqualTo(1);
        assertThat(repo.existsById(row.getId())).isFalse();
    }

    /**
     * The kept files of a download, with one file changed, kept again under an id of their own —
     * what an older generator's download would have left.
     */
    private String olderSnapshot(ResponseEntity<byte[]> download, String path) throws Exception {
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = download.getHeaders().getFirst(GeneratedSnapshots.HEADER);
        Map<String, byte[]> files = new TreeMap<>(store.find(id).orElseThrow());
        assertThat(files).containsKey(path);
        files.put(path, ("// written by an older generator\n" + new String(files.get(path), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        String older = GeneratedSnapshots.idOf(files);
        store.keep(older, "test", "older", files);
        store.awaitIdle(java.time.Duration.ofSeconds(5));
        return older;
    }

    /** The files without the zip's root folder, when it has one (the framework's /starter.zip has none). */
    private static Map<String, byte[]> withoutRoot(Map<String, byte[]> zip) {
        String first = zip.keySet().iterator().next();
        String root = first.contains("/") ? first.substring(0, first.indexOf('/') + 1) : "";
        if (root.isEmpty() || !zip.keySet().stream().allMatch(p -> p.startsWith(root))) return new TreeMap<>(zip);
        Map<String, byte[]> out = new TreeMap<>();
        zip.forEach((path, bytes) -> out.put(path.substring(root.length()), bytes));
        return out;
    }

    private static Map<String, Object> wizardBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "snap");
        body.put("packageName", "com.menora.snap");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web", "data-jpa", "h2"));
        body.put("sqlByDep", Map.of("h2", "CREATE TABLE teams (id BIGINT PRIMARY KEY, name VARCHAR(60) NOT NULL);"));
        body.put("sqlOptions", Map.of("h2", Map.of("apiMode", "INLINE_DTO")));
        return body;
    }

    private static Map<String, Object> fullstackBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "tasks");
        body.put("packageName", "com.menora.tasks");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("entities", List.of(Map.of("name", "Task", "fields", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "STRING", "required", true)))));
        return body;
    }
}
