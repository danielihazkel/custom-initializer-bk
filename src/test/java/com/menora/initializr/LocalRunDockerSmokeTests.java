package com.menora.initializr;

import com.menora.initializr.localrun.LocalRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link LocalRun} writes, run for real in Docker:
 * <ul>
 *   <li>a fullstack backend's API tests on PostgreSQL, Oracle and SQL Server through its
 *       {@code db-tests} profile — the first time the Flyway baselines run on those databases;</li>
 *   <li>a fullstack backend started on the {@code compose} profile against its compose services,
 *       answering its API;</li>
 *   <li>a plain project's {@code testcontainers} sample test;</li>
 *   <li>the frontend's nginx configuration signing calls as the developer only when told to.</li>
 * </ul>
 * Opt in with {@code -Dsmoke.docker=true}; needs Docker running and Maven able to download what
 * the generated projects need. Images come from Docker Hub itself here (the server's proxy
 * prefix is cleared), so it runs off the Menora network. Each case starts its own containers and
 * takes them down again; a case that fails half-way may leave a compose project behind
 * ({@code docker compose ls}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "menora.containers.hub-prefix=")
@EnabledIfSystemProperty(named = "smoke.docker", matches = "true")
class LocalRunDockerSmokeTests {

    private static final Logger log = LoggerFactory.getLogger(LocalRunDockerSmokeTests.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String MVN = IS_WINDOWS ? "mvn.cmd" : "mvn";
    private static final File NULL_DEVICE = new File(IS_WINDOWS ? "NUL" : "/dev/null");

    @Autowired
    private TestRestTemplate rest;

    /** Orders with the options whose columns a migration has to get right, on {@code db}. */
    private static Map<String, Object> orders(String db, String boot, boolean flyway) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("bootVersion", boot);
        List<String> deps = new ArrayList<>(List.of("web", "data-jpa", db, "validation"));
        if (flyway) deps.add("flyway");
        body.put("dependencies", deps);
        body.put("opts", Map.of("scaffold", List.of("tests", "audit", "softDelete", "optimisticLocking")));
        return body;
    }

    @ParameterizedTest(name = "{0} on Spring Boot {1}, Flyway {2}")
    @CsvSource({
            "postgresql, 3.2.1, true",
            "postgresql, " + FullstackGoldenOutputTests.BOOT_35 + ", true",
            "postgresql, " + FullstackGoldenOutputTests.BOOT_35 + ", false",
            "oracle, " + FullstackGoldenOutputTests.BOOT_35 + ", true",
            "mssql, " + FullstackGoldenOutputTests.BOOT_35 + ", true",
    })
    void theApiTestsPassOnTheRealDatabase(String db, String boot, boolean flyway, @TempDir Path workDir) throws Exception {
        // One database per run with -Dsmoke.db=oracle (a container and a nested build each: several at once is a lot).
        String only = System.getProperty("smoke.db");
        org.junit.jupiter.api.Assumptions.assumeTrue(only == null || only.equalsIgnoreCase(db), "another database was asked for");
        Path root = extract(workDir, fullstack(orders(db, boot, flyway)));
        // -Dsmoke.offline=true: the nested build may not download; it fails if an artifact is not cached.
        if (Boolean.getBoolean("smoke.offline")) {
            run(root.resolve("backend"), Duration.ofMinutes(30), MVN, "-B", "-o", "test", "-P" + LocalRun.DB_TESTS_PROFILE);
        } else {
            run(root.resolve("backend"), Duration.ofMinutes(30), MVN, "-B", "test", "-P" + LocalRun.DB_TESTS_PROFILE);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"postgresql", "oracle", "mssql"})
    @SuppressWarnings("unchecked")
    void theBackendRunsOnItsComposeServices(String db, @TempDir Path workDir) throws Exception {
        Map<String, Object> body = orders(db, FullstackGoldenOutputTests.BOOT_35, true);
        // Kafka beside PostgreSQL: the generated client has to reach a broker without SASL.
        if (db.equals("postgresql")) ((List<String>) body.get("dependencies")).add("kafka");
        Path root = extract(workDir, fullstack(body));
        String project = "smoke-" + db + "-" + ProcessHandle.current().pid();
        List<String> services = new ArrayList<>(List.of(LocalRun.serviceName(db)));
        if (db.equals("postgresql")) services.add("kafka");
        List<String> up = new ArrayList<>(List.of("docker", "compose", "-p", project, "up", "-d", "--wait"));
        up.addAll(services);
        Process app = null;
        try {
            run(root, Duration.ofMinutes(15), up.toArray(String[]::new));
            Path backend = root.resolve("backend");
            run(backend, Duration.ofMinutes(20), MVN, "-B", "package", "-DskipTests");
            int port = freePort();
            app = new ProcessBuilder("java", "-jar", "target/support.jar",
                    "--spring.profiles.active=" + LocalRun.PROFILE, "--server.port=" + port)
                    .directory(backend.toFile()).redirectErrorStream(true)
                    .redirectOutput(workDir.resolve("app.log").toFile()).redirectInput(NULL_DEVICE).start();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> answer = null;
            long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
            while (System.nanoTime() < deadline && app.isAlive()) {
                try {
                    answer = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/customers")).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (answer.statusCode() == 200) break;
                } catch (java.io.IOException notYet) {
                    // not listening yet
                }
                Thread.sleep(2000);
            }
            String appLog = Files.readString(workDir.resolve("app.log"));
            assertThat(answer).as(appLog).isNotNull();
            assertThat(answer.statusCode()).as(appLog).isEqualTo(200);
            assertThat(answer.body()).as(appLog).contains("\"content\"");
            if (db.equals("postgresql")) assertThat(appLog).doesNotContain("SaslAuthenticationException");
        } finally {
            if (app != null) {
                app.destroy();
                if (!app.waitFor(30, TimeUnit.SECONDS)) app.destroyForcibly();
            }
            run(root, Duration.ofMinutes(5), "docker", "compose", "-p", project, "down", "-v");
        }
    }

    @Test
    void thePlainSampleTestStartsTheApplicationOnPostgresql(@TempDir Path workDir) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "tcdemo");
        body.put("packageName", "com.menora.tcdemo");
        body.put("bootVersion", FullstackGoldenOutputTests.BOOT_35);
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "flyway", "testcontainers"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        ResponseEntity<byte[]> r = rest.postForEntity("/starter-wizard.zip", body, byte[].class);
        assertThat(r.getStatusCode()).as(r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Path root = extract(workDir, r.getBody());
        // The context-load test asks for the real database the application names; the sample
        // test brings its own.
        run(root, Duration.ofMinutes(20), MVN, "-B", "test", "-Dtest=ContainerDatabaseTests");
    }

    @Test
    void nginxSignsCallsAsTheDeveloperOnlyWhenTold(@TempDir Path workDir) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        Path root = extract(workDir, fullstack(body));
        Path conf = root.resolve("frontend/nginx/nginx.conf");
        Path entrypoint = root.resolve("frontend/entrypoint.sh");
        String network = "smoke-nginx-" + ProcessHandle.current().pid();
        run(workDir, Duration.ofMinutes(1), "docker", "network", "create", network);
        List<String> containers = new ArrayList<>();
        try {
            // An upstream that answers with the request it got, headers included.
            String echo = network + "-echo";
            run(workDir, Duration.ofMinutes(5), "docker", "run", "-d", "--rm", "--name", echo, "--network", network,
                    "-e", "HTTP_PORT=8080", "mendhak/http-https-echo:31");
            containers.add(echo);
            int on = freePort();
            int off = freePort();
            containers.add(nginx(network, "on", on, conf, entrypoint, echo));
            containers.add(nginx(network, "off", off, conf, entrypoint, echo));
            HttpClient http = HttpClient.newHttpClient();
            String plain = waitFor(http, on, null);
            assertThat(plain).as("DEV_USERINFO_ENABLED=on, no header").contains("\"userinfo\":\"dev-user\"");
            String signed = waitFor(http, on, "alice");
            assertThat(signed).as("DEV_USERINFO_ENABLED=on, the gateway's header").contains("\"userinfo\":\"alice\"");
            String server = waitFor(http, off, null);
            assertThat(server).as("DEV_USERINFO_ENABLED off, no header").doesNotContain("userinfo");
            assertThat(waitFor(http, off, "bob")).as("off, the gateway's header").contains("\"userinfo\":\"bob\"");
        } finally {
            for (String c : containers) run(workDir, Duration.ofMinutes(1), "docker", "rm", "-f", c);
            run(workDir, Duration.ofMinutes(1), "docker", "network", "rm", network);
        }
    }

    /** The generated nginx.conf and entrypoint.sh in the public nginx image, proxying /api/ to the echo. */
    private String nginx(String network, String devUser, int port, Path conf, Path entrypoint, String echo) throws Exception {
        String name = network + "-" + devUser;
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "--name", name, "--network", network,
                "-p", port + ":3000",
                "-v", conf.toAbsolutePath() + ":/src/nginx.conf:ro",
                "-v", entrypoint.toAbsolutePath() + ":/src/entrypoint.sh:ro",
                "-e", "API_UPSTREAM=http://" + echo + ":8080"));
        if (devUser.equals("on")) cmd.addAll(List.of("-e", "DEV_USERINFO_ENABLED=on", "-e", "DEV_USERINFO_DEFAULT=dev-user"));
        // Where the frontend's Dockerfile puts them; the image's default.conf would answer on :80
        // beside it. entrypoint.sh substitutes the variables and starts nginx.
        cmd.addAll(List.of("nginx:stable-alpine", "sh", "-c",
                "rm -f /etc/nginx/conf.d/default.conf && cp /src/nginx.conf /etc/nginx/conf.d/nginx.conf && sh /src/entrypoint.sh"));
        run(Path.of("."), Duration.ofMinutes(5), cmd.toArray(String[]::new));
        return name;
    }

    private static String waitFor(HttpClient http, int port, String userinfo) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/x"));
                if (userinfo != null) request.header("userinfo", userinfo);
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return response.body().replace(" ", "");
            } catch (java.io.IOException e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("nginx on :" + port + " did not answer", last);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private byte[] fullstack(Map<String, Object> body) {
        ResponseEntity<byte[]> r = FullstackExampleRequests.post(rest, body);
        assertThat(r.getStatusCode()).as(r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** Unpacks a zip into {@code workDir}; the project's root directory. */
    private static Path extract(Path workDir, byte[] zip) throws Exception {
        Path root = null;
        for (Map.Entry<String, byte[]> e : FullstackExampleRequests.unzip(zip).entrySet()) {
            Path target = workDir.resolve(e.getKey()).normalize();
            assertThat(target.startsWith(workDir)).isTrue();
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
            if (root == null) root = workDir.resolve(Path.of(e.getKey()).getName(0));
        }
        assertThat(root).isNotNull();
        return Files.isRegularFile(root) ? workDir : root;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String run(Path cwd, Duration timeout, String... cmd) throws Exception {
        log.info("[{}] $ {}", cwd.getFileName(), String.join(" ", cmd));
        Process proc = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectInput(NULL_DEVICE).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[{}]   {}", cwd.getFileName(), line);
                out.append(line).append('\n');
            }
        }
        if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError(String.join(" ", cmd) + " timed out after " + timeout);
        }
        assertThat(proc.exitValue()).as(String.join(" ", cmd) + " in " + cwd + "\n" + out).isZero();
        return out.toString();
    }
}
