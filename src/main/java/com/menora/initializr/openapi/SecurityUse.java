package com.menora.initializr.openapi;

/**
 * A security scheme of the document ({@code components.securitySchemes}) as an operation uses it.
 *
 * @param scheme    the scheme's name in the document ({@code petstore_auth})
 * @param javaName  a Java name for it ({@code petstoreAuth})
 * @param kind      how a client proves it
 * @param paramName an API key's header, query or cookie name; null otherwise
 * @param label     a short description for documentation ({@code API key in header X-API-Key})
 */
public record SecurityUse(String scheme, String javaName, Kind kind, String paramName, String label) {

    public enum Kind {
        API_KEY_HEADER, API_KEY_QUERY, API_KEY_COOKIE,
        /** HTTP bearer, OAuth2 and OpenID Connect: an {@code Authorization: Bearer} token. */
        BEARER,
        BASIC,
        /** A scheme a client cannot be generated for (HTTP digest, mutual TLS): documented only. */
        OTHER
    }

    /** Whether the generated client sends it. */
    public boolean sendable() {
        return kind != Kind.OTHER;
    }
}
