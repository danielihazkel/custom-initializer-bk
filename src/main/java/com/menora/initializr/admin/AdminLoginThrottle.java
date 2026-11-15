package com.menora.initializr.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing the admin password: after {@code admin.login.max-failures} wrong passwords
 * from one address within {@code admin.login.window}, that address is refused until the window
 * has passed. A right password clears its count.
 *
 * <p>The admin password is one shared secret and a login is one request, so without this a
 * script could try passwords as fast as the server answers. The count is kept per JVM: with
 * several instances an address gets that many tries on each, which still turns "as fast as the
 * server answers" into a handful of tries every few minutes.
 *
 * <p>The address is the connection's ({@code getRemoteAddr}), never a header the caller can set.
 * Behind a reverse proxy that is the proxy's address unless {@code server.forward-headers-strategy}
 * is configured — every admin then shares one count, and the limit is for all of them together.
 * {@code admin.login.max-failures=0} switches the limit off.
 */
@Component
public class AdminLoginThrottle {

    /** More addresses than this are not tracked at once: expired counts go first, then all. */
    private static final int MAX_TRACKED = 10_000;

    private record Failures(long windowStartMillis, int count) {}

    private final int maxFailures;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Failures> byAddress = new ConcurrentHashMap<>();

    @Autowired
    public AdminLoginThrottle(@Value("${admin.login.max-failures:10}") int maxFailures,
                              @Value("${admin.login.window:5m}") Duration window) {
        this(maxFailures, window, Clock.systemUTC());
    }

    AdminLoginThrottle(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /** Seconds until {@code address} may try again; 0 when it may try now. */
    public long retryAfterSeconds(String address) {
        if (maxFailures <= 0) return 0;
        Failures failures = byAddress.get(address);
        if (failures == null) return 0;
        long remaining = failures.windowStartMillis() + windowMillis - clock.millis();
        if (remaining <= 0) {
            byAddress.remove(address, failures);
            return 0;
        }
        return failures.count() >= maxFailures ? (remaining + 999) / 1000 : 0;
    }

    /** A wrong password from {@code address}. */
    public void failed(String address) {
        if (maxFailures <= 0) return;
        long now = clock.millis();
        if (byAddress.size() >= MAX_TRACKED) {
            byAddress.values().removeIf(f -> f.windowStartMillis() + windowMillis <= now);
            if (byAddress.size() >= MAX_TRACKED) byAddress.clear();
        }
        byAddress.merge(address, new Failures(now, 1), (old, first) ->
                old.windowStartMillis() + windowMillis <= now ? first : new Failures(old.windowStartMillis(), old.count() + 1));
    }

    /** The right password from {@code address}: its count starts over. */
    public void succeeded(String address) {
        byAddress.remove(address);
    }
}
