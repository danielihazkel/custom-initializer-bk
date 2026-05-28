package com.menora.initializr.fullstack;

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
