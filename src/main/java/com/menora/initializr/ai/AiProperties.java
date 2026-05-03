package com.menora.initializr.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional AI file-contribution feature. The wizard UI
 * lets users describe extra files in natural language; this class binds the
 * {@code ai.*} keys that point at the org's AI endpoint and shape the request.
 *
 * <p>The implementation calls the endpoint via plain {@link
 * org.springframework.web.client.RestClient} — no AI-specific SDK — so the
 * endpoint can be swapped to OpenAI, Anthropic-via-proxy, vLLM, Ollama, or any
 * internal proxy that speaks the OpenAI {@code chat/completions} shape.
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** Master switch. When false, {@code POST /ai/generate-files} returns 503. */
    private boolean enabled = false;

    /** Full URL the {@code POST {model, messages}} body is sent to. */
    private String endpointUrl = "";

    /** Header name carrying credentials (e.g. {@code Authorization}). */
    private String authHeaderName = "Authorization";

    /**
     * Header value (e.g. {@code Bearer sk-…}). Blank means no auth header is
     * sent, which is correct for unauthenticated local proxies.
     */
    private String authHeaderValue = "";

    /** Model identifier passed in the request body. */
    private String model = "gpt-4o-mini";

    /** Connect/read timeout for the AI call. */
    private int timeoutSeconds = 60;

    /** Hard cap on the number of files the AI is allowed to return. */
    private int maxFiles = 20;

    /**
     * System message instructing the AI how to shape its response. The default
     * pins the JSON envelope the service parses out of {@code choices[0].message.content}.
     */
    private String systemPrompt = """
            You are a Spring Boot project scaffolding assistant. The user has selected
            dependencies and asked you to add extra files to their generated project.
            Respond with ONLY a JSON object of the shape:
            {"files":[{"path":"src/main/java/...","content":"..."}]}
            Paths are relative to the project root. Do not wrap the JSON in markdown.
            Do not include any explanation outside the JSON.
            """;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }

    public String getAuthHeaderName() { return authHeaderName; }
    public void setAuthHeaderName(String authHeaderName) { this.authHeaderName = authHeaderName; }

    public String getAuthHeaderValue() { return authHeaderValue; }
    public void setAuthHeaderValue(String authHeaderValue) { this.authHeaderValue = authHeaderValue; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}
