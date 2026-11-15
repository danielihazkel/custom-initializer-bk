package com.menora.initializr.config;

/**
 * Thrown by wizard input parsing (e.g. {@code mode} string → enum) when the
 * caller-supplied value is invalid. Surfaced as HTTP 400 by the wizard
 * controllers. Deliberately not a subclass of {@link IllegalArgumentException}
 * so the controller's handler does not accidentally swallow unrelated IAEs
 * thrown from deeper in the stack (e.g. DB enum mismatches).
 *
 * <p>A fullstack request is a tree, and its problems are reported with where in the tree they
 * are: {@link #path()} — {@code entities[1].fields[3].defaultValue}, {@code pages[2].widgets[0]}
 * — beside the sentence. The validators do not build that at each of their few hundred
 * {@code throw}s; the loops and sections around them add their own segment as the exception
 * passes ({@link #at(String)}), innermost first. The editor reads it to put the message on the
 * card, row or page that caused it; a message alone could only be shown as a toast.
 */
public class WizardArgumentException extends RuntimeException {

    private String path;

    public WizardArgumentException(String message) {
        super(message);
    }

    /** A problem at a known place, for a throw outside the validators' loops. */
    public WizardArgumentException(String message, String path) {
        super(message);
        this.path = path;
    }

    /**
     * Where in the request the problem is, in the request's own JSON names, or null when it is
     * about the request as a whole (or was thrown by code that does not say).
     */
    public String path() {
        return path;
    }

    /**
     * Puts {@code segment} in front of the path so far and returns this exception, for a
     * {@code catch (WizardArgumentException e) { throw e.at("fields[" + i + "]"); }} around a
     * loop body. An index segment ({@code [2]}) attaches to the segment before it without a dot.
     */
    public WizardArgumentException at(String segment) {
        if (segment == null || segment.isEmpty()) return this;
        if (path == null || path.isEmpty()) path = segment;
        else path = segment + (path.startsWith("[") ? "" : ".") + path;
        return this;
    }

    /**
     * Names the property the problem is about, when nothing more specific has: the innermost
     * segment, set at the throw itself ({@code throw new …(msg).on("defaultValue")}).
     */
    public WizardArgumentException on(String property) {
        if (path == null || path.isEmpty()) path = property;
        return this;
    }
}
