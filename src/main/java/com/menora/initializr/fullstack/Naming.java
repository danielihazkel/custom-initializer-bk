package com.menora.initializr.fullstack;

import java.util.Locale;

/**
 * String case conversion + naive English pluralization used by the fullstack
 * scaffolding. Pluralization is best-effort — users can override via {@code tableName}.
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

    /** {@code OrderItem} → {@code order_item}. {@code orderItem} → {@code order_item}. */
    public static String toSnakeCase(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == ' ') {
                sb.append('_');
            } else if (Character.isUpperCase(c)) {
                if (i > 0 && sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@code OrderItem} → {@code order-item}. {@code order_item} → {@code order-item}. */
    public static String toKebabCase(String s) {
        return toSnakeCase(s).replace('_', '-');
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
