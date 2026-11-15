package com.menora.initializr.config;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The templates behind what the plain generators write themselves: the wizards' Java
 * (SQL, OpenAPI, SOAP, events) and the multi-module parent POM. They live on the classpath under
 * {@code templates/wizard/<name>.mustache} and render through {@link CompiledTemplates}.
 *
 * <p>These used to be {@code StringBuilder} code. They are files now so that what a wizard emits
 * can be read — and changed — as the text it is; the generators keep the parsing and build the
 * view each template reads. Unlike the catalog and the fullstack sets they are <em>not</em>
 * database rows: a wizard template is written against a Java view-model that an edit in the
 * admin screen could not follow, and the entity-template tables, their sync and their admin UI
 * are shaped for the fullstack sets. {@code WizardGoldenOutputTests} pins what they render.
 */
public final class WizardTemplates {

    private static final Map<String, String> TEXTS = new ConcurrentHashMap<>();

    private WizardTemplates() {
    }

    /** Renders {@code templates/wizard/<name>.mustache} against {@code view}. */
    public static String render(String name, Object view) {
        return CompiledTemplates.render(text(name), view);
    }

    /** The template's text, with LF line endings whatever git made of the file on this checkout. */
    static String text(String name) {
        return TEXTS.computeIfAbsent(name, n -> {
            String path = "templates/wizard/" + n + ".mustache";
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            } catch (IOException e) {
                throw new UncheckedIOException("Missing wizard template " + path, e);
            }
        });
    }
}
