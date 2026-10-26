package com.menora.initializr.admin;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private static AdminTokenStore store(String password, String secret, Instant now) {
        return new AdminTokenStore(password, secret, Duration.ofHours(12), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void tokenIssuedByOneInstanceIsAcceptedByAnotherWithTheSamePassword() {
        String token = store("changeme", "", NOW).issue();

        assertThat(store("changeme", "", NOW).isValid(token)).isTrue();
    }

    @Test
    void tokenSignedWithTheSecretIsAcceptedByAnotherInstanceWithTheSameSecret() {
        String token = store("one", "shared-secret", NOW).issue();

        assertThat(store("two", "shared-secret", NOW).isValid(token)).isTrue();
    }

    @Test
    void differentPasswordOrSecretRejectsTheToken() {
        String token = store("changeme", "", NOW).issue();

        assertThat(store("other", "", NOW).isValid(token)).isFalse();
        assertThat(store("changeme", "some-secret", NOW).isValid(token)).isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = store("changeme", "", NOW).issue();

        assertThat(store("changeme", "", NOW.plus(Duration.ofHours(11))).isValid(token)).isTrue();
        assertThat(store("changeme", "", NOW.plus(Duration.ofHours(12))).isValid(token)).isFalse();
    }

    @Test
    void tamperedOrMalformedTokensAreRejected() {
        AdminTokenStore store = store("changeme", "", NOW);
        String token = store.issue();
        int dot = token.indexOf('.');
        String payload = token.substring(0, dot);
        String signature = token.substring(dot + 1);

        String otherPayload = store.issue().substring(0, dot);
        assertThat(store.isValid(otherPayload + "." + signature)).isFalse();
        assertThat(store.isValid(payload + "." + flip(signature))).isFalse();
        assertThat(store.isValid(payload)).isFalse();
        assertThat(store.isValid(token + ".x")).isFalse();
        assertThat(store.isValid(null)).isFalse();
        assertThat(store.isValid("")).isFalse();
        assertThat(store.isValid("not-a-token")).isFalse();
        assertThat(store.isValid("!!!.???")).isFalse();
        assertThat(store.isValid("6c3e5a0e-3b0f-4c8e-9a43-2f1d0b7e6a11")).isFalse();
    }

    private static String flip(String s) {
        char c = s.charAt(0);
        return (c == 'A' ? 'B' : 'A') + s.substring(1);
    }
}
