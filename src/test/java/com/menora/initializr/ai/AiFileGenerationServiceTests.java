package com.menora.initializr.ai;

import com.menora.initializr.ai.AiDtos.AiGenerationRequest;
import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import com.menora.initializr.ai.AiDtos.ProjectFormDto;
import com.menora.initializr.ai.AiException.AiDisabledException;
import com.menora.initializr.ai.AiException.AiResponseParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AiFileGenerationService} that exercise response
 * parsing, path safety filtering, and the disabled-feature path. The actual
 * HTTP call is not invoked here — that path is exercised end-to-end against
 * the controller in the integration test.
 */
class AiFileGenerationServiceTests {

    private AiProperties props;
    private AiFileGenerationService service;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        props.setEnabled(true);
        props.setEndpointUrl("http://localhost:9999/v1/chat/completions");
        props.setModel("test-model");
        props.setMaxFiles(20);
        service = new AiFileGenerationService(props, RestClient.create());
    }

    @Test
    void disabledFeatureThrowsAiDisabledException() {
        props.setEnabled(false);
        AiGenerationRequest req = new AiGenerationRequest(
                emptyForm(), List.of("web"), Map.of(), "do something");
        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(AiDisabledException.class);
    }

    @Test
    void parsesHappyPathChatCompletionsResponse() {
        String content = "{\"files\":["
                + "{\"path\":\"src/main/java/com/menora/demo/Greeter.java\",\"content\":\"public class Greeter {}\"},"
                + "{\"path\":\"src/main/java/com/menora/demo/Util.java\",\"content\":\"public class Util {}\"}"
                + "]}";
        String raw = wrapAsChatCompletion(content);

        List<GeneratedAiFile> files = service.parseResponse(raw);

        assertThat(files).hasSize(2);
        assertThat(files.get(0).path()).isEqualTo("src/main/java/com/menora/demo/Greeter.java");
        assertThat(files.get(0).content()).isEqualTo("public class Greeter {}");
    }

    @Test
    void stripsMarkdownFencesAroundJsonContent() {
        String fenced = "```json\n{\"files\":[{\"path\":\"a/b.txt\",\"content\":\"hi\"}]}\n```";
        String raw = wrapAsChatCompletion(fenced);

        List<GeneratedAiFile> files = service.parseResponse(raw);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("a/b.txt");
    }

    @Test
    void rejectsUnsafePathsAndKeepsValidOnes() {
        String content = "{\"files\":["
                + "{\"path\":\"../../../etc/passwd\",\"content\":\"bad\"},"
                + "{\"path\":\"/absolute/no.txt\",\"content\":\"bad\"},"
                + "{\"path\":\"pom.xml\",\"content\":\"bad\"},"
                + "{\"path\":\"src/main/java/Ok.java\",\"content\":\"good\"}"
                + "]}";
        String raw = wrapAsChatCompletion(content);

        List<GeneratedAiFile> files = service.parseResponse(raw);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("src/main/java/Ok.java");
    }

    @Test
    void capsFileCountAtMaxFiles() {
        props.setMaxFiles(2);
        StringBuilder b = new StringBuilder("{\"files\":[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) b.append(',');
            b.append("{\"path\":\"file").append(i).append(".txt\",\"content\":\"x\"}");
        }
        b.append("]}");
        String raw = wrapAsChatCompletion(b.toString());

        List<GeneratedAiFile> files = service.parseResponse(raw);

        assertThat(files).hasSize(2);
    }

    @Test
    void malformedTopLevelJsonThrowsParseException() {
        assertThatThrownBy(() -> service.parseResponse("not json at all"))
                .isInstanceOf(AiResponseParseException.class);
    }

    @Test
    void missingChoicesContentThrowsParseException() {
        assertThatThrownBy(() -> service.parseResponse("{\"choices\":[]}"))
                .isInstanceOf(AiResponseParseException.class);
    }

    @Test
    void messageContentThatIsntJsonThrowsParseException() {
        String raw = wrapAsChatCompletion("here is some prose, no JSON to be found");
        assertThatThrownBy(() -> service.parseResponse(raw))
                .isInstanceOf(AiResponseParseException.class);
    }

    @Test
    void messageContentMissingFilesArrayThrowsParseException() {
        String raw = wrapAsChatCompletion("{\"summary\":\"nothing here\"}");
        assertThatThrownBy(() -> service.parseResponse(raw))
                .isInstanceOf(AiResponseParseException.class);
    }

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

    @Test
    void blankPromptIsRejected() {
        AiGenerationRequest req = new AiGenerationRequest(
                emptyForm(), List.of("web"), Map.of(), "   ");
        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProjectFormDto emptyForm() {
        return new ProjectFormDto("g", "a", "n", "d", "p.k", "3.2.1", "21", "jar");
    }

    /** Wrap a content string in the OpenAI-style chat-completions envelope. */
    private static String wrapAsChatCompletion(String content) {
        // The content needs JSON-string escaping
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}]}";
    }
}
