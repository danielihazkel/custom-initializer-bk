package com.menora.initializr.config;

import com.menora.initializr.fullstack.FullstackStarterRequest;
import com.menora.initializr.fullstack.schema.SchemaBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.menora.initializr.config.FullstackRootFiles.*;

/** The {@code menora-model.json} at a fullstack project's root: the model it was generated from, and a block about the generation itself. */
final class FullstackModelFile {

    private FullstackModelFile() {}

    /** The model the project was generated from, at the zip root, for the editor's Import JSON. */
    static final String MODEL_FILE = "menora-model.json";

    /** Marks a model file that holds the request itself (a request sent without the editor's snapshot). */
    static final String REQUEST_MODEL_FORMAT = "menora-fullstack-request/1";

    private static final com.fasterxml.jackson.databind.ObjectMapper MODEL_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The editor's own snapshot when the request carries a usable one (an object with an
     * {@code entities} array, at most the team-model size), else the request itself — everything
     * but {@code editorModel} — under {@link #REQUEST_MODEL_FORMAT}, which the editor imports too.
     */
    static String modelJson(FullstackStarterRequest body) throws IOException {
        return modelJson(body, null);
    }

    /**
     * {@link #modelJson(FullstackStarterRequest)} with a {@code generator} block naming the
     * templates the project was rendered with ({@link FullstackProjectAssembler#templatesHash}). An update made later
     * compares it with the templates of the day.
     */
    static String modelJson(FullstackStarterRequest body, String templatesHash) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode generator = null;
        if (templatesHash != null) {
            generator = MODEL_MAPPER.createObjectNode();
            generator.put("templatesHash", templatesHash);
            // Which version of the generator's own tables the baseline was written with: an update
            // brings an older one up (SchemaBuilder.asOf).
            generator.put(SCHEMA_VERSION_KEY, com.menora.initializr.fullstack.schema.SchemaBuilder.CURRENT_VERSION);
        }
        com.fasterxml.jackson.databind.JsonNode model = body.editorModel();
        if (model != null && model.isObject() && model.path("entities").isArray()) {
            com.fasterxml.jackson.databind.node.ObjectNode snapshot = model.deepCopy();
            // Last, so the editor's own keys read as they did.
            snapshot.remove(GENERATOR_KEY);
            if (generator != null) snapshot.set(GENERATOR_KEY, generator);
            String json = MODEL_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            if (json.getBytes(StandardCharsets.UTF_8).length <= FullstackModelController.MAX_SNAPSHOT_BYTES) return json + "\n";
        }
        com.fasterxml.jackson.databind.node.ObjectNode request = MODEL_MAPPER.valueToTree(body);
        request.remove("editorModel");
        com.fasterxml.jackson.databind.node.ObjectNode wrapper = MODEL_MAPPER.createObjectNode();
        wrapper.put("format", REQUEST_MODEL_FORMAT);
        wrapper.set("request", request);
        if (generator != null) wrapper.set(GENERATOR_KEY, generator);
        return MODEL_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper) + "\n";
    }

    /** The model file's block about the generation itself — never part of the model. */
    static final String GENERATOR_KEY = "generator";

    static final String SCHEMA_VERSION_KEY = "schemaVersion";

    /** The {@code generator.templatesHash} of a model file, or null when it has none. */
    static String templatesHashOf(byte[] modelFile) {
        if (modelFile == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode hash = MODEL_MAPPER.readTree(modelFile).path(GENERATOR_KEY).path("templatesHash");
            return hash.isTextual() ? hash.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
