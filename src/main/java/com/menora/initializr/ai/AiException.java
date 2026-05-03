package com.menora.initializr.ai;

/**
 * Base type for AI-feature errors. Subtypes carry HTTP-status semantics so the
 * controller can map each to a specific response code without an enum dance.
 */
public abstract class AiException extends RuntimeException {

    protected AiException(String message) {
        super(message);
    }

    protected AiException(String message, Throwable cause) {
        super(message, cause);
    }

    /** AI feature off in config — surface as 503. */
    public static class AiDisabledException extends AiException {
        public AiDisabledException() {
            super("AI file generation is disabled. Set ai.enabled=true and ai.endpoint-url to enable.");
        }
    }

    /** AI endpoint timed out — surface as 504. */
    public static class AiTimeoutException extends AiException {
        public AiTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** AI endpoint returned non-2xx or could not be reached — surface as 502. */
    public static class AiUpstreamException extends AiException {
        public AiUpstreamException(String message, Throwable cause) {
            super(message, cause);
        }
        public AiUpstreamException(String message) {
            super(message);
        }
    }

    /** AI response was 200 but malformed/unparseable — surface as 502. */
    public static class AiResponseParseException extends AiException {
        public AiResponseParseException(String message) {
            super(message);
        }
        public AiResponseParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
