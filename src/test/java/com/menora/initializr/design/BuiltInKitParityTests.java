package com.menora.initializr.design;

import com.menora.initializr.FullstackExampleRequests;
import com.menora.initializr.TestInvokerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request naming a built-in kit generates exactly what the same request without a kit
 * generates on the kit's set — every file but {@code menora-model.json}, which records the kit.
 * The kit path writes {@code index.css}, {@code main.tsx} and {@code package.json} itself, so
 * this is what keeps it honest. Line endings are normalized: the set templates take the
 * checkout's, the kit writer writes LF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class BuiltInKitParityTests {

    private static final Map<String, String[]> KITS = Map.of(
            "tailwind-default", new String[] {"react-tailwind-crud", "en"},
            "menora-digital", new String[] {"react-menora-digital-crud", "he"});

    @Autowired
    private TestRestTemplate rest;

    @Test
    void builtInKitsReproduceTheirSets() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> example : FullstackExampleRequests.examples()) {
            for (Map.Entry<String, String[]> kit : KITS.entrySet()) {
                String set = kit.getValue()[0];
                String locale = kit.getValue()[1];
                boolean layout = FullstackExampleRequests.hasPages(example);
                Map<String, byte[]> legacy = generate(FullstackExampleRequests.body(example, set, locale, layout));
                Map<String, Object> withKit = FullstackExampleRequests.body(example, null, locale, layout);
                withKit.put("designKit", kit.getKey());
                Map<String, byte[]> kitted = generate(withKit);

                String name = example.get("exampleId") + " × " + kit.getKey();
                TreeSet<String> paths = new TreeSet<>(legacy.keySet());
                paths.addAll(kitted.keySet());
                for (String path : paths) {
                    if (path.endsWith("/menora-model.json")) {
                        assertThat(new String(kitted.get(path), StandardCharsets.UTF_8)).contains("\"designKit\" : \"" + kit.getKey() + "\"");
                        continue;
                    }
                    if (!legacy.containsKey(path) || !kitted.containsKey(path)) {
                        failures.add(name + ": only " + (legacy.containsKey(path) ? "without" : "with") + " the kit: " + path);
                    } else if (!Arrays.equals(normalize(legacy.get(path)), normalize(kitted.get(path)))) {
                        failures.add(name + ": differs: " + path);
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    private Map<String, byte[]> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> r = FullstackExampleRequests.post(rest, body);
        assertThat(r.getStatusCode()).as(r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        return FullstackExampleRequests.unzip(r.getBody());
    }

    private static byte[] normalize(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return bytes;
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }
}
