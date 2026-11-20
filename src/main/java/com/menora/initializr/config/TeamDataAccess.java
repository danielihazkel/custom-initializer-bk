package com.menora.initializr.config;

import com.menora.initializr.admin.AdminTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Who may change what the team shares on the generator site — team models, team presets and
 * team templates.
 *
 * <p>Saving one is open to every engineer, as reading is: it is an internal tool, and a login in
 * front of "save for the team" would defeat it. Changing or deleting one was open too, so anyone
 * could overwrite or remove a colleague's work without a trace. Now a row is changed or deleted by
 * <ul>
 *   <li>whoever created it — the user the single sign-on gateway names, compared ignoring case;</li>
 *   <li>a signed-in admin — the request carries an admin token ({@link AdminTokenStore}; tokens
 *       are stateless, so one is as good here as under {@code /admin});</li>
 *   <li>anyone, when the row has no recorded creator (saved before users were recorded, or
 *       through a deployment without the gateway).</li>
 * </ul>
 * Anyone else is answered 403 naming the creator. Each answer says whether the caller may edit
 * the row ({@code canEdit}), so the screens need no "who am I" call, and each change records who
 * made it ({@code updatedBy}).
 *
 * <p>The user comes from the header {@code menora.audit.user-header} names. Without the gateway
 * in front, that header is whatever a caller sends — here as everywhere else on the site.
 */
@Component
public class TeamDataAccess {

    /**
     * Who is asking. {@code user}: the gateway's user, null when the request names none.
     * {@code admin}: the name an admin token was issued to, null without a valid token.
     */
    public record Caller(String user, String admin) {

        public boolean isAdmin() {
            return admin != null;
        }

        /** The name a change by this caller is recorded under; null when nothing says who it is. */
        public String name() {
            if (user != null) return user;
            if (admin == null) return null;
            return AdminTokenStore.PASSWORD_USER.equals(admin) ? "admin" : admin;
        }
    }

    /** The caller is neither the row's creator nor an admin. */
    public static final class NotAllowedException extends RuntimeException {
        public NotAllowedException(String createdBy) {
            super("Saved by " + createdBy + " — only they or a signed-in admin can change or delete it");
        }
    }

    private final AdminTokenStore tokens;
    private final String userHeader;

    public TeamDataAccess(AdminTokenStore tokens,
                          @Value("${menora.audit.user-header:userinfo}") String userHeader) {
        this.tokens = tokens;
        this.userHeader = userHeader;
    }

    public Caller callerOf(HttpServletRequest request) {
        String user = request.getHeader(userHeader);
        user = user == null || user.isBlank() ? null : user.trim();
        if (user != null && user.length() > 255) user = user.substring(0, 255);
        String authorization = request.getHeader("Authorization");
        String admin = authorization != null && authorization.startsWith("Bearer ")
                ? tokens.userOf(authorization.substring("Bearer ".length()).trim()).orElse(null) : null;
        return new Caller(user, admin);
    }

    /** Whether {@code caller} may change or delete a row {@code createdBy} saved. */
    public boolean canEdit(String createdBy, Caller caller) {
        if (createdBy == null || createdBy.isBlank()) return true;
        if (caller.isAdmin()) return true;
        return caller.user() != null && caller.user().equalsIgnoreCase(createdBy.trim());
    }

    /** @throws NotAllowedException when {@link #canEdit} says no */
    public void requireEdit(String createdBy, Caller caller) {
        if (!canEdit(createdBy, caller)) throw new NotAllowedException(createdBy.trim());
    }

    /** The 403 of a {@link NotAllowedException}, in the {@code {error, detail}} shape of the rest. */
    public static ResponseEntity<Map<String, String>> refusal(NotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Not yours to change", "detail", ex.getMessage()));
    }
}
