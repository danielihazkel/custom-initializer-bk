package com.menora.initializr.admin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who is an admin, as the LDAP REST service answers ({@code GET /employee/findGroupsByCn/{user}},
 * a JSON list of group names) — here a stub of it. And the admin's name in the token.
 */
class AdminDirectoryTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    private String serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ldapServices/employee/findGroupsByCn/", exchange -> {
            calls.incrementAndGet();
            String user = exchange.getRequestURI().getPath().substring(exchange.getRequestURI().getPath().lastIndexOf('/') + 1);
            byte[] bytes = body.replace("{user}", user).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/ldapServices";
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void aMemberOfTheAdminGroupIsAnAdmin() throws Exception {
        String url = serve(200, "[\"CN=GRP_LTS_InitializrAdmin,OU=Groups\", \"GRP_Everyone\"]");
        AdminDirectory directory = new AdminDirectory(url, "GRP_LTS_InitializrAdmin", "", RestClient.builder());
        assertThat(directory.ssoAvailable()).isTrue();
        assertThat(directory.isAdmin("dana")).isTrue();
        // Remembered for a minute: the directory is asked once.
        assertThat(directory.isAdmin("dana")).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void anyoneElseIsNot() throws Exception {
        String url = serve(200, "[\"GRP_Everyone\"]");
        AdminDirectory directory = new AdminDirectory(url, "GRP_LTS_InitializrAdmin", "", RestClient.builder());
        assertThat(directory.isAdmin("mallory")).isFalse();
        assertThat(directory.isAdmin(" ")).isFalse();
    }

    @Test
    void aDirectoryThatDoesNotAnswerIsSaidSo() throws Exception {
        String url = serve(500, "{}");
        AdminDirectory directory = new AdminDirectory(url, "GRP_LTS_InitializrAdmin", "", RestClient.builder());
        assertThatThrownBy(() -> directory.isAdmin("dana")).isInstanceOf(AdminDirectory.DirectoryUnavailableException.class);
    }

    @Test
    void withoutTheDirectoryOnlyDevUsersAre() {
        AdminDirectory none = new AdminDirectory("", "", "", RestClient.builder());
        assertThat(none.ssoAvailable()).isFalse();
        assertThat(none.isAdmin("dana")).isFalse();
        AdminDirectory dev = new AdminDirectory("", "", "dana, eli", RestClient.builder());
        assertThat(dev.ssoAvailable()).isTrue();
        assertThat(dev.isAdmin("eli")).isTrue();
        assertThat(dev.isAdmin("mallory")).isFalse();
    }

    @Test
    void theTokenCarriesTheAdminsName() {
        AdminTokenStore tokens = new AdminTokenStore("pw", "", Duration.ofHours(1));
        assertThat(tokens.userOf(tokens.issue("dana"))).hasValue("dana");
        assertThat(tokens.userOf(tokens.issue("ünï.code user"))).hasValue("ünï.code user");
        assertThat(tokens.userOf(tokens.issue())).hasValue(AdminTokenStore.PASSWORD_USER);
        assertThat(tokens.userOf("forged.token")).isEmpty();
        // Another password, another key: the token is not accepted.
        assertThat(new AdminTokenStore("other", "", Duration.ofHours(1)).userOf(tokens.issue("dana"))).isEmpty();
    }
}
