package com.menora.initializr.liveschema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Each database's spelling of {@code CHECK (status IN ('OPEN','CLOSED'))}, read back as a list. */
class CheckClausesTest {

    private static final Function<String, String> COLUMNS = n -> Map.of(
            "status", "status", "STATUS", "STATUS", "kind", "kind", "amount", "amount").get(n);

    private static LiveSchemaModel.CheckIn read(String clause) {
        return CheckClauses.inList(clause, COLUMNS);
    }

    @Test
    void postgresAnyArrayWithCasts() {
        var in = read("CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'CLOSED'::character varying, 'IN PROGRESS'::character varying])::text[])))");
        assertThat(in.column()).isEqualTo("status");
        assertThat(in.values()).containsExactly("OPEN", "CLOSED", "IN PROGRESS");
    }

    @Test
    void postgresAnyArrayOfText() {
        var in = read("CHECK ((kind = ANY (ARRAY['a'::text, 'b'::text])))");
        assertThat(in.values()).containsExactly("a", "b");
    }

    @Test
    void sqlServerOrChainWithBrackets() {
        var in = read("([status]='CLOSED' OR [status]='OPEN' OR [status]=N'ON HOLD')");
        assertThat(in.column()).isEqualTo("status");
        assertThat(in.values()).containsExactly("CLOSED", "OPEN", "ON HOLD");
    }

    @Test
    void oracleAndDb2KeepTheTextAsWritten() {
        assertThat(read("status IN ('OPEN', 'CLOSED')").values()).containsExactly("OPEN", "CLOSED");
        assertThat(read("\"STATUS\" IN('OPEN', 'CLOSED')").column()).isEqualTo("STATUS");
        assertThat(read("(status in ('it''s', 'x'))").values()).containsExactly("it's", "x");
    }

    @Test
    void anythingElseIsNotAList() {
        assertThat(read("\"STATUS\" IS NOT NULL")).isNull();
        assertThat(read("amount > 0")).isNull();
        assertThat(read("status IN ('A', 'B') AND amount > 0")).isNull();
        assertThat(read("status IN ('A', 'B') AND (amount > 0)")).isNull();
        assertThat(read("[status]='A' OR [kind]='B'")).isNull();
        assertThat(read("amount IN (1, 2)")).isNull();
        assertThat(read("unknown IN ('A')")).isNull();
        assertThat(read(null)).isNull();
    }

    @Test
    void splitsOnlyAtTheTopLevel() {
        assertThat(CheckClauses.splitTopLevel("'a,b', 'c'", ",")).containsExactly("'a,b'", " 'c'");
        assertThat(CheckClauses.splitTopLevel("(x OR y) OR z", " OR ")).isEqualTo(List.of("(x OR y)", "z"));
        assertThat(CheckClauses.stripOuterParens("((a) = (b))")).isEqualTo("(a) = (b)");
        assertThat(CheckClauses.stripOuterParens("(a) = (b)")).isEqualTo("(a) = (b)");
    }
}
