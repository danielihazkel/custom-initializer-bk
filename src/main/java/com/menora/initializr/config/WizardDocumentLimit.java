package com.menora.initializr.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * How much pasted text one request may hand the wizards: {@code menora.wizard.max-documents-size}
 * (5 MB unless configured), over all its documents together.
 *
 * <p>A SQL script, an OpenAPI or AsyncAPI document and a WSDL are each parsed in memory — the
 * OpenAPI ones into a model several times their size — and nothing bounded them, so one request
 * could occupy the server for as long and as much as its sender liked. Counted in characters,
 * which is what the parsers are given.
 */
@Component
public class WizardDocumentLimit {

    private final long maxCharacters;

    public WizardDocumentLimit(@Value("${menora.wizard.max-documents-size:5MB}") DataSize max) {
        this.maxCharacters = max.toBytes();
    }

    /** One document, as the drawers' detection and the editor's imports send. */
    public void check(String document) {
        require(document == null ? 0 : document.length());
    }

    /** A document and the files it comes with, as the SOAP drawer's detection sends. */
    public void check(String document, Map<String, String> files) {
        long total = document == null ? 0 : document.length();
        if (files != null) {
            for (String file : files.values()) {
                if (file != null) total += file.length();
            }
        }
        require(total);
    }

    /** The files of every dependency's document as one map, to count beside the documents. */
    public static Map<String, String> flatten(Map<String, Map<String, String>> filesByDependency) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (filesByDependency == null) return out;
        filesByDependency.forEach((dependency, files) -> {
            if (files != null) files.forEach((name, text) -> out.put(dependency + "/" + name, text));
        });
        return out;
    }

    /** Every document of a generation request: each map is one wizard's, keyed by dependency. */
    @SafeVarargs
    public final void check(Map<String, String>... byDependency) {
        long total = 0;
        for (Map<String, String> documents : byDependency) {
            if (documents == null) continue;
            Collection<String> values = documents.values();
            for (String document : values) {
                if (document != null) total += document.length();
            }
        }
        require(total);
    }

    private void require(long characters) {
        if (characters > maxCharacters) {
            throw new WizardArgumentException("The pasted documents are " + megabytes(characters)
                    + " MB; this server takes " + megabytes(maxCharacters)
                    + " MB in one request (menora.wizard.max-documents-size)");
        }
    }

    private static String megabytes(long characters) {
        return String.format(Locale.ROOT, "%.1f", characters / (1024.0 * 1024.0));
    }
}
