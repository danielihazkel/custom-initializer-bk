package com.menora.initializr.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing a way into the admin screens.
 *
 * <p><b>One address.</b> After {@code admin.login.max-failures} refused sign-ins from one address
 * within {@code admin.login.window}, that address is refused until the window has passed. A
 * sign-in that succeeds clears its count. The shared password and signing in as oneself
 * ({@link Kind}) are counted apart: a mistyped password does not lock a person's own account out.
 *
 * <p><b>Every address together.</b> The admin password is one shared secret, and guesses spread
 * over many addresses stay under any per-address limit. So after
 * {@code admin.login.max-failures-total} wrong passwords within the window, from wherever, the
 * password signs nobody in until the oldest of those windows has passed. Signing in as oneself is
 * not held to it: there is no shared secret to guess there, and it is how an admin gets in while
 * the password is closed. Where the password is the only way in, the ceiling is also how somebody
 * could keep the admins out by guessing on purpose — {@code 0} switches it off.
 *
 * <p><b>Counted in the database</b> ({@code initializer_admin_login_failure}), so every instance
 * behind a load balancer sees the same counts; kept per JVM, an address got its tries on each of
 * them. If the table cannot be read or written the sign-in goes on unlimited rather than failing:
 * the limit is a brake, not a lock.
 *
 * <p>The address is the connection's ({@code getRemoteAddr}), never a header the caller can set.
 * Behind a reverse proxy that is the proxy's address — every admin then shares one count — unless
 * the application is told to believe the proxy: {@code server.forward-headers-strategy=native}
 * (or {@code framework}), which is only safe when nothing but the proxy can reach the
 * application. {@code admin.login.max-failures=0} switches the per-address limit off.
 */
@Component
public class AdminLoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginThrottle.class);

    /** Which way in was refused. */
    public enum Kind { PASSWORD, SSO }

    /** One address's failures in its current window. */
    record Count(long windowStartMillis, int failures) {}

    /** Every address's failures of one kind in the windows still running, and when the oldest began. */
    record Total(long failures, long oldestWindowStartMillis) {}

    /** Where the counts are kept. */
    interface Store {

        /** The address's count; null when it has none. */
        Count read(Kind kind, String address);

        /** One more failure: added to the window still running, or the first of a new one. */
        void record(Kind kind, String address, long nowMillis, long windowMillis);

        void clear(Kind kind, String address);

        /** The failures of every address whose window began after {@code sinceMillis}. */
        Total total(Kind kind, long sinceMillis);

        /** Forgets the windows that began at or before {@code beforeMillis}. */
        void purge(long beforeMillis);
    }

    private final int maxFailures;
    private final int maxFailuresTotal;
    private final long windowMillis;
    private final Clock clock;
    private final Store store;

    @Autowired
    public AdminLoginThrottle(@Value("${admin.login.max-failures:10}") int maxFailures,
                              @Value("${admin.login.max-failures-total:100}") int maxFailuresTotal,
                              @Value("${admin.login.window:5m}") Duration window,
                              JdbcTemplate jdbc) {
        this(maxFailures, maxFailuresTotal, window, Clock.systemUTC(), new JdbcStore(jdbc));
    }

    /** Counts kept in memory: for a test of the rules. */
    AdminLoginThrottle(int maxFailures, Duration window, Clock clock) {
        this(maxFailures, 0, window, clock, new MemoryStore());
    }

    AdminLoginThrottle(int maxFailures, int maxFailuresTotal, Duration window, Clock clock, Store store) {
        this.maxFailures = maxFailures;
        this.maxFailuresTotal = maxFailuresTotal;
        this.windowMillis = window.toMillis();
        this.clock = clock;
        this.store = store;
    }

    /** Seconds until the password may be tried from {@code address} again; 0 when it may be now. */
    public long retryAfterSeconds(String address) {
        return retryAfterSeconds(Kind.PASSWORD, address);
    }

    /** A wrong password from {@code address}. */
    public void failed(String address) {
        failed(Kind.PASSWORD, address);
    }

    /** The right password from {@code address}: its count starts over. */
    public void succeeded(String address) {
        succeeded(Kind.PASSWORD, address);
    }

    /** Seconds until {@code address} may try this way in again; 0 when it may try now. */
    public long retryAfterSeconds(Kind kind, String address) {
        try {
            long now = clock.millis();
            long wait = 0;
            if (maxFailures > 0) {
                Count count = store.read(kind, key(address));
                if (count != null && count.failures() >= maxFailures) {
                    wait = Math.max(wait, count.windowStartMillis() + windowMillis - now);
                }
            }
            if (kind == Kind.PASSWORD && maxFailuresTotal > 0) {
                Total total = store.total(kind, now - windowMillis);
                if (total.failures() >= maxFailuresTotal) {
                    wait = Math.max(wait, total.oldestWindowStartMillis() + windowMillis - now);
                }
            }
            return wait <= 0 ? 0 : (wait + 999) / 1000;
        } catch (DataAccessException e) {
            log.warn("The admin sign-in limit could not be read ({}): this sign-in is not limited", e.toString());
            return 0;
        }
    }

    /** A refused sign-in from {@code address}. */
    public void failed(Kind kind, String address) {
        if (maxFailures <= 0 && (kind != Kind.PASSWORD || maxFailuresTotal <= 0)) return;
        try {
            long now = clock.millis();
            store.purge(now - windowMillis);
            store.record(kind, key(address), now, windowMillis);
        } catch (DataAccessException e) {
            log.warn("A refused admin sign-in could not be counted ({})", e.toString());
        }
    }

    /** A sign-in that succeeded from {@code address}: its count of this kind starts over. */
    public void succeeded(Kind kind, String address) {
        try {
            store.clear(kind, key(address));
        } catch (DataAccessException e) {
            log.warn("The admin sign-in count of an address could not be cleared ({})", e.toString());
        }
    }

    private static String key(String address) {
        String key = address == null || address.isBlank() ? "unknown" : address.trim();
        return key.length() > 100 ? key.substring(0, 100) : key;
    }

    // ── In the database ──────────────────────────────────────────────────────

    /** Each step is one statement, so two instances counting the same address at once both count. */
    static final class JdbcStore implements Store {

        private static final String TABLE = "initializer_admin_login_failure";

        private final JdbcTemplate jdbc;

        JdbcStore(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Count read(Kind kind, String address) {
            return jdbc.query("SELECT window_start, failures FROM " + TABLE + " WHERE kind = ? AND address = ?",
                    rs -> rs.next() ? new Count(rs.getLong(1), rs.getInt(2)) : null, kind.name(), address);
        }

        @Override
        public void record(Kind kind, String address, long nowMillis, long windowMillis) {
            for (int attempt = 0; attempt < 2; attempt++) {
                // The window still running: one more.
                if (jdbc.update("UPDATE " + TABLE + " SET failures = failures + 1 WHERE kind = ? AND address = ? "
                        + "AND window_start > ?", kind.name(), address, nowMillis - windowMillis) > 0) return;
                // A window that has passed: a new one.
                if (jdbc.update("UPDATE " + TABLE + " SET window_start = ?, failures = 1 WHERE kind = ? AND address = ?",
                        nowMillis, kind.name(), address) > 0) return;
                try {
                    jdbc.update("INSERT INTO " + TABLE + " (kind, address, window_start, failures) VALUES (?, ?, ?, 1)",
                            kind.name(), address, nowMillis);
                    return;
                } catch (DuplicateKeyException another) {
                    // Another instance counted this address first, just now: add to its row.
                }
            }
        }

        @Override
        public void clear(Kind kind, String address) {
            jdbc.update("DELETE FROM " + TABLE + " WHERE kind = ? AND address = ?", kind.name(), address);
        }

        @Override
        public Total total(Kind kind, long sinceMillis) {
            return jdbc.query("SELECT COALESCE(SUM(failures), 0), COALESCE(MIN(window_start), 0) FROM " + TABLE
                            + " WHERE kind = ? AND window_start > ?",
                    rs -> rs.next() ? new Total(rs.getLong(1), rs.getLong(2)) : new Total(0, 0), kind.name(), sinceMillis);
        }

        @Override
        public void purge(long beforeMillis) {
            jdbc.update("DELETE FROM " + TABLE + " WHERE window_start <= ?", beforeMillis);
        }
    }

    // ── In memory ────────────────────────────────────────────────────────────

    static final class MemoryStore implements Store {

        private final Map<String, Count> counts = new ConcurrentHashMap<>();

        private static String key(Kind kind, String address) {
            return kind.name() + ' ' + address;
        }

        @Override
        public Count read(Kind kind, String address) {
            return counts.get(key(kind, address));
        }

        @Override
        public void record(Kind kind, String address, long nowMillis, long windowMillis) {
            counts.merge(key(kind, address), new Count(nowMillis, 1), (old, first) ->
                    old.windowStartMillis() + windowMillis <= nowMillis ? first
                            : new Count(old.windowStartMillis(), old.failures() + 1));
        }

        @Override
        public void clear(Kind kind, String address) {
            counts.remove(key(kind, address));
        }

        @Override
        public Total total(Kind kind, long sinceMillis) {
            long failures = 0;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Count> entry : counts.entrySet()) {
                if (!entry.getKey().startsWith(kind.name() + ' ') || entry.getValue().windowStartMillis() <= sinceMillis) continue;
                failures += entry.getValue().failures();
                oldest = Math.min(oldest, entry.getValue().windowStartMillis());
            }
            return new Total(failures, failures == 0 ? 0 : oldest);
        }

        @Override
        public void purge(long beforeMillis) {
            counts.values().removeIf(count -> count.windowStartMillis() <= beforeMillis);
        }
    }
}
