package com.menora.initializr.liveschema;

import org.springframework.http.HttpStatus;

/**
 * A live schema request that cannot be served, with the status to answer and a message meant for
 * the person at the screen. Never carries a password: messages from a driver go through
 * {@link LiveSchemaReader#describe}.
 */
public class LiveSchemaException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final java.util.List<String> existing;

    public LiveSchemaException(HttpStatus status, String error, String detail) {
        this(status, error, detail, null);
    }

    /** @param existing tables that are in the way of creating a model's tables — a 409's list */
    public LiveSchemaException(HttpStatus status, String error, String detail, java.util.List<String> existing) {
        super(detail);
        this.status = status;
        this.error = error;
        this.existing = existing;
    }

    public static LiveSchemaException badRequest(String detail) {
        return new LiveSchemaException(HttpStatus.BAD_REQUEST, "Invalid connection", detail);
    }

    public HttpStatus status() { return status; }

    public String error() { return error; }

    /** The tables already there, when creating a model's tables was refused for them; else null. */
    public java.util.List<String> existing() { return existing; }
}
