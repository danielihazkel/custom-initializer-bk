package com.menora.initializr.gen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What one generation request has already worked out from a wizard's document: the document
 * parsed, and the files rendered from it. A wizard request used to read each document two or
 * three times — once to check it (so a bad one is a 400 with nothing on disk), again in the
 * contributor that writes the files, and again in a build customizer — and the swagger and
 * wsdl4j parsers are the expensive part of a generation.
 *
 * <p>Only while a request has {@link #open opened} it: outside one (a test, a detect endpoint,
 * the fullstack import) every call computes, so nothing is kept on a pooled thread. The key is
 * everything the result depends on, so a caller that asks with other inputs (another package, a
 * starter the validation did not know of) computes its own instead of reusing a wrong one. A
 * computation that throws is not kept.
 *
 * <p>The values are shared between the callers that hit: they must be read, never modified.
 */
public final class WizardMemo {

    /** One remembered result: what was computed ({@code kind}) and from what. */
    public record Key(String kind, List<Object> inputs) {
        public static Key of(String kind, Object... inputs) {
            return new Key(kind, Arrays.asList(inputs));
        }
    }

    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();

    /** The kinds computed in the last session closed on any thread — for tests. */
    private static volatile List<String> lastComputed = List.of();

    private WizardMemo() {}

    /** Starts remembering for the current thread's request (a second open starts afresh). */
    public static void open() {
        SESSION.set(new Session());
    }

    /** Forgets everything the current thread remembered. Safe to call when nothing is open. */
    public static void close() {
        Session session = SESSION.get();
        if (session != null) lastComputed = List.copyOf(session.computed);
        SESSION.remove();
    }

    public static boolean isOpen() {
        return SESSION.get() != null;
    }

    /** The remembered value for {@code key}, computing and remembering it the first time. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Key key, Supplier<T> compute) {
        Session session = SESSION.get();
        if (session == null) return compute.get();
        if (session.values.containsKey(key)) return (T) session.values.get(key);
        T value = compute.get();
        session.values.put(key, value);
        session.computed.add(key.kind());
        return value;
    }

    /** What the last closed session computed, one entry per computation, in order. */
    public static List<String> lastComputed() {
        return lastComputed;
    }

    private static final class Session {
        final Map<Key, Object> values = new HashMap<>();
        final List<String> computed = new ArrayList<>();
    }
}
