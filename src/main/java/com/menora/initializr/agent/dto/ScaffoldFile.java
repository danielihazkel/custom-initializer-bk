package com.menora.initializr.agent.dto;

/**
 * One file in the {@link ScaffoldResponse}. Text files carry their content
 * inline as UTF-8; binary files are base64-encoded so the envelope stays JSON.
 */
public record ScaffoldFile(String path, String encoding, String content, String sha256) {
    public static final String UTF8 = "utf-8";
    public static final String BASE64 = "base64";
}
