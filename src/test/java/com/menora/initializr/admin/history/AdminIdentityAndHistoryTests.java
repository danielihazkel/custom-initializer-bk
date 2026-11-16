package com.menora.initializr.admin.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin identity and the catalog history, over HTTP: single sign-on (a dev user standing in for
 * the directory), the password's holder named, every admin change recorded as its admin's with
 * the row before and after, a change undone, a row re-created with its own id, a conflict
 * refused and then forced, a whole import undone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "admin.dev-users=dana,eli")
class AdminIdentityAndHistoryTests {

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;

    private HttpHeaders as(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    private String sso(String user) throws Exception {
        HttpHeaders headers = as(null);
        headers.set("userinfo", user);
        ResponseEntity<String> response = rest.exchange("/admin/login/sso", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody()).get("token").asText();
    }

    private JsonNode call(HttpMethod method, String url, String token, Object body, HttpStatus expected) throws Exception {
        ResponseEntity<String> response = rest.exchange(url, method, new HttpEntity<>(body, as(token)), String.class);
        assertThat(response.getStatusCode()).as(method + " " + url + ": " + response.getBody()).isEqualTo(expected);
        return response.getBody() == null || response.getBody().isBlank() ? null : json.readTree(response.getBody());
    }

    private JsonNode get(String url, String token) throws Exception {
        return call(HttpMethod.GET, url, token, null, HttpStatus.OK);
    }

    private JsonNode group(String token, String name) throws Exception {
        for (JsonNode g : get("/admin/dependency-groups", token)) {
            if (name.equals(g.path("name").asText())) return g;
        }
        throw new AssertionError("no group " + name);
    }

    @Test
    void anAdminSignsInAsThemselves() throws Exception {
        HttpHeaders headers = as(null);
        headers.set("userinfo", "dana");
        JsonNode options = json.readTree(rest.exchange("/admin/login/options", HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody());
        assertThat(options.path("sso").asBoolean()).isTrue();
        assertThat(options.path("password").asBoolean()).isTrue();
        assertThat(options.path("user").asText()).isEqualTo("dana");

        String token = sso("dana");
        assertThat(get("/admin/me", token).path("user").asText()).isEqualTo("dana");

        // Not an admin, or nobody named.
        HttpHeaders stranger = as(null);
        stranger.set("userinfo", "mallory");
        assertThat(rest.exchange("/admin/login/sso", HttpMethod.POST, new HttpEntity<>(stranger), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // (The JDK client cannot read a 401 to a POST: it says so with the code.)
        try {
            assertThat(rest.exchange("/admin/login/sso", HttpMethod.POST, new HttpEntity<>(as(null)), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            assertThat(e.getCause()).isInstanceOf(java.net.HttpRetryException.class);
            assertThat(((java.net.HttpRetryException) e.getCause()).responseCode()).isEqualTo(401);
        }

        // The password's holder is named by the gateway when it can be.
        HttpHeaders bob = as(null);
        bob.set("userinfo", "bob");
        JsonNode login = json.readTree(rest.exchange("/admin/login", HttpMethod.POST,
                new HttpEntity<>("{\"password\":\"test\"}", bob), String.class).getBody());
        assertThat(login.path("user").asText()).isEqualTo("bob (password)");
        assertThat(get("/admin/me", login.path("token").asText()).path("user").asText()).isEqualTo("bob (password)");
    }

    @Test
    void anEditIsRecordedAsItsAdminsAndCanBeUndone() throws Exception {
        String dana = sso("dana");
        ObjectNode web = (ObjectNode) group(dana, "Web");
        long id = web.path("id").asLong();
        web.put("name", "Web (renamed)");
        call(HttpMethod.PUT, "/admin/dependency-groups/" + id, dana, web, HttpStatus.OK);

        JsonNode change = get("/admin/history?entityType=DependencyGroupEntity&rowId=" + id + "&size=1", dana).path("content").get(0);
        assertThat(change.path("actor").asText()).isEqualTo("dana");
        assertThat(change.path("source").asText()).isEqualTo("admin");
        assertThat(change.path("action").asText()).isEqualTo("UPDATE");
        assertThat(change.path("before").path("name").asText()).isEqualTo("Web");
        assertThat(change.path("after").path("name").asText()).isEqualTo("Web (renamed)");
        assertThat(change.path("changedFields").toString()).contains("name");
        assertThat(change.path("label").asText()).isEqualTo("Web (renamed)");

        String eli = sso("eli");
        call(HttpMethod.POST, "/admin/history/" + change.path("id").asLong() + "/undo", eli, null, HttpStatus.OK);
        assertThat(group(dana, "Web").path("id").asLong()).isEqualTo(id);
        JsonNode undo = get("/admin/history?entityType=DependencyGroupEntity&rowId=" + id + "&size=1", dana).path("content").get(0);
        assertThat(undo.path("actor").asText()).isEqualTo("eli");
        assertThat(undo.path("source").asText()).isEqualTo("restore");
        assertThat(undo.path("after").path("name").asText()).isEqualTo("Web");
    }

    @Test
    void anUndoOverSomebodysLaterEditIsRefusedUntilForced() throws Exception {
        String dana = sso("dana");
        ObjectNode data = (ObjectNode) group(dana, "Messaging");
        long id = data.path("id").asLong();
        data.put("name", "Messaging 1");
        call(HttpMethod.PUT, "/admin/dependency-groups/" + id, dana, data, HttpStatus.OK);
        long first = get("/admin/history?entityType=DependencyGroupEntity&rowId=" + id + "&size=1", dana).path("content").get(0).path("id").asLong();
        data.put("name", "Messaging 2");
        call(HttpMethod.PUT, "/admin/dependency-groups/" + id, dana, data, HttpStatus.OK);

        JsonNode refused = call(HttpMethod.POST, "/admin/history/" + first + "/undo", dana, null, HttpStatus.CONFLICT);
        assertThat(refused.path("detail").asText()).contains("name changed since");
        assertThat(group(dana, "Messaging 2").path("id").asLong()).isEqualTo(id);

        call(HttpMethod.POST, "/admin/history/" + first + "/undo", dana, java.util.Map.of("force", true), HttpStatus.OK);
        assertThat(group(dana, "Messaging").path("id").asLong()).isEqualTo(id);
    }

    @Test
    void aDeletedRowComesBackWithItsOwnId() throws Exception {
        String dana = sso("dana");
        JsonNode rule = get("/admin/compatibility", dana).get(0);
        long id = rule.path("id").asLong();
        call(HttpMethod.DELETE, "/admin/compatibility/" + id, dana, null, HttpStatus.NO_CONTENT);
        JsonNode deletion = get("/admin/history?entityType=DependencyCompatibilityEntity&rowId=" + id + "&size=1", dana).path("content").get(0);
        assertThat(deletion.path("action").asText()).isEqualTo("DELETE");
        assertThat(deletion.path("before").path("sourceDepId").asText()).isEqualTo(rule.path("sourceDepId").asText());

        call(HttpMethod.POST, "/admin/history/" + deletion.path("id").asLong() + "/undo", dana, null, HttpStatus.OK);
        JsonNode back = null;
        for (JsonNode r : get("/admin/compatibility", dana)) {
            if (r.path("id").asLong() == id) back = r;
        }
        assertThat(back).isNotNull();
        assertThat(back.path("sourceDepId").asText()).isEqualTo(rule.path("sourceDepId").asText());
        assertThat(back.path("targetDepId").asText()).isEqualTo(rule.path("targetDepId").asText());
        assertThat(back.path("relationType").asText()).isEqualTo(rule.path("relationType").asText());
        JsonNode recreated = get("/admin/history?entityType=DependencyCompatibilityEntity&rowId=" + id + "&size=1", dana).path("content").get(0);
        assertThat(recreated.path("action").asText()).isEqualTo("CREATE");
        assertThat(recreated.path("source").asText()).isEqualTo("restore");
        // Restoring the deletion's own version takes it away again.
        call(HttpMethod.POST, "/admin/history/" + deletion.path("id").asLong() + "/restore", dana, null, HttpStatus.OK);
        for (JsonNode r : get("/admin/compatibility", dana)) assertThat(r.path("id").asLong()).isNotEqualTo(id);
        // And undoing that puts it back — the database is shared with the other test classes.
        JsonNode again = get("/admin/history?entityType=DependencyCompatibilityEntity&rowId=" + id + "&size=1", dana).path("content").get(0);
        call(HttpMethod.POST, "/admin/history/" + again.path("id").asLong() + "/undo", dana, null, HttpStatus.OK);
        assertThat(get("/admin/compatibility", dana).findValuesAsText("id")).contains(String.valueOf(id));
    }

    @Test
    void aWholeImportIsOneRequestThatCanBeUndone() throws Exception {
        String dana = sso("dana");
        JsonNode exported = get("/admin/export", dana);
        long webId = group(dana, "Observability").path("id").asLong();
        ((ObjectNode) exported).put("_note", "same catalog, imported again");
        call(HttpMethod.POST, "/admin/import", dana, exported, HttpStatus.OK);
        // The import replaced every row: the group has another id now.
        assertThat(group(dana, "Observability").path("id").asLong()).isNotEqualTo(webId);

        JsonNode request = get("/admin/history/requests?size=5", dana).path("content").get(0);
        assertThat(request.path("source").asText()).isEqualTo("import");
        assertThat(request.path("actor").asText()).isEqualTo("dana");
        assertThat(request.path("changes").asLong()).isGreaterThan(100);

        call(HttpMethod.POST, "/admin/history/requests/" + request.path("requestId").asText() + "/undo", dana, null, HttpStatus.OK);
        assertThat(group(dana, "Observability").path("id").asLong()).isEqualTo(webId);
        assertThat(rest.getForObject("/metadata/client", String.class)).contains("\"id\":\"actuator\"");
    }
}
