package com.menora.initializr.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Issues and checks admin login tokens without keeping any state, so a token issued by one
 * backend instance is accepted by every other instance behind the same load balancer.
 *
 * <p>A token is {@code base64url(payload) + "." + base64url(HMAC-SHA256(key, payload))}, where the
 * payload is {@code <expiry epoch seconds>.<random nonce>.<base64url admin name>}. The key is {@code admin.token-secret}
 * when set, else derived from {@code admin.password} — so replicas that share the password agree
 * with no extra config, and changing the password invalidates every token. Nothing is stored:
 * a token simply stops being valid when it expires ({@code admin.token-ttl}).
 *
 * <p>The admin's name travels in the signed payload, so whoever the token was issued to is who
 * the catalog history says made a change ({@link #userOf}).
 */
@Component
public class AdminTokenStore {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AdminTokenStore(@Value("${admin.password}") String password,
                           @Value("${admin.token-secret:}") String secret,
                           @Value("${admin.token-ttl:12h}") Duration ttl) {
        this(password, secret, ttl, Clock.systemUTC());
    }

    AdminTokenStore(String password, String secret, Duration ttl, Clock clock) {
        this.key = new SecretKeySpec(keyBytes(password, secret), ALGORITHM);
        this.ttl = ttl;
        this.clock = clock;
    }

    /** A token for the shared password's holder, named {@value #PASSWORD_USER}. */
    public String issue() {
        return issue(PASSWORD_USER);
    }

    /** Who signed in with the shared password, when nothing else names them. */
    public static final String PASSWORD_USER = "password";

    public String issue(String user) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        long expiry = clock.instant().plus(ttl).getEpochSecond();
        String name = user == null || user.isBlank() ? PASSWORD_USER : user;
        byte[] payload = (expiry + "." + ENCODER.encodeToString(nonce) + "."
                + ENCODER.encodeToString(name.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    public boolean isValid(String token) {
        return userOf(token).isPresent();
    }

    /** The admin a valid, unexpired token was issued to; empty for any other token. */
    public java.util.Optional<String> userOf(String token) {
        if (token == null) {
            return java.util.Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return java.util.Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(token.substring(0, dot));
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), signature)) {
                return java.util.Optional.empty();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\.");
            long expiry = Long.parseLong(parts[0]);
            if (clock.instant().getEpochSecond() >= expiry) {
                return java.util.Optional.empty();
            }
            // A token from before names were carried is the password's.
            String user = parts.length >= 3 ? new String(DECODER.decode(parts[2]), StandardCharsets.UTF_8) : PASSWORD_USER;
            return java.util.Optional.of(user);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return java.util.Optional.empty();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign admin token", e);
        }
    }

    private static byte[] keyBytes(String password, String secret) {
        if (secret != null && !secret.isBlank()) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("menora-admin-token:" + password).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
