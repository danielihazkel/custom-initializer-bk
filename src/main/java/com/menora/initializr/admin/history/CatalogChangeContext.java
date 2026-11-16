package com.menora.initializr.admin.history;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who is changing the catalog on this thread, why, and as part of which request — what
 * {@link CatalogHistoryRecorder} writes beside each change. The admin filter opens one for every
 * signed-in admin request ({@code source} "admin"); an import, a catalog-sync apply or a
 * template reset name their own source within it; the start-up syncs run as "system". A change
 * made with no context open is "system" / "system", one request of its own.
 *
 * <p>{@link #suppressed} turns recording off — the first seeding of an empty database, which is
 * the release itself, not a change anybody made.
 */
public final class CatalogChangeContext {

    public static final String SYSTEM = "system";

    record State(String actor, String source, String requestId, boolean suppressed) {}

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private CatalogChangeContext() {
    }

    static State current() {
        State state = CURRENT.get();
        return state != null ? state : new State(SYSTEM, SYSTEM, null, false);
    }

    /** Opens a context for a request; {@link #close()} when it is done. */
    public static void open(String actor, String source) {
        CURRENT.set(new State(actor, source, UUID.randomUUID().toString(), false));
    }

    public static void close() {
        CURRENT.remove();
    }

    /** The request this thread's changes belong to, or null outside one. */
    public static String requestId() {
        State state = CURRENT.get();
        return state == null ? null : state.requestId();
    }

    /** Runs {@code work} as {@code source}, by the current actor (or "system"), in the current request (or a new one). */
    public static <T> T as(String source, Supplier<T> work) {
        State previous = CURRENT.get();
        State base = current();
        CURRENT.set(new State(base.actor(), source,
                base.requestId() != null ? base.requestId() : UUID.randomUUID().toString(), base.suppressed()));
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void as(String source, Runnable work) {
        as(source, () -> {
            work.run();
            return null;
        });
    }

    /** Runs {@code work} with nothing recorded. */
    public static void suppressed(Runnable work) {
        State previous = CURRENT.get();
        State base = current();
        CURRENT.set(new State(base.actor(), base.source(), base.requestId(), true));
        try {
            work.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
