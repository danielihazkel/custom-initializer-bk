package com.menora.initializr.gen;

import java.util.Locale;

/**
 * String case conversion + naive English pluralization shared by the entity
 * generators — the standalone SQL wizard ({@code com.menora.initializr.sql}) and
 * the fullstack scaffolder ({@code com.menora.initializr.fullstack}). Pluralization
 * is best-effort — users can override via {@code tableName}.
 */
public final class Naming {

    private Naming() {}

    /** {@code orderItem} or {@code OrderItem} → {@code OrderItem}. {@code order-item} → {@code OrderItem}. */
    public static String toPascalCase(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_' || c == ' ') {
                upper = true;
            } else if (Character.isUpperCase(c) && i > 0
                    && (Character.isLowerCase(s.charAt(i - 1)) || Character.isDigit(s.charAt(i - 1)))) {
                // CamelHumps: keep an uppercase that follows lowercase
                sb.append(c);
                upper = false;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** {@code OrderItem} → {@code orderItem}. {@code order_item} → {@code orderItem}. */
    public static String toCamelCase(String s) {
        String pascal = toPascalCase(s);
        if (pascal.isEmpty()) return "";
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /**
     * {@code OrderItem} → {@code order_item}. {@code orderItem} → {@code order_item}.
     * Explicit separators ({@code _}/{@code -}/space) and runs of uppercase letters
     * collapse to a single boundary, so all-caps SQL identifiers survive intact:
     * {@code TD_APP_STP} → {@code td_app_stp} (not {@code t_d_a_p_p_s_t_p}). A boundary
     * is only inserted at a camel hump — an uppercase letter following a lowercase
     * letter or digit.
     */
    public static String toSnakeCase(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == ' ' || c == '_') {
                sb.append('_');
            } else if (Character.isUpperCase(c)) {
                char prev = i > 0 ? s.charAt(i - 1) : '\0';
                if (i > 0 && (Character.isLowerCase(prev) || Character.isDigit(prev))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@code OrderItem} → {@code order-item}. {@code TD_APP_STP} → {@code td-app-stp}. */
    public static String toKebabCase(String s) {
        return toSnakeCase(s).replace('_', '-');
    }

    /** Upper-case the first character only; the rest is left untouched. */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Lower-case the first character only; the rest is left untouched. */
    public static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Best-effort English pluralization for table names. Handles common -y→-ies,
     * -s/-x/-z/-ch/-sh→-es, and the default +s. Already-plural words (heuristic:
     * already ending in s and not in ss) are returned unchanged.
     */
    public static String pluralize(String s) {
        if (s == null || s.isEmpty()) return s;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ies")) return s;
        if (lower.endsWith("s") && !lower.endsWith("ss") && !lower.endsWith("us")) return s;
        if (lower.endsWith("y") && s.length() > 1 && !isVowel(lower.charAt(lower.length() - 2))) {
            return s.substring(0, s.length() - 1) + "ies";
        }
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z")
                || lower.endsWith("ch") || lower.endsWith("sh")) {
            return s + "es";
        }
        return s + "s";
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }
}
