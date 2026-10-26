package com.menora.initializr.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
            return ResponseEntity.ok(Map.of("token", tokenStore.issue()));
        }
        return ResponseEntity.status(401).body(Map.of("error", "Invalid password"));
    }

    // Tokens are stateless (see AdminTokenStore): the client drops its copy and the token
    // expires on its own. A server-side revocation would only reach one replica.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
