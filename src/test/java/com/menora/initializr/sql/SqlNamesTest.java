package com.menora.initializr.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNamesTest {

    @Test
    void unquotedNamesTheNamingStrategyWouldSplitAreFoldedOrQuoted() {
        // DB2/Oracle/H2 fold unquoted names to upper case; PostgreSQL to lower case, which the
        // strategy's own lower-casing reproduces — upper case suits both.
        assertThat(SqlNames.physical("orderDate", false, SqlDialect.DB2)).isEqualTo("ORDERDATE");
        assertThat(SqlNames.physical("orderDate", false, SqlDialect.POSTGRESQL)).isEqualTo("ORDERDATE");
        assertThat(SqlNames.physical("orderDate", false, null)).isEqualTo("ORDERDATE");
        // SQL Server keeps names as written; MySQL table names are case-sensitive on Linux.
        assertThat(SqlNames.physical("orderDate", false, SqlDialect.MSSQL)).isEqualTo("[orderDate]");
        assertThat(SqlNames.physical("orderDate", false, SqlDialect.MYSQL)).isEqualTo("`orderDate`");
        assertThat(SqlNames.physical("Orders", false, SqlDialect.MYSQL)).isEqualTo("`Orders`");
    }

    @Test
    void namesTheStrategyLeavesAloneAreUnchanged() {
        assertThat(SqlNames.physical("FRF_QOD_MISHLOAH_FUTURE_USE_8", false, SqlDialect.DB2))
                .isEqualTo("FRF_QOD_MISHLOAH_FUTURE_USE_8");
        assertThat(SqlNames.physical("order_date", false, SqlDialect.POSTGRESQL)).isEqualTo("order_date");
        assertThat(SqlNames.physical("ORDERS", false, SqlDialect.MSSQL)).isEqualTo("ORDERS");
        assertThat(SqlNames.physical("Status", false, SqlDialect.DB2)).isEqualTo("Status");
    }

    @Test
    void quotedNamesKeepTheirCaseUnlessTheyAreWhatTheDatabaseStoresAnyway() {
        assertThat(SqlNames.physical("OrderDate", true, SqlDialect.POSTGRESQL)).isEqualTo("\"OrderDate\"");
        assertThat(SqlNames.physical("ORD_NO", true, SqlDialect.POSTGRESQL)).isEqualTo("\"ORD_NO\"");
        assertThat(SqlNames.physical("cust_no", true, SqlDialect.POSTGRESQL)).isEqualTo("cust_no");
        // db2look quotes every name; upper-case ones are plain DB2 names.
        assertThat(SqlNames.physical("ORD_NO", true, SqlDialect.DB2)).isEqualTo("ORD_NO");
        assertThat(SqlNames.physical("ordNo", true, SqlDialect.DB2)).isEqualTo("\"ordNo\"");
        assertThat(SqlNames.physical("Order Date", true, SqlDialect.MSSQL)).isEqualTo("[Order Date]");
        assertThat(SqlNames.physical("OrderDate", true, SqlDialect.MYSQL)).isEqualTo("`OrderDate`");
    }

    @Test
    void storedNamesAreIdempotent() {
        assertThat(SqlNames.physical("\"OrderDate\"", null)).isEqualTo("\"OrderDate\"");
        assertThat(SqlNames.physical("ORDERDATE", null)).isEqualTo("ORDERDATE");
        assertThat(SqlNames.physical("orderDate", null)).isEqualTo("ORDERDATE");
        assertThat(SqlNames.javaLiteral("\"OrderDate\"")).isEqualTo("\\\"OrderDate\\\"");
    }
}
