package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A FILE or IMAGE field that holds several files ({@code maxFiles}): a list kept in a table of its
 * own — the entity's element collection, the files table in the baseline, the read check and the
 * clean-up over it, the service's checks, and the list field in the form.
 */
@IntegrationTest
class FullstackMultiFileTests {

    private static final String BE = "album/backend/src/main/java/com/menora/album/";
    private static final String FE = "album/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    static Map<String, Object> body() {
        Map<String, Object> id = Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("name", "Trip");
        trip.put("fields", List.of(id, Map.of("name", "title", "type", "STRING", "required", true),
                Map.of("name", "photos", "type", "IMAGE", "maxFiles", 5),
                Map.of("name", "tickets", "type", "FILE", "maxFiles", 3, "required", true),
                Map.of("name", "cover", "type", "IMAGE")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "album");
        body.put("packageName", "com.menora.album");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "flyway"));
        body.put("opts", Map.of("scaffold", List.of("csvExport", "xlsxExport", "seedData", "tests")));
        body.put("entities", List.of(trip));
        return body;
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new LinkedHashMap<>();
        FullstackExampleRequests.unzip(response.getBody()).forEach((k, v) -> files.put(k, new String(v, StandardCharsets.UTF_8)));
        return files;
    }

    @Test
    void severalFilesAreAListInATableOfTheirOwn() throws Exception {
        Map<String, String> files = generate(body());

        assertThat(files.get(BE + "attachment/AttachedFile.java")).contains("@Embeddable").contains("@Column(name = \"attachment_id\", nullable = false)");
        assertThat(files.get(BE + "entity/Trip.java"))
                .contains("import com.menora.album.attachment.AttachedFile;")
                .contains("@ElementCollection(fetch = FetchType.EAGER)")
                .contains("@CollectionTable(name = \"trip_photos\", joinColumns = @JoinColumn(name = \"owner_id\"))")
                .contains("@OrderColumn(name = \"file_order\")")
                .contains("private java.util.List<AttachedFile> photos = new java.util.ArrayList<>();")
                // The single picture keeps its column and its name beside it.
                .contains("private java.util.UUID cover;").contains("private String coverName;")
                .doesNotContain("photosName");
        assertThat(files.get(BE + "dto/TripDto.java"))
                .contains("java.util.List<AttachedFile> photos,")
                .contains("entity.getPhotos() == null ? java.util.List.of() : java.util.List.copyOf(entity.getPhotos())")
                .contains("entity.setPhotos(this.photos == null ? null : new java.util.ArrayList<>(this.photos));");
        assertThat(files.get(BE + "service/TripService.java"))
                .contains("entity.setPhotos(attachments.attachAll(entity.getPhotos(), java.util.List.of(), \"photos\", 5, false));")
                .contains("entity.setTickets(attachments.attachAll(entity.getTickets(), java.util.List.of(), \"tickets\", 3, true));")
                .contains("attachments.detachRemoved(photosBefore, photosAfter);")
                .doesNotContain("existing.setPhotos(updated.getPhotos());")
                .contains("existing.getPhotos().forEach(file -> attachments.delete(file.getId()));");
        assertThat(files.get(BE + "attachment/Attachments.java"))
                .contains("public List<AttachedFile> attachAll(")
                // The read check and the clean-up look in the files tables too.
                .contains("select count(*) from trip_photos where attachment_id = :id")
                .contains("not exists (select 1 from trip_tickets o where o.attachment_id = a.id)");
        assertThat(endingWith(files, "/TripController.java"))
                .contains("csv(String.join(\"; \", dto.photos().stream().map(file -> String.valueOf(file.getName())).toList())),");

        // The baseline: no column on the entity's table, a table of the files with an index on the id.
        String baseline = files.get("album/backend/src/main/resources/db/migration/V1__init.sql");
        assertThat(baseline)
                .contains("create table trip_photos (\n    owner_id bigint not null,\n    file_order integer not null,\n"
                        + "    attachment_id uuid not null,\n    file_name varchar(255),\n"
                        + "    constraint pk_trip_photos primary key (owner_id, file_order)\n);")
                .contains("alter table trip_photos add constraint fk_trip_photos_owner_id foreign key (owner_id) references trips (id);")
                .contains("create index ixa_trip_photos_attachment_id on trip_photos (attachment_id);");
        int trips = baseline.indexOf("create table trips (");
        assertThat(baseline.substring(trips, baseline.indexOf(");", trips)))
                .doesNotContain("photos").contains("cover uuid");

        // The form: the list field for several, the one-file field for one; the checks.
        assertThat(files.get(FE + "features/trip-form/ui/TripForm.tsx"))
                .contains("import { AttachmentsField } from '@shared/ui/AttachmentsField'")
                .contains("import { AttachmentField } from '@shared/ui/AttachmentField'")
                .contains("value={value.photos ?? []}")
                .contains("max={ 5 }")
                .contains("onChange={files => onChange({ ...value, photos: files })}");
        assertThat(files.get(FE + "features/trip-form/model/validate.ts"))
                .contains("if (!value.tickets || value.tickets.length === 0) errors.tickets = t('required')")
                .contains("if (!errors.photos && value.photos && value.photos.length > 5) errors.photos = t('maxFiles', { n: 5 })");
        assertThat(files.get(FE + "entities/trip/model/types.ts"))
                .contains("photos?: { id: string; name?: string | null }[] | null")
                .doesNotContain("photosName");
        assertThat(files).containsKey(FE + "shared/ui/AttachmentsField.tsx");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("maxFiles: 'At most {n} files',");
        // Demo rows give a required list one sample file; the API tests send one by id.
        assertThat(files.get(BE + "config/DemoDataLoader.java")).contains("sampleFiles(i, false)");
    }

    @Test
    void severalFilesAreRefusedWhereOneValueIsNeeded() {
        Map<String, Object> notAFile = body();
        @SuppressWarnings("unchecked")
        Map<String, Object> trip = new LinkedHashMap<>(((List<Map<String, Object>>) notAFile.get("entities")).get(0));
        trip.put("fields", List.of(Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "STRING", "maxFiles", 3)));
        notAFile.put("entities", List.of(trip));
        assertRefused(notAFile, "maxFiles only applies to a FILE or IMAGE field");

        trip.put("fields", List.of(Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "photos", "type", "IMAGE", "maxFiles", 51)));
        assertRefused(notAFile, "must be between 1 and 50");

        trip.put("fields", List.of(Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "STRING"),
                Map.of("name", "photos", "type", "IMAGE", "maxFiles", 3)));
        trip.put("uniqueKeys", List.of(Map.of("fields", List.of("title", "photos"))));
        assertRefused(notAFile, "holds several files");
    }

    private static String endingWith(Map<String, String> files, String suffix) {
        return files.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).map(Map.Entry::getValue)
                .findFirst().orElseThrow(() -> new AssertionError("no file ending with " + suffix));
    }

    private void assertRefused(Map<String, Object> body, String message) {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains(message);
    }
}
