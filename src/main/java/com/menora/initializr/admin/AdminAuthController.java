package com.menora.initializr.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    /** The password {@code application.yml} ships with. */
    static final String DEFAULT_PASSWORD = "changeme";

    private final byte[] configuredPasswordDigest;
    private final AdminTokenStore tokenStore;
    private final AdminLoginThrottle throttle;

    public AdminAuthController(@Value("${admin.password}") String configuredPassword,
                               AdminTokenStore tokenStore,
                               AdminLoginThrottle throttle) {
        this.configuredPasswordDigest = digest(configuredPassword);
        this.tokenStore = tokenStore;
        this.throttle = throttle;
        if (DEFAULT_PASSWORD.equals(configuredPassword)) {
            log.warn("admin.password is still the default '{}': anyone who can reach /admin can edit the catalog. "
                    + "Set ADMIN_PASSWORD (or admin.password) for this environment.", DEFAULT_PASSWORD);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String address = request.getRemoteAddr();
        long wait = throttle.retryAfterSeconds(address);
        if (wait > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(wait))
                    .body(Map.of("error", "Too many wrong passwords. Try again in " + minutes(wait) + "."));
        }
        String password = body.get("password");
        // Compared as digests in constant time: how long a wrong password takes to refuse says
        // nothing about how much of it was right.
        if (password != null && MessageDigest.isEqual(digest(password), configuredPasswordDigest)) {
            throttle.succeeded(address);
            return ResponseEntity.ok(Map.of("token", tokenStore.issue()));
        }
        throttle.failed(address);
        return ResponseEntity.status(401).body(Map.of("error", "Invalid password"));
    }

    // Tokens are stateless (see AdminTokenStore): the client drops its copy and the token
    // expires on its own. A server-side revocation would only reach one replica.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    private static String minutes(long seconds) {
        long minutes = (seconds + 59) / 60;
        return minutes <= 1 ? "a minute" : minutes + " minutes";
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
