package com.menora.initializr.config;

/**
 * Thrown by wizard input parsing (e.g. {@code mode} string → enum) when the
 * caller-supplied value is invalid. Surfaced as HTTP 400 by the wizard
 * controllers. Deliberately not a subclass of {@link IllegalArgumentException}
 * so the controller's handler does not accidentally swallow unrelated IAEs
 * thrown from deeper in the stack (e.g. DB enum mismatches).
 */
public class WizardArgumentException extends RuntimeException {
    public WizardArgumentException(String message) {
        super(message);
    }
}
