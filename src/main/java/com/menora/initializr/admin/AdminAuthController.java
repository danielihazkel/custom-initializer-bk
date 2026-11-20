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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signing in to the admin screens. An admin is named: through single sign-on the SSO gateway's
 * user header says who is asking and {@link AdminDirectory} whether they are an admin; the shared
 * password ({@code admin.password}) stays for an environment without the directory, and its
 * holder is "password" — or "&lt;user&gt; (password)" when the header names them. The name goes
 * into the token, and from there into the catalog history.
 */
@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    /** The password {@code application.yml} ships with. */
    static final String DEFAULT_PASSWORD = "changeme";

    private final byte[] configuredPasswordDigest;
    private final AdminTokenStore tokenStore;
    private final AdminLoginThrottle throttle;
    private final AdminDirectory directory;
    private final boolean passwordLogin;
    private final String userHeader;

    public AdminAuthController(@Value("${admin.password}") String configuredPassword,
                               @Value("${admin.password-login:true}") boolean passwordLogin,
                               @Value("${menora.audit.user-header:userinfo}") String userHeader,
                               AdminTokenStore tokenStore,
                               AdminLoginThrottle throttle,
                               AdminDirectory directory) {
        this.configuredPasswordDigest = digest(configuredPassword);
        this.tokenStore = tokenStore;
        this.throttle = throttle;
        this.directory = directory;
        this.passwordLogin = passwordLogin;
        this.userHeader = userHeader;
        if (passwordLogin && DEFAULT_PASSWORD.equals(configuredPassword)) {
            log.warn("admin.password is still the default '{}': anyone who can reach /admin can edit the catalog. "
                    + "Set ADMIN_PASSWORD (or admin.password) for this environment.", DEFAULT_PASSWORD);
        }
    }

    /** What the login screen may offer, and who the gateway says is asking. Open to anyone. */
    @GetMapping("/login/options")
    public Map<String, Object> options(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", passwordLogin);
        body.put("sso", directory.ssoAvailable());
        body.put("user", userOf(request));
        return body;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!passwordLogin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Signing in with the password is turned off here: sign in with your own account."));
        }
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
            String user = userOf(request);
            String name = user == null ? AdminTokenStore.PASSWORD_USER : user + " (password)";
            return ResponseEntity.ok(Map.of("token", tokenStore.issue(name), "user", name));
        }
        throttle.failed(address);
        return ResponseEntity.status(401).body(Map.of("error", "Invalid password"));
    }

    /** Single sign-on: the gateway's user header names the user, the directory says whether they are an admin. */
    @PostMapping("/login/sso")
    public ResponseEntity<?> loginSso(HttpServletRequest request) {
        // Refused sign-ins are counted here too: each one asks the directory, and a caller who is
        // not an admin has no business asking it again and again.
        String address = request.getRemoteAddr();
        long wait = throttle.retryAfterSeconds(AdminLoginThrottle.Kind.SSO, address);
        if (wait > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(wait))
                    .body(Map.of("error", "Too many sign-ins that were refused. Try again in " + minutes(wait) + "."));
        }
        String user = userOf(request);
        if (user == null) {
            throttle.failed(AdminLoginThrottle.Kind.SSO, address);
            return ResponseEntity.status(401).body(Map.of("error",
                    "No signed-in user: the request did not come through the single sign-on gateway."));
        }
        boolean admin;
        try {
            admin = directory.isAdmin(user);
        } catch (AdminDirectory.DirectoryUnavailableException e) {
            log.warn("Admin sign-in of {}: {}", user, e.getCause() == null ? e.getMessage() : e.getCause().toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",
                    "The directory that says who is an admin did not answer. Try again, or sign in with the password."));
        }
        if (!admin) {
            throttle.failed(AdminLoginThrottle.Kind.SSO, address);
            log.info("Admin sign-in refused: {} is not in the admin group", user);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error",
                    user + " is not an admin of this service.", "user", user));
        }
        throttle.succeeded(AdminLoginThrottle.Kind.SSO, address);
        return ResponseEntity.ok(Map.of("token", tokenStore.issue(user), "user", user));
    }

    /** Who the token in the request was issued to. */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(name = AdminAuthFilter.USER_ATTRIBUTE, required = false) String user) {
        return Map.of("user", user == null ? AdminTokenStore.PASSWORD_USER : user);
    }

    // Tokens are stateless (see AdminTokenStore): the client drops its copy and the token
    // expires on its own. A server-side revocation would only reach one replica.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    private String userOf(HttpServletRequest request) {
        String value = request.getHeader(userHeader);
        if (value == null || value.isBlank()) return null;
        String user = value.trim();
        return user.length() > 200 ? user.substring(0, 200) : user;
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
