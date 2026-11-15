package com.menora.initializr.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin login: the password check and the limit on wrong passwords. No Spring context — a
 * locked-out address in a shared test context would lock every other test class out with it.
 */
class AdminLoginTest {

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-15T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final AdminLoginThrottle throttle = new AdminLoginThrottle(3, Duration.ofMinutes(5), clock);
    private final AdminAuthController controller = new AdminAuthController("s3cret",
            new AdminTokenStore("s3cret", "", Duration.ofHours(12), clock), throttle);

    private ResponseEntity<?> login(String password, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        // A header the caller sets must not choose whose count a login goes on.
        request.addHeader("X-Forwarded-For", "203.0.113." + (int) (Math.random() * 200));
        Map<String, String> body = new HashMap<>();
        body.put("password", password);
        return controller.login(body, request);
    }

    @Test
    void theRightPasswordGetsATokenAndAWrongOneDoesNot() {
        ResponseEntity<?> ok = login("s3cret", "10.0.0.1");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) ok.getBody()).get("token")).asString().isNotBlank();

        for (String wrong : new String[] {"s3cre", "s3cret ", "S3CRET", "", null}) {
            assertThat(login(wrong, "10.0.0.2").getStatusCode()).as("'" + wrong + "'").isEqualTo(HttpStatus.UNAUTHORIZED);
            throttle.succeeded("10.0.0.2");
        }
    }

    @Test
    void wrongPasswordsFromOneAddressAreRefusedUntilTheWindowHasPassed() {
        for (int i = 0; i < 3; i++) {
            assertThat(login("guess" + i, "10.0.0.7").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The fourth try is not looked at — not even the right password.
        ResponseEntity<?> refused = login("s3cret", "10.0.0.7");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300");
        assertThat(((Map<?, ?>) refused.getBody()).get("error")).asString().contains("Too many wrong passwords").contains("5 minutes");

        // Another address is not affected.
        assertThat(login("s3cret", "10.0.0.8").getStatusCode()).isEqualTo(HttpStatus.OK);

        clock.advance(Duration.ofMinutes(4));
        ResponseEntity<?> still = login("s3cret", "10.0.0.7");
        assertThat(still.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(still.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");

        clock.advance(Duration.ofMinutes(1));
        assertThat(login("s3cret", "10.0.0.7").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aRightPasswordStartsTheCountOver() {
        login("guess", "10.0.0.9");
        login("guess", "10.0.0.9");
        assertThat(login("s3cret", "10.0.0.9").getStatusCode()).isEqualTo(HttpStatus.OK);
        login("guess", "10.0.0.9");
        login("guess", "10.0.0.9");
        assertThat(throttle.retryAfterSeconds("10.0.0.9")).isZero();
        assertThat(login("s3cret", "10.0.0.9").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void failuresSpreadOverMoreThanTheWindowDoNotAddUp() {
        login("guess", "10.0.0.10");
        login("guess", "10.0.0.10");
        clock.advance(Duration.ofMinutes(6));
        login("guess", "10.0.0.10");
        login("guess", "10.0.0.10");
        assertThat(throttle.retryAfterSeconds("10.0.0.10")).isZero();
        assertThat(login("s3cret", "10.0.0.10").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void zeroSwitchesTheLimitOff() {
        AdminLoginThrottle off = new AdminLoginThrottle(0, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 50; i++) off.failed("10.0.0.11");
        assertThat(off.retryAfterSeconds("10.0.0.11")).isZero();
    }
}
