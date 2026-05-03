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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls the org's AI endpoint over plain REST (no AI-vendor SDK), parses the
 * assistant's JSON envelope, and returns the proposed files after path-safety
 * checks.
 *
 * <p>Two provider shapes are supported, selected by {@link AiProperties#getProvider()}:
 * <ul>
 *   <li><b>openai</b> — POSTs {@code {model, messages:[…]}} as JSON; reads
 *       {@code choices[0].message.content}.</li>
 *   <li><b>menora</b> — POSTs {@code multipart/form-data} with {@code input},
 *       {@code modelId}, {@code labels}; reads the response's {@code message}
 *       field. The system prompt is concatenated into {@code input} since the
 *       Menora gateway has no role separation.</li>
 * </ul>
 *
 * <p>Both paths converge on {@link #parseFilesEnvelope(String)} for the final
 * {@code {"files":[…]}} extraction + {@link SafePath} validation.
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
        String content = switch (resolveProvider()) {
            case "menora" -> callMenora(userMessage);
            case "openai" -> callOpenAi(userMessage);
            default -> throw new AiUpstreamException(
                    "Unknown ai.provider '" + props.getProvider() + "' (expected 'openai' or 'menora')");
        };
        return parseFilesEnvelope(content);
    }

    // ── Provider paths ────────────────────────────────────────────────────────

    /**
     * OpenAI-compatible chat/completions: JSON body, reads
     * {@code choices[0].message.content}. Returns the raw content text — the
     * shared envelope parser handles markdown fences and the {"files":[…]} parse.
     */
    private String callOpenAi(String userMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system",
                "content", props.getSystemPrompt() == null ? "" : props.getSystemPrompt()));
        messages.add(Map.of("role", "user", "content", userMessage));
        body.put("messages", messages);

        String raw = post(MediaType.APPLICATION_JSON, body);
        JsonNode root = readJson(raw);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || !contentNode.isTextual()) {
            throw new AiResponseParseException("AI response missing choices[0].message.content");
        }
        return contentNode.asText();
    }

    /**
     * Menora gateway: multipart/form-data with {@code input}, {@code modelId},
     * {@code labels}. The system prompt is concatenated into the single
     * {@code input} string — Claude Sonnet 4 follows the inline JSON-only
     * instruction reliably even without role separation.
     */
    private String callMenora(String userMessage) {
        if (props.getMenoraAppId() == null || props.getMenoraAppId().isBlank()) {
            throw new AiUpstreamException(
                    "ai.menora-app-id is required when ai.provider=menora");
        }

        String inputBody = (props.getSystemPrompt() == null ? "" : props.getSystemPrompt())
                + "\n\n" + userMessage;

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("input", inputBody);
        form.add("modelId", props.getModel());
        form.add("labels", "{\"app_id\":\"" + escapeJsonString(props.getMenoraAppId()) + "\"}");

        String raw = post(MediaType.MULTIPART_FORM_DATA, form);
        JsonNode root = readJson(raw);
        JsonNode message = root.path("message");
        if (!message.isTextual()) {
            throw new AiResponseParseException(
                    "AI response missing 'message' field (Menora response shape)");
        }
        return message.asText();
    }

    // ── HTTP + parsing ────────────────────────────────────────────────────────

    /** Common POST + auth + timeout/upstream exception mapping. */
    private String post(MediaType contentType, Object body) {
        try {
            RestClient.RequestBodySpec spec = client.post()
                    .uri(props.getEndpointUrl())
                    .contentType(contentType)
                    .accept(MediaType.APPLICATION_JSON);
            if (props.getAuthHeaderValue() != null && !props.getAuthHeaderValue().isBlank()) {
                spec = spec.header(props.getAuthHeaderName(), props.getAuthHeaderValue());
            }
            return spec.body(body).retrieve().body(String.class);
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
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiResponseParseException("AI returned empty body");
        }
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new AiResponseParseException("AI response was not JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * Shared envelope parser used by both provider paths. Takes the assistant's
     * text content (after any provider-specific extraction), strips an
     * optional ```json fence, and parses {@code {"files":[{path,content}]}}.
     * Path-validates and caps to {@link AiProperties#getMaxFiles()}.
     */
    List<GeneratedAiFile> parseFilesEnvelope(String content) {
        if (content == null || content.isBlank()) {
            throw new AiResponseParseException("AI returned empty assistant content");
        }
        String stripped = stripFences(content);
        JsonNode envelope;
        try {
            envelope = mapper.readTree(stripped);
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

    // ── User message + helpers ────────────────────────────────────────────────

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

    private String resolveProvider() {
        String p = props.getProvider();
        return p == null ? "openai" : p.trim().toLowerCase(Locale.ROOT);
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String orBlank(String s) {
        return s == null ? "" : s;
    }
}
