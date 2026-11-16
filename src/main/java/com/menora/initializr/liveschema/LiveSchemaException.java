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

    public LiveSchemaException(HttpStatus status, String error, String detail) {
        super(detail);
        this.status = status;
        this.error = error;
    }

    public static LiveSchemaException badRequest(String detail) {
        return new LiveSchemaException(HttpStatus.BAD_REQUEST, "Invalid connection", detail);
    }

    public HttpStatus status() { return status; }

    public String error() { return error; }
}
