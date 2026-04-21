package com.menora.initializr.admin;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);
    private static final String DEFAULT_PASSWORD = "changeme";

    private final String configuredPassword;
    private final AdminTokenStore tokenStore;

    public AdminAuthController(@Value("${admin.password}") String configuredPassword,
                               AdminTokenStore tokenStore) {
        this.configuredPassword = configuredPassword;
        this.tokenStore = tokenStore;
    }

    @PostConstruct
    void warnOnDefaultPassword() {
        if (configuredPassword == null || configuredPassword.isBlank() || DEFAULT_PASSWORD.equals(configuredPassword)) {
            log.warn("SECURITY: admin.password is unset or still the default. Set the ADMIN_PASSWORD env var "
                    + "(or admin.password in application.yml) before exposing this service.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password != null && passwordMatches(password)) {
            String token = UUID.randomUUID().toString();
            tokenStore.add(token);
            return ResponseEntity.ok(Map.of("token", token));
        }
        return ResponseEntity.status(401).body(Map.of("error", "Invalid password"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            tokenStore.remove(authHeader.substring(7));
        }
        return ResponseEntity.noContent().build();
    }

    private boolean passwordMatches(String submitted) {
        if (configuredPassword == null) return false;
        return MessageDigest.isEqual(
                submitted.getBytes(StandardCharsets.UTF_8),
                configuredPassword.getBytes(StandardCharsets.UTF_8));
    }
}
