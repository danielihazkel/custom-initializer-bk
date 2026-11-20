-- V40: refused admin sign-ins, counted in the database.
--
-- The limit on wrong admin passwords was kept per JVM: with several instances behind a load
-- balancer an address got its tries on each of them, and nothing limited guesses spread over
-- many addresses. The counts are rows now, so every instance sees the same ones.
-- See admin/AdminLoginThrottle.
--
-- kind          PASSWORD (the shared password) or SSO (signing in as oneself)
-- address       the connection's address
-- window_start  when this address's current window began, epoch milliseconds
-- failures      refused sign-ins in that window
--
-- A row is gone with the first sign-in that succeeds from its address, and rows whose window
-- has passed are deleted as new failures are recorded.

CREATE TABLE initializer_admin_login_failure (
    kind         VARCHAR(10)  NOT NULL,
    address      VARCHAR(100) NOT NULL,
    window_start BIGINT       NOT NULL,
    failures     INT          NOT NULL,
    CONSTRAINT pk_admin_login_failure PRIMARY KEY (kind, address)
);

CREATE INDEX idx_admin_login_failure_window ON initializer_admin_login_failure (window_start);
