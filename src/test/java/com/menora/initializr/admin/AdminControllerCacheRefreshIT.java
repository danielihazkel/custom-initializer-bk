package com.menora.initializr.admin;

import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.DependencyGroupRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression pin for the cache-refresh behavior re-applied in Wave 4a.1
 * (commit 6472e25). The master merge at 54f0315 had silently dropped the
 * {@code refreshMetadata()} calls in {@link AdminController} when the
 * validation branch's {@code validateContent()} edits won the merge — this
 * test prevents that from happening again unnoticed.
 *
 * <p>Note: of the six endpoints touched by 4a.1, only the dependency-entries
 * path has a directly observable metadata effect (entries appear in
 * {@code /metadata/client}). File contributions and build customizations
 * don't contribute to the metadata structure, so for those endpoints we
 * assert the wire-shape contract (CRUD round-trips cleanly) — the
 * {@code refreshMetadata()} call there is defense in depth that would only
 * matter if a future change made them participate in metadata.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminControllerCacheRefreshIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DependencyGroupRepository groupRepo;

    @Autowired
    private DependencyEntryRepository entryRepo;

    private String token;
    private Long createdEntryId;
    private Long createdFileContribId;
    private Long createdBuildCustomId;
    private String createdDepId;

    @BeforeEach
    void login() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/admin/login", Map.of("password", "test"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        token = (String) resp.getBody().get("token");
    }

    @AfterEach
    void cleanup() {
        if (createdFileContribId != null) {
            restTemplate.exchange("/admin/file-contributions/" + createdFileContribId,
                    HttpMethod.DELETE, new HttpEntity<>(authHeaders()), Void.class);
        }
        if (createdBuildCustomId != null) {
            restTemplate.exchange("/admin/build-customizations/" + createdBuildCustomId,
                    HttpMethod.DELETE, new HttpEntity<>(authHeaders()), Void.class);
        }
        if (createdEntryId != null) {
            entryRepo.deleteById(createdEntryId);
        }
    }

    @Test
    void creatingDependencyEntryRefreshesMetadataCache() {
        DependencyGroupEntity group = groupRepo.findAll().stream().findFirst().orElseThrow();
        createdDepId = "cache-test-" + UUID.randomUUID().toString().substring(0, 8);

        DependencyEntryEntity entry = new DependencyEntryEntity();
        entry.setGroup(group);
        entry.setDepId(createdDepId);
        entry.setName("Cache Refresh Test");
        entry.setMavenGroupId("com.example");
        entry.setMavenArtifactId("cache-refresh-test");

        ResponseEntity<DependencyEntryEntity> postResp = restTemplate.exchange(
                "/admin/dependency-entries", HttpMethod.POST,
                new HttpEntity<>(entry, authHeaders()), DependencyEntryEntity.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        createdEntryId = postResp.getBody().getId();

        // The whole point: /metadata/client reflects the new dep without a
        // separate POST /admin/refresh.
        ResponseEntity<String> metadata = restTemplate.exchange("/metadata/client",
                HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(metadata.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metadata.getBody())
                .as("new dep should appear in metadata immediately after POST")
                .contains(createdDepId);
    }

    @Test
    void deletingDependencyEntryRefreshesMetadataCache() {
        DependencyGroupEntity group = groupRepo.findAll().stream().findFirst().orElseThrow();
        createdDepId = "cache-del-" + UUID.randomUUID().toString().substring(0, 8);

        DependencyEntryEntity entry = new DependencyEntryEntity();
        entry.setGroup(group);
        entry.setDepId(createdDepId);
        entry.setName("Cache Delete Test");
        entry.setMavenGroupId("com.example");
        entry.setMavenArtifactId("cache-delete-test");

        Long id = restTemplate.exchange("/admin/dependency-entries", HttpMethod.POST,
                new HttpEntity<>(entry, authHeaders()), DependencyEntryEntity.class)
                .getBody().getId();

        // Confirm it landed in metadata before we delete.
        assertThat(restTemplate.exchange("/metadata/client", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), String.class).getBody())
                .contains(createdDepId);

        ResponseEntity<Void> delResp = restTemplate.exchange(
                "/admin/dependency-entries/" + id, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(delResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        createdEntryId = null;

        assertThat(restTemplate.exchange("/metadata/client", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), String.class).getBody())
                .as("deleted dep should be gone from metadata immediately")
                .doesNotContain(createdDepId);
    }

    @Test
    void fileContributionCrudReturnsExpectedShape() {
        FileContributionEntity fc = new FileContributionEntity();
        fc.setDependencyId("__common__");
        fc.setFileType(FileContributionEntity.FileType.STATIC_COPY);
        fc.setSubstitutionType(FileContributionEntity.SubstitutionType.NONE);
        fc.setTargetPath("test-cache-refresh-" + UUID.randomUUID() + ".txt");
        fc.setContent("hello");

        ResponseEntity<FileContributionEntity> created = restTemplate.exchange(
                "/admin/file-contributions", HttpMethod.POST,
                new HttpEntity<>(fc, authHeaders()), FileContributionEntity.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().getId()).isNotNull();
        createdFileContribId = created.getBody().getId();

        FileContributionEntity update = created.getBody();
        update.setContent("hello updated");
        ResponseEntity<FileContributionEntity> updated = restTemplate.exchange(
                "/admin/file-contributions/" + createdFileContribId, HttpMethod.PUT,
                new HttpEntity<>(update, authHeaders()), FileContributionEntity.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().getContent()).isEqualTo("hello updated");

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/admin/file-contributions/" + createdFileContribId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        createdFileContribId = null;
    }

    @Test
    void buildCustomizationCrudReturnsExpectedShape() {
        BuildCustomizationEntity bc = new BuildCustomizationEntity();
        bc.setDependencyId("__common__");
        bc.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_DEPENDENCY);
        bc.setMavenGroupId("com.example");
        bc.setMavenArtifactId("cache-refresh-bc-" + UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<BuildCustomizationEntity> created = restTemplate.exchange(
                "/admin/build-customizations", HttpMethod.POST,
                new HttpEntity<>(bc, authHeaders()), BuildCustomizationEntity.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().getId()).isNotNull();
        createdBuildCustomId = created.getBody().getId();

        BuildCustomizationEntity update = created.getBody();
        update.setVersion("1.2.3");
        ResponseEntity<BuildCustomizationEntity> updated = restTemplate.exchange(
                "/admin/build-customizations/" + createdBuildCustomId, HttpMethod.PUT,
                new HttpEntity<>(update, authHeaders()), BuildCustomizationEntity.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().getVersion()).isEqualTo("1.2.3");

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/admin/build-customizations/" + createdBuildCustomId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        createdBuildCustomId = null;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        h.setBearerAuth(token);
        return h;
    }
}
