package com.menora.initializr.openapi;

import java.util.List;

/**
 * A security scheme of the document ({@code components.securitySchemes}) as an operation uses it.
 *
 * @param scheme    the scheme's name in the document ({@code petstore_auth})
 * @param javaName  a Java name for it ({@code petstoreAuth})
 * @param kind      how a client proves it
 * @param paramName an API key's header, query or cookie name; null otherwise
 * @param label     a short description for documentation ({@code API key in header X-API-Key})
 * @param tokenUrl  an OAuth2 client-credentials flow's token endpoint; null otherwise
 * @param scopes    the scopes that flow declares (the client asks for all of them by default)
 */
public record SecurityUse(String scheme, String javaName, Kind kind, String paramName, String label,
                          String tokenUrl, List<String> scopes) {

    public enum Kind {
        API_KEY_HEADER, API_KEY_QUERY, API_KEY_COOKIE,
        /** HTTP bearer, OpenID Connect, and OAuth2 without a client-credentials flow: a configured {@code Authorization: Bearer} token. */
        BEARER,
        /** OAuth2 with a client-credentials flow: a token the client fetches itself (else a configured one). */
        OAUTH2_CLIENT_CREDENTIALS,
        BASIC,
        /** A scheme a client cannot be generated for (HTTP digest, mutual TLS): documented only. */
        OTHER
    }

    public SecurityUse {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /** A scheme without an OAuth2 flow. */
    public SecurityUse(String scheme, String javaName, Kind kind, String paramName, String label) {
        this(scheme, javaName, kind, paramName, label, null, List.of());
    }

    /** Whether the generated client sends it. */
    public boolean sendable() {
        return kind != Kind.OTHER;
    }
}
