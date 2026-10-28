package com.menora.initializr.design;

import org.springframework.http.HttpStatus;

import java.util.List;

/** A design-kit request that cannot be served; answered as {@code {error, detail, problems?}}. */
public class DesignKitException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> problems;

    public DesignKitException(HttpStatus status, String message, List<String> problems) {
        super(message);
        this.status = status;
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static DesignKitException badRequest(String message) {
        return new DesignKitException(HttpStatus.BAD_REQUEST, message, null);
    }

    public static DesignKitException invalid(List<String> problems) {
        return new DesignKitException(HttpStatus.BAD_REQUEST, problems.size() == 1 ? problems.get(0)
                : problems.size() + " problems: " + problems.get(0) + " …", problems);
    }

    public static DesignKitException notFound(String message) {
        return new DesignKitException(HttpStatus.NOT_FOUND, message, null);
    }

    public static DesignKitException conflict(String message) {
        return new DesignKitException(HttpStatus.CONFLICT, message, null);
    }

    public HttpStatus status() {
        return status;
    }

    public List<String> problems() {
        return problems;
    }
}
