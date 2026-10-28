package com.menora.initializr.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code GET /metadata/design/contract}. The generator UI keeps a copy of it
 * ({@code ui/src/components/fullstack/__fixtures__/design-contract.json}) and tests its hand
 * lists (nav icons, widget kinds, page types) against it, so a contract change shows up in both
 * repos. Regenerate both copies with {@code -Ddesign.contract.update=true}.
 */
class DesignContractSnapshotTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/design/contract.snapshot.json");
    private static final Path UI_FIXTURE = Path.of("../ui/src/components/fullstack/__fixtures__/design-contract.json");

    @Test
    void contractMatchesItsSnapshot() throws Exception {
        String actual = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writerWithDefaultPrettyPrinter().writeValueAsString(DesignContract.describe()).replace("\r\n", "\n") + "\n";
        if (Boolean.getBoolean("design.contract.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
            if (Files.isDirectory(UI_FIXTURE.getParent())) Files.writeString(UI_FIXTURE, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(actual).as("the design contract changed — run with -Ddesign.contract.update=true and commit both copies")
                .isEqualTo(Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
}
