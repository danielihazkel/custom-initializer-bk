package com.menora.initializr.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Who is an admin: a member of the LDAP group {@code admin.group}, as Menora's LDAP REST service
 * answers it — {@code GET {admin.ldap-rest.base-url}/employee/findGroupsByCn/{user}}, a JSON list
 * of group names, the same service and the same "a group's name contains it" rule the generated
 * applications' {@code ldap-auth-rest} use. A user's answer is kept for a minute.
 *
 * <p>{@code admin.dev-users} (comma-separated) are admins without the directory being asked — for
 * a developer's machine, where it cannot be reached, and for tests; a warning says so at start.
 * Without a base URL or a group, nobody but those is an admin through single sign-on, and the
 * shared password ({@code admin.password}, unless {@code admin.password-login=false}) stays the
 * only way in.
 */
@Component
public class AdminDirectory {

    private static final Logger log = LoggerFactory.getLogger(AdminDirectory.class);
    private static final long TTL_MILLIS = 60_000;
    private static final int MAX_REMEMBERED = 1_000;

    /** The directory could not be asked: not the same as "not an admin". */
    public static class DirectoryUnavailableException extends RuntimeException {
        public DirectoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Remembered(boolean admin, long until) {}

    private final String baseUrl;
    private final String group;
    private final Set<String> devUsers;
    private final RestClient restClient;
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();

    public AdminDirectory(@Value("${admin.ldap-rest.base-url:}") String baseUrl,
                          @Value("${admin.group:}") String group,
                          @Value("${admin.dev-users:}") String devUsers,
                          RestClient.Builder restClientBuilder) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.group = group == null ? "" : group.trim();
        this.devUsers = Arrays.stream((devUsers == null ? "" : devUsers).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.restClient = this.baseUrl.isEmpty() ? null : restClientBuilder.baseUrl(this.baseUrl).build();
        if (!this.devUsers.isEmpty()) {
            log.warn("admin.dev-users is set: {} are admins without the directory being asked. For development only.",
                    this.devUsers);
        }
    }

    /** Whether single sign-on can make anyone an admin: the directory is configured, or there are dev users. */
    public boolean ssoAvailable() {
        return configured() || !devUsers.isEmpty();
    }

    private boolean configured() {
        return restClient != null && !group.isEmpty();
    }

    /**
     * Whether {@code user} is an admin.
     *
     * @throws DirectoryUnavailableException when the directory is configured but did not answer
     */
    public boolean isAdmin(String user) {
        if (user == null || user.isBlank()) return false;
        String name = user.trim();
        if (devUsers.contains(name)) return true;
        if (!configured()) return false;
        long now = System.currentTimeMillis();
        Remembered known = remembered.get(name);
        if (known != null && known.until() > now) return known.admin();
        List<String> groups;
        try {
            groups = restClient.get().uri("/employee/findGroupsByCn/{username}", name)
                    .retrieve().body(new ParameterizedTypeReference<List<String>>() {});
        } catch (RestClientException e) {
            throw new DirectoryUnavailableException("The LDAP directory could not be asked about " + name, e);
        }
        boolean admin = groups != null && groups.stream().anyMatch(g -> g != null && g.contains(group));
        if (remembered.size() >= MAX_REMEMBERED) remembered.clear();
        remembered.put(name, new Remembered(admin, now + TTL_MILLIS));
        return admin;
    }
}
