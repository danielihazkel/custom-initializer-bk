package com.menora.initializr.admin;

import com.menora.initializr.admin.AdminLoginThrottle.Kind;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin sign-in limit counted in the database ({@link AdminLoginThrottle.JdbcStore}): the
 * table the migration makes, and two instances — two throttles over the one database — seeing
 * each other's counts. A database of its own, no Spring context: the counts of a shared one
 * would be every other test class's too.
 */
class AdminLoginThrottleStoreTest {

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T08:00:00Z");

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
    private JdbcTemplate jdbc;
    private AdminLoginThrottle one;
    private AdminLoginThrottle other;

    @BeforeEach
    void aDatabaseWithTheMigrationsTable() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:throttle-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V40__admin_login_failure.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        one = new AdminLoginThrottle(3, 6, Duration.ofMinutes(5), clock, new AdminLoginThrottle.JdbcStore(jdbc));
        other = new AdminLoginThrottle(3, 6, Duration.ofMinutes(5), clock, new AdminLoginThrottle.JdbcStore(jdbc));
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM initializer_admin_login_failure", Integer.class);
    }

    @Test
    void twoInstancesCountOneAddressTogether() {
        // Behind a load balancer: two wrong passwords land on one instance, the third on the other.
        one.failed("10.0.0.7");
        one.failed("10.0.0.7");
        assertThat(other.retryAfterSeconds("10.0.0.7")).isZero();
        other.failed("10.0.0.7");

        // Kept per instance, that address would have had three tries on each.
        assertThat(one.retryAfterSeconds("10.0.0.7")).isEqualTo(300);
        assertThat(other.retryAfterSeconds("10.0.0.7")).isEqualTo(300);
        assertThat(other.retryAfterSeconds("10.0.0.8")).isZero();
        assertThat(rows()).isEqualTo(1);

        // The right password on either clears it for both.
        other.succeeded("10.0.0.7");
        assertThat(one.retryAfterSeconds("10.0.0.7")).isZero();
        assertThat(rows()).isZero();
    }

    @Test
    void aWindowThatHasPassedStartsAgainAndIsForgotten() {
        for (int i = 0; i < 3; i++) one.failed("10.0.0.7");
        one.failed("10.0.0.9");
        clock.advance(Duration.ofMinutes(4));
        assertThat(other.retryAfterSeconds("10.0.0.7")).isEqualTo(60);

        clock.advance(Duration.ofMinutes(1));
        assertThat(other.retryAfterSeconds("10.0.0.7")).isZero();
        // The next failure anywhere forgets the windows that have passed, and starts this one's.
        other.failed("10.0.0.7");
        assertThat(rows()).isEqualTo(1);
        assertThat(one.retryAfterSeconds("10.0.0.7")).isZero();
        other.failed("10.0.0.7");
        other.failed("10.0.0.7");
        assertThat(one.retryAfterSeconds("10.0.0.7")).isEqualTo(300);
    }

    @Test
    void theCeilingOverEveryAddressIsSharedAndIsThePasswordsAlone() {
        // Two each from three addresses, over both instances: six wrong passwords in all.
        for (int address = 1; address <= 3; address++) {
            one.failed("10.1.0." + address);
            other.failed("10.1.0." + address);
        }
        assertThat(one.retryAfterSeconds("10.1.0.99")).isEqualTo(300);
        assertThat(other.retryAfterSeconds("10.1.0.99")).isEqualTo(300);
        // Signing in as oneself is counted apart, per address only.
        assertThat(other.retryAfterSeconds(Kind.SSO, "10.1.0.99")).isZero();
        for (int i = 0; i < 6; i++) one.failed(Kind.SSO, "10.2.0." + i);
        assertThat(other.retryAfterSeconds(Kind.SSO, "10.2.0.50")).isZero();
        for (int i = 0; i < 2; i++) other.failed(Kind.SSO, "10.2.0.0");
        assertThat(one.retryAfterSeconds(Kind.SSO, "10.2.0.0")).isEqualTo(300);
        // …and one address has both counts, each its own row.
        one.failed(Kind.PASSWORD, "10.2.0.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM initializer_admin_login_failure WHERE address = '10.2.0.0'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void anAddressLongerThanTheColumnIsStillCounted() {
        String long1 = "x".repeat(300);
        for (int i = 0; i < 3; i++) one.failed(long1);
        assertThat(other.retryAfterSeconds(long1)).isEqualTo(300);
        one.failed((String) null);
        assertThat(other.retryAfterSeconds((String) null)).isZero();
    }

    @Test
    void whenTheCountsCannotBeKeptTheSignInGoesOnUnlimited() {
        for (int i = 0; i < 3; i++) one.failed("10.0.0.7");
        jdbc.execute("DROP TABLE initializer_admin_login_failure");

        // A brake, not a lock: no table must not mean no way in.
        assertThat(one.retryAfterSeconds("10.0.0.7")).isZero();
        one.failed("10.0.0.7");
        one.succeeded("10.0.0.7");
    }
}
