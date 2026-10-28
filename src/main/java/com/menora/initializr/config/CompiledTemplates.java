package com.menora.initializr.config;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Mustache compiler every generator uses ({@code escapeHTML=false} — the output is Java,
 * TypeScript, JSON, YAML, never HTML), with its compiled templates kept by template text.
 *
 * <p>Compiling is most of the cost of rendering a small file, and the same catalog content is
 * compiled over and over: a fullstack request used to compile {@code EntityPage.tsx.mustache}
 * (57 KB) once per entity. Keyed by the text itself, an admin edit is simply a new key — nothing
 * to invalidate — and the least recently used templates fall out past {@link #MAX_TEMPLATES}.
 * A compiled jmustache {@link Template} keeps no state between executions, so it is safe to run
 * from several requests at once. A template that does not compile throws here as before and
 * is never cached.
 */
public final class CompiledTemplates {

    static final int MAX_TEMPLATES = 1024;

    private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);

    private static final Map<String, Template> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Template> eldest) {
                    return size() > MAX_TEMPLATES;
                }
            });

    private CompiledTemplates() {}

    /** The compiled form of {@code text}, compiled on first use. */
    public static Template of(String text) {
        Template template = CACHE.get(text);
        if (template == null) {
            template = MUSTACHE.compile(text);
            CACHE.put(text, template);
        }
        return template;
    }

    /** Renders {@code text} as a Mustache template against {@code context}. */
    public static String render(String text, Object context) {
        return of(text).execute(context);
    }
}
