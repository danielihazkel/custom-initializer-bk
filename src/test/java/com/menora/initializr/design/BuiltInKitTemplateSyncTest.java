package com.menora.initializr.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped frontend set's {@code index.css.mustache} and {@code main.tsx} ARE its built-in
 * kit, written by {@link KitCssWriter} (palette as mustache tags) and {@link KitEntryWriter}. So a
 * request without a kit and one naming the built-in kit generate the same bytes — and a token
 * edit to a built-in kit's {@code kit.json} must be carried into its set's template.
 *
 * <p>Regenerate the templates from the kits with {@code -Ddesign.sync=true}.
 */
class BuiltInKitTemplateSyncTest {

    private static final boolean SYNC = Boolean.getBoolean("design.sync");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void setTemplatesAreTheirBuiltInKits() throws IOException {
        java.util.Set<String> sets = new java.util.HashSet<>();
        for (String key : BuiltInKits.KEYS) {
            BuiltInKits.Loaded kit = BuiltInKits.load(key, mapper);
            BuiltInKits.Definition d = kit.definition();
            assertThat(TokenValidator.validate(d.tokens(), d.manifest(), kit.css())).as(key).isEmpty();
            // A set's templates are its first built-in kit; a later kit on the same set (menora-digital
            // on react-tailwind-crud) is written over them at generation time and has no templates.
            if (!sets.add(d.baseSet())) continue;

            String css = KitCssWriter.write(d.tokens(), d.manifest(), List.of(), kit.css(), KitCssWriter.MUSTACHE);
            String main = KitEntryWriter.write(d.manifest().fonts());
            String dir = "templates/fullstack/" + d.baseSet() + "/";
            if (SYNC) {
                Path source = Path.of("src/main/resources", dir);
                Files.writeString(source.resolve("index.css.mustache"), css, StandardCharsets.UTF_8);
                Files.writeString(source.resolve("main.tsx"), main, StandardCharsets.UTF_8);
                continue;
            }
            assertThat(read(dir + "index.css.mustache")).as(key + " → " + dir + "index.css.mustache (run with -Ddesign.sync=true)")
                    .isEqualTo(css);
            assertThat(read(dir + "main.tsx")).as(key + " → " + dir + "main.tsx").isEqualTo(main);
        }
    }

    private static String read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
