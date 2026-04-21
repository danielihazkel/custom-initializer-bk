package com.menora.initializr.admin;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AdminTokenStore {

    private static final Duration TTL = Duration.ofHours(8);

    private final ConcurrentMap<String, Instant> tokens = new ConcurrentHashMap<>();

    public void add(String token) {
        sweep();
        tokens.put(token, Instant.now().plus(TTL));
    }

    public void remove(String token) {
        tokens.remove(token);
    }

    public boolean isValid(String token) {
        if (token == null) return false;
        Instant expiresAt = tokens.get(token);
        if (expiresAt == null) return false;
        if (Instant.now().isAfter(expiresAt)) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    private void sweep() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> now.isAfter(e.getValue()));
    }
}
