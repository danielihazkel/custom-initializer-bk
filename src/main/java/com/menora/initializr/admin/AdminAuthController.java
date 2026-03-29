package com.menora.initializr.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private final String configuredPassword;
    private final AdminTokenStore tokenStore;

    public AdminAuthController(@Value("${admin.password}") String configuredPassword,
                               AdminTokenStore tokenStore) {
        this.configuredPassword = configuredPassword;
        this.tokenStore = tokenStore;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password != null && password.equals(configuredPassword)) {
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
}
