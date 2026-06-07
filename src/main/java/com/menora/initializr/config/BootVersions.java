package com.menora.initializr.config;

/** Single source of truth for stripping the legacy {@code .RELEASE}/{@code -RELEASE}
 *  suffix off a Boot version id, so URL-param and JSON-body request paths agree. */
public final class BootVersions {
    private BootVersions() {}

    public static String normalize(String v) {
        if (v == null) return null;
        return v.replaceAll("[.\\-]RELEASE$", "");
    }
}
