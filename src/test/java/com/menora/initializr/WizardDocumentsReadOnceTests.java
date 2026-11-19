package com.menora.initializr;

import com.menora.initializr.gen.WizardMemo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wizard request reads and generates each document once ({@link WizardMemo}): the check that
 * makes a bad document a 400 generates it with the project's own inputs, and the contributors and
 * build customizers reuse what it made. It used to be read two or three times per request.
 */
@IntegrationTest
class WizardDocumentsReadOnceTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void everyWizardDocumentIsReadAndGeneratedOnce() throws Exception {
        Map<String, Object> body = base();
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "web-services", "validation", "kafka", "flyway"));
        body.put("sqlByDep", Map.of("postgresql", WizardGoldenOutputTests.fixture("library-postgresql.sql")));
        body.put("sqlOptions", Map.of("postgresql", Map.of("apiMode", "INLINE_DTO")));
        body.put("specByDep", Map.of("web", WizardGoldenOutputTests.fixture("petstore.yaml")));
        body.put("openApiOptions", Map.of("web", Map.of("mode", "BOTH")));
        body.put("wsdlByDep", Map.of("web-services", WizardGoldenOutputTests.fixture("countries.wsdl")));
        body.put("soapOptions", Map.of("web-services", Map.of("mode", "BOTH")));
        // Avro: the build customizer asks the document for its schema files too.
        body.put("asyncApiByDep", Map.of("kafka", WizardGoldenOutputTests.fixture("orders-avro-asyncapi.yaml")));

        ResponseEntity<byte[]> response = restTemplate.postForEntity("/starter-wizard.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(counts(WizardMemo.lastComputed())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "sql", 1L,
                "openapi", 1L,
                "soap", 1L,
                "soap-parse", 1L,
                "events", 1L,
                "asyncapi-parse", 1L));
    }

    /** Outside a request nothing is kept: a direct caller (a test, a detect endpoint) computes each time. */
    @Test
    void outsideARequestNothingIsKept() {
        WizardMemo.Key key = WizardMemo.Key.of("test", "x");
        int[] calls = {0};
        WizardMemo.get(key, () -> ++calls[0]);
        WizardMemo.get(key, () -> ++calls[0]);
        assertThat(calls[0]).isEqualTo(2);

        WizardMemo.open();
        try {
            WizardMemo.get(key, () -> ++calls[0]);
            WizardMemo.get(key, () -> ++calls[0]);
        } finally {
            WizardMemo.close();
        }
        assertThat(calls[0]).isEqualTo(3);
        assertThat(WizardMemo.isOpen()).isFalse();
    }

    private static Map<String, Long> counts(List<String> kinds) {
        return kinds.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static Map<String, Object> base() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "once");
        body.put("packageName", "com.menora.once");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        return body;
    }
}
