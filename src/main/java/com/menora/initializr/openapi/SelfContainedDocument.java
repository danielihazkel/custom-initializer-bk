package com.menora.initializr.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.parser.ObjectMapperFactory;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.swagger.v3.parser.util.DeserializationUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Makes sure a pasted OpenAPI document leads nowhere but into itself before swagger-parser reads
 * it.
 *
 * <p>The parser resolves references, and a reference may be a URL or a file path: it fetches
 * {@code $ref: http://host/x.yaml} and reads {@code $ref: ../../some/file} — from this server,
 * for whoever posted the document. Measured against swagger-parser 2.1.22 with a local HTTP
 * server, three things make it go outside the document:
 * <ul>
 *   <li>a {@code $ref} that is not a fragment ({@code #/…}) or a bare component name — wherever it
 *       is written: a schema, a property, a path item, a parameter, a response, an example, a
 *       callback;</li>
 *   <li>a discriminator {@code mapping} value of that kind (OpenAPI 3.0);</li>
 *   <li>a schema's {@code $id} (OpenAPI 3.1): the parser fetches the {@code $id} itself and then
 *       looks for the schema's <em>internal</em> references in what came back.</li>
 * </ul>
 * The first two are refused with the place and the reference named, as the SOAP wizard refuses a
 * WSDL that imports another file. An {@code $id} is dropped instead: documents carry one
 * routinely, it cannot be honoured without fetching, and without it the schema's references
 * resolve inside the document — which is what they were failing to do.
 *
 * <p>The document is read here the way the parser itself reads it (its own
 * {@link DeserializationUtils}, its own fallback), so the two cannot disagree about what a
 * document says. One that cannot be read here at all is handed on marked unreadable, and is then
 * parsed without resolving anything — the parser reports what is wrong with it in its own words.
 */
public final class SelfContainedDocument {

    private SelfContainedDocument() {}

    /**
     * @param text     what to give the parser: the document as pasted, or — when an {@code $id}
     *                 was dropped — the same document as JSON without it
     * @param resolve  whether the parser may resolve references: false for a document this class
     *                 could not read
     */
    public record Checked(String text, boolean resolve) {

        /** The parser's options for this document. */
        public ParseOptions parseOptions() {
            ParseOptions options = new ParseOptions();
            options.setResolve(resolve);
            return options;
        }
    }

    /** A document that refers to something outside itself; {@link #messages()} name each place. */
    public static final class ExternalReferenceException extends RuntimeException {

        private final List<String> messages;

        ExternalReferenceException(List<String> messages) {
            super(String.join("; ", messages));
            this.messages = List.copyOf(messages);
        }

        public List<String> messages() {
            return messages;
        }
    }

    /**
     * Checks {@code document} and says how to parse it.
     *
     * @throws ExternalReferenceException when it refers to a URL or a file
     */
    public static Checked check(String document) {
        JsonNode tree = read(document);
        if (tree == null || !tree.isObject()) {
            return new Checked(document, false);
        }
        List<String> problems = new ArrayList<>();
        boolean[] changed = {false};
        walk(tree, "", problems, changed);
        if (!problems.isEmpty()) {
            throw new ExternalReferenceException(problems);
        }
        return new Checked(changed[0] ? tree.toString() : document, true);
    }

    /** The document as a tree, read as {@code OpenAPIV3Parser.readContents} reads it; null when it cannot be. */
    private static JsonNode read(String document) {
        if (document == null || document.isBlank()) return null;
        try {
            return DeserializationUtils.deserializeIntoTree(document, null, new ParseOptions(), new SwaggerParseResult());
        } catch (Exception first) {
            try {
                return (document.trim().startsWith("{")
                        ? ObjectMapperFactory.createJson() : ObjectMapperFactory.createYaml()).readTree(document);
            } catch (Exception second) {
                return null;
            }
        }
    }

    private static void walk(JsonNode node, String where, List<String> problems, boolean[] changed) {
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                walk(array.get(i), where + "[" + i + "]", problems, changed);
            }
            return;
        }
        if (!(node instanceof ObjectNode object)) return;

        // A property may be called "$id" or "$ref" (properties: {$id: {type: string}}): its value
        // is then a schema, not text. Only text is a reference or an identifier.
        JsonNode id = object.get("$id");
        if (id != null && id.isTextual()) {
            object.remove("$id");
            changed[0] = true;
        }
        JsonNode ref = object.get("$ref");
        if (ref != null && ref.isTextual() && isExternal(ref.asText())) {
            problems.add(outside(at(where, "$ref"), ref.asText()));
        }
        JsonNode mapping = object.path("discriminator").path("mapping");
        if (mapping.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = mapping.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (entry.getValue().isTextual() && isExternal(entry.getValue().asText())) {
                    problems.add(outside(at(at(at(where, "discriminator"), "mapping"), entry.getKey()),
                            entry.getValue().asText()));
                }
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            walk(entry.getValue(), at(where, entry.getKey()), problems, changed);
        }
    }

    /**
     * Whether the parser would look outside the document for {@code reference}. A fragment stays
     * inside it, and so does a bare name ({@code Pet}), which the parser reads as
     * {@code #/components/…/Pet}; anything with a {@code /}, a {@code .} or a {@code :} it takes
     * for a URL or a file.
     */
    static boolean isExternal(String reference) {
        String ref = reference.trim();
        if (ref.isEmpty() || ref.startsWith("#")) return false;
        return ref.indexOf('/') >= 0 || ref.indexOf('.') >= 0 || ref.indexOf(':') >= 0 || ref.indexOf('\\') >= 0;
    }

    private static String at(String where, String key) {
        return where.isEmpty() ? key : where + "." + key;
    }

    private static String outside(String where, String reference) {
        return where + " refers to '" + reference + "', which is not part of the document given. "
                + "Give one self-contained document.";
    }
}
