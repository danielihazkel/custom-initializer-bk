package com.menora.initializr.sql;

import com.menora.initializr.gen.Naming;

import static com.menora.initializr.sql.SqlEntityGenerator.*;
import static com.menora.initializr.sql.SqlSelectReader.*;
import static com.menora.initializr.sql.SqlScriptParser.*;
import static com.menora.initializr.sql.SqlEntityRenderer.*;
import static com.menora.initializr.sql.SqlRestRenderer.*;

/** Java names from SQL names, and two type checks. Part of {@link SqlEntityGenerator}. */
final class SqlJavaNames {

    private SqlJavaNames() {}

    static String toPascalCase(String snake) {
        String pascal = Naming.toPascalCase(snake);
        return pascal.isEmpty() ? "Entity" : pascal;
    }

    static String toCamelCase(String snake) {
        return Naming.decapitalize(toPascalCase(snake));
    }

    static String capitalize(String s) {
        return Naming.capitalize(s);
    }

    static String decapitalize(String s) {
        return Naming.decapitalize(s);
    }

    /** Lower-kebab a raw table name for use as a REST collection path segment
     *  ({@code TD_APP_STP} → {@code td-app-stp}, {@code line_items} → {@code line-items}). */
    static String toKebab(String raw) {
        String kebab = Naming.toKebabCase(raw);
        return kebab.isEmpty() ? "items" : kebab;
    }

    static boolean isStringType(JavaType jt) {
        return "String".equals(jt.simpleName());
    }

    static boolean isDecimalType(JavaType jt) {
        return "BigDecimal".equals(jt.simpleName());
    }
}
