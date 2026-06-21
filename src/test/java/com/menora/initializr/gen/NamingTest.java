package com.menora.initializr.gen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamingTest {

    @Test
    void toPascalCase_handlesVariousInputs() {
        assertThat(Naming.toPascalCase("user")).isEqualTo("User");
        assertThat(Naming.toPascalCase("orderItem")).isEqualTo("OrderItem");
        assertThat(Naming.toPascalCase("OrderItem")).isEqualTo("OrderItem");
        assertThat(Naming.toPascalCase("order_item")).isEqualTo("OrderItem");
        assertThat(Naming.toPascalCase("order-item")).isEqualTo("OrderItem");
        assertThat(Naming.toPascalCase("")).isEqualTo("");
    }

    @Test
    void toCamelCase_handlesVariousInputs() {
        assertThat(Naming.toCamelCase("User")).isEqualTo("user");
        assertThat(Naming.toCamelCase("OrderItem")).isEqualTo("orderItem");
        assertThat(Naming.toCamelCase("order_item")).isEqualTo("orderItem");
    }

    @Test
    void toSnakeCase_handlesVariousInputs() {
        assertThat(Naming.toSnakeCase("User")).isEqualTo("user");
        assertThat(Naming.toSnakeCase("OrderItem")).isEqualTo("order_item");
        assertThat(Naming.toSnakeCase("orderItem")).isEqualTo("order_item");
        assertThat(Naming.toSnakeCase("order-item")).isEqualTo("order_item");
    }

    @Test
    void toKebabCase_handlesVariousInputs() {
        assertThat(Naming.toKebabCase("OrderItem")).isEqualTo("order-item");
        assertThat(Naming.toKebabCase("orderItem")).isEqualTo("order-item");
    }

    /** All-caps SQL identifiers (common in DB2-for-i DDL) must stay intact —
     *  separators and uppercase runs collapse to a single boundary. */
    @Test
    void snakeAndKebab_preserveAllCapsSqlIdentifiers() {
        assertThat(Naming.toSnakeCase("TD_APP_STP")).isEqualTo("td_app_stp");
        assertThat(Naming.toKebabCase("TD_APP_STP")).isEqualTo("td-app-stp");
        assertThat(Naming.toKebabCase("line_items")).isEqualTo("line-items");
        assertThat(Naming.toKebabCase("CUSTOMER")).isEqualTo("customer");
    }

    @Test
    void capitalize_decapitalize() {
        assertThat(Naming.capitalize("order")).isEqualTo("Order");
        assertThat(Naming.decapitalize("Order")).isEqualTo("order");
        assertThat(Naming.capitalize("")).isEqualTo("");
        assertThat(Naming.decapitalize("")).isEqualTo("");
    }

    @Test
    void pluralize_handlesCommonEndings() {
        assertThat(Naming.pluralize("user")).isEqualTo("users");
        assertThat(Naming.pluralize("box")).isEqualTo("boxes");
        assertThat(Naming.pluralize("bus")).isEqualTo("buses");
        assertThat(Naming.pluralize("city")).isEqualTo("cities");
        assertThat(Naming.pluralize("category")).isEqualTo("categories");
        assertThat(Naming.pluralize("day")).isEqualTo("days");
        assertThat(Naming.pluralize("status")).isEqualTo("statuses");
    }

    @Test
    void pluralize_leavesAlreadyPluralUnchanged() {
        assertThat(Naming.pluralize("users")).isEqualTo("users");
        assertThat(Naming.pluralize("cities")).isEqualTo("cities");
    }
}
