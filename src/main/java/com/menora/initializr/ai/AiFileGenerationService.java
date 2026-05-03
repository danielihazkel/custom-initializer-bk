package com.menora.initializr.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.ai.AiException.AiDisabledException;
import com.menora.initializr.ai.AiException.AiResponseParseException;
import com.menora.initializr.ai.AiException.AiTimeoutException;
import com.menora.initializr.ai.AiException.AiUpstreamException;
import com.menora.initializr.ai.AiDtos.AiGenerationRequest;
import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import com.menora.initializr.ai.AiDtos.ProjectFormDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the org's AI endpoint with an OpenAI-compatible chat-completions
 * request, parses the assistant's JSON envelope, and returns the proposed
 * files after path-safety checks.
 *
 * <p>The endpoint is treated as a black box: send {@code {model, messages}},
 * read {@code choices[0].message.content}, expect JSON of the shape
 * {@code {"files":[{"path","content"}]}}. The service never imports an
 * AI-vendor SDK so the YAML can swap the URL freely.
 */
@Service
public class AiFileGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AiFileGenerationService.class);

    private final AiProperties props;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiFileGenerationService(AiProperties props, RestClient aiRestClient) {
        this.props = props;
        this.client = aiRestClient;
    }

    public List<GeneratedAiFile> generate(AiGenerationRequest req) {
        if (!props.isEnabled()) {
            throw new AiDisabledException();
        }
        if (props.getEndpointUrl() == null || props.getEndpointUrl().isBlank()) {
            throw new AiUpstreamException("ai.endpoint-url is not configured");
        }
        if (req == null || req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        String userMessage = buildUserMessage(req);
        Map<String, Object> body = buildChatBody(props.getModel(), props.getSystemPrompt(), userMessage);

        String rawResponse;
        try {
            RestClient.RequestBodySpec spec = client.post()
                    .uri(props.getEndpointUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            if (props.getAuthHeaderValue() != null && !props.getAuthHeaderValue().isBlank()) {
                spec = spec.header(props.getAuthHeaderName(), props.getAuthHeaderValue());
            }
            rawResponse = spec.body(body).retrieve().body(String.class);
        } catch (ResourceAccessException ex) {
            if (ex.getCause() instanceof SocketTimeoutException) {
                throw new AiTimeoutException("AI endpoint timed out after "
                        + props.getTimeoutSeconds() + "s", ex);
            }
            throw new AiUpstreamException("AI endpoint unreachable: " + ex.getMessage(), ex);
        } catch (RestClientResponseException ex) {
            log.warn("AI endpoint returned {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new AiUpstreamException("AI endpoint returned HTTP " + ex.getStatusCode(), ex);
        }

        return parseResponse(rawResponse);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    static String buildUserMessage(AiGenerationRequest req) {
        ProjectFormDto f = req.form();
        StringBuilder sb = new StringBuilder();
        sb.append("Project metadata:\n");
        if (f != null) {
            sb.append("- groupId: ").append(orBlank(f.groupId())).append('\n');
            sb.append("- artifactId: ").append(orBlank(f.artifactId())).append('\n');
            sb.append("- packageName: ").append(orBlank(f.packageName())).append('\n');
            sb.append("- bootVersion: ").append(orBlank(f.bootVersion())).append('\n');
            sb.append("- javaVersion: ").append(orBlank(f.javaVersion())).append('\n');
            sb.append("- packaging: ").append(orBlank(f.packaging())).append('\n');
        }
        List<String> deps = req.dependencies() == null ? List.of() : req.dependencies();
        sb.append("Selected dependencies: ").append(deps.isEmpty() ? "(none)" : String.join(", ", deps)).append('\n');
        if (req.selectedOptions() != null && !req.selectedOptions().isEmpty()) {
            sb.append("Selected sub-options:\n");
            for (var e : req.selectedOptions().entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    sb.append("- ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
                }
            }
        }
        sb.append("\nUser request:\n").append(req.prompt().trim()).append('\n');
        return sb.toString();
    }

    private static Map<String, Object> buildChatBody(String model, String systemPrompt, String userMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));
        body.put("messages", messages);
        return body;
    }

    List<GeneratedAiFile> parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiResponseParseException("AI returned empty body");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new AiResponseParseException("AI response was not JSON: " + ex.getOriginalMessage(), ex);
        }
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || !contentNode.isTextual()) {
            throw new AiResponseParseException("AI response missing choices[0].message.content");
        }
        String content = stripFences(contentNode.asText());
        JsonNode envelope;
        try {
            envelope = mapper.readTree(content);
        } catch (JsonProcessingException ex) {
            throw new AiResponseParseException("AI message content was not JSON: " + ex.getOriginalMessage(), ex);
        }
        JsonNode filesNode = envelope.path("files");
        if (!filesNode.isArray()) {
            throw new AiResponseParseException("AI response missing 'files' array");
        }

        List<GeneratedAiFile> result = new ArrayList<>();
        int cap = Math.max(1, props.getMaxFiles());
        for (JsonNode file : filesNode) {
            if (result.size() >= cap) {
                log.warn("AI returned more than maxFiles={} — truncating", cap);
                break;
            }
            String path = file.path("path").asText(null);
            String fileContent = file.path("content").asText(null);
            if (path == null || fileContent == null) {
                continue;
            }
            try {
                String safe = SafePath.validate(path);
                result.add(new GeneratedAiFile(safe, fileContent));
            } catch (IllegalArgumentException ex) {
                log.warn("AI proposed unsafe path '{}' — skipping ({})", path, ex.getMessage());
            }
        }
        return result;
    }

    /**
     * Defensive: some models wrap their JSON in ```json fences despite the
     * system prompt asking them not to. Strip a single fenced block if present.
     */
    static String stripFences(String s) {
        String trimmed = s.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) return trimmed;
        String body = trimmed.substring(firstNewline + 1);
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.trim();
    }

    private static String orBlank(String s) {
        return s == null ? "" : s;
    }
}
