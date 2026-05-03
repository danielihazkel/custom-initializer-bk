package com.menora.initializr.ai;

import com.menora.initializr.ai.AiDtos.AiGenerationRequest;
import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import com.menora.initializr.ai.AiDtos.ProjectFormDto;
import com.menora.initializr.ai.AiException.AiDisabledException;
import com.menora.initializr.ai.AiException.AiResponseParseException;
import com.menora.initializr.ai.AiException.AiUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AiFileGenerationService}. Exercise the shared
 * {@code parseFilesEnvelope} helper that both provider paths converge on,
 * plus the up-front validation that runs before any HTTP call (disabled
 * feature, blank prompt, blank URL, missing Menora app_id, unknown provider).
 *
 * <p>The HTTP transport itself isn't exercised here — that's covered by
 * manual smoke tests against real endpoints.
 */
class AiFileGenerationServiceTests {

    private AiProperties props;
    private AiFileGenerationService service;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        props.setEnabled(true);
        props.setEndpointUrl("http://localhost:9999/v1/chat/completions");
        props.setProvider("openai");
        props.setModel("test-model");
        props.setMaxFiles(20);
        service = new AiFileGenerationService(props, RestClient.create());
    }

    // ── Up-front validation ───────────────────────────────────────────────────

    @Test
    void disabledFeatureThrowsAiDisabledException() {
        props.setEnabled(false);
        AiGenerationRequest req = new AiGenerationRequest(
                emptyForm(), List.of("web"), Map.of(), "do something");
        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(AiDisabledException.class);
    }

    @Test
    void blankPromptIsRejected() {
        AiGenerationRequest req = new AiGenerationRequest(
                emptyForm(), List.of("web"), Map.of(), "   ");
        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankEndpointUrlThrowsUpstreamException() {
        props.setEndpointUrl("");
        AiGenerationRequest req = new AiGenerationRequest(
                emptyForm(), List.of("web"), Map.of(), "do something");
        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessageContaining("ai.endpoint-url");
    }

    @Test
    void unknownProviderThrowsUpstreamException() {
        props.setProvider("anthropic-direct");
        AiGenerationRequest req = new AiGenerationRequest(
                emptyForm(), List.of("web"), Map.of(), "do something");
        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessageContaining("Unknown ai.provider");
    }

    @Test
    void menoraProviderWithoutAppIdThrowsUpstreamException() {
        props.setProvider("menora");
        props.setMenoraAppId("");
        AiGenerationRequest req = new AiGenerationRequest(
                emptyForm(), List.of("web"), Map.of(), "do something");
        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessageContaining("ai.menora-app-id");
    }

    // ── Shared envelope parser ────────────────────────────────────────────────

    @Test
    void parseFilesEnvelopeHappyPath() {
        String content = "{\"files\":["
                + "{\"path\":\"src/main/java/com/menora/demo/Greeter.java\",\"content\":\"public class Greeter {}\"},"
                + "{\"path\":\"src/main/java/com/menora/demo/Util.java\",\"content\":\"public class Util {}\"}"
                + "]}";

        List<GeneratedAiFile> files = service.parseFilesEnvelope(content);

        assertThat(files).hasSize(2);
        assertThat(files.get(0).path()).isEqualTo("src/main/java/com/menora/demo/Greeter.java");
        assertThat(files.get(0).content()).isEqualTo("public class Greeter {}");
    }

    @Test
    void parseFilesEnvelopeStripsMarkdownFences() {
        String fenced = "```json\n{\"files\":[{\"path\":\"a/b.txt\",\"content\":\"hi\"}]}\n```";

        List<GeneratedAiFile> files = service.parseFilesEnvelope(fenced);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("a/b.txt");
    }

    @Test
    void parseFilesEnvelopeRejectsUnsafePathsAndKeepsValidOnes() {
        String content = "{\"files\":["
                + "{\"path\":\"../../../etc/passwd\",\"content\":\"bad\"},"
                + "{\"path\":\"/absolute/no.txt\",\"content\":\"bad\"},"
                + "{\"path\":\"pom.xml\",\"content\":\"bad\"},"
                + "{\"path\":\"src/main/java/Ok.java\",\"content\":\"good\"}"
                + "]}";

        List<GeneratedAiFile> files = service.parseFilesEnvelope(content);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("src/main/java/Ok.java");
    }

    @Test
    void parseFilesEnvelopeCapsAtMaxFiles() {
        props.setMaxFiles(2);
        StringBuilder b = new StringBuilder("{\"files\":[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) b.append(',');
            b.append("{\"path\":\"file").append(i).append(".txt\",\"content\":\"x\"}");
        }
        b.append("]}");

        List<GeneratedAiFile> files = service.parseFilesEnvelope(b.toString());

        assertThat(files).hasSize(2);
    }

    @Test
    void parseFilesEnvelopeRejectsNonJsonContent() {
        assertThatThrownBy(() -> service.parseFilesEnvelope("here is some prose, no JSON to be found"))
                .isInstanceOf(AiResponseParseException.class);
    }

    @Test
    void parseFilesEnvelopeRejectsContentMissingFilesArray() {
        assertThatThrownBy(() -> service.parseFilesEnvelope("{\"summary\":\"nothing here\"}"))
                .isInstanceOf(AiResponseParseException.class);
    }

    @Test
    void parseFilesEnvelopeRejectsBlankContent() {
        assertThatThrownBy(() -> service.parseFilesEnvelope("   "))
                .isInstanceOf(AiResponseParseException.class);
    }

    // ── User message composition ──────────────────────────────────────────────

    @Test
    void buildUserMessageIncludesProjectMetadataAndDeps() {
        AiGenerationRequest req = new AiGenerationRequest(
                new ProjectFormDto("com.acme", "demo", "demo", "desc",
                        "com.acme.demo", "3.2.1", "21", "jar"),
                List.of("web", "kafka"),
                Map.of("kafka", List.of("consumer-example")),
                "Add a simple controller.");

        String msg = AiFileGenerationService.buildUserMessage(req);

        assertThat(msg).contains("com.acme");
        assertThat(msg).contains("com.acme.demo");
        assertThat(msg).contains("web, kafka");
        assertThat(msg).contains("consumer-example");
        assertThat(msg).contains("Add a simple controller.");
    }

    private static ProjectFormDto emptyForm() {
        return new ProjectFormDto("g", "a", "n", "d", "p.k", "3.2.1", "21", "jar");
    }
}
