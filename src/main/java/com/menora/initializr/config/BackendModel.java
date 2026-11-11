package com.menora.initializr.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.FileContributionEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code menora-model.json} of a plain backend: what the project was generated from, kept at its
 * root so it can be reopened in the generator and — after the model changes — updated with a
 * patch instead of generated again ({@code POST /starter.patch}). The fullstack projects have had
 * one since Round 6 ({@code FullstackStarterController}); this is the same idea for
 * {@code /starter.zip}, the wizards and multi-module projects.
 *
 * <pre>
 * {
 *   "format": "menora-backend-model/1",
 *   "kind": "backend" | "multimodule",
 *   "request": { … a /starter-wizard.zip body; a multi-module one adds "modules" … },
 *   "generator": { "catalogHash": "…" }
 * }
 * </pre>
 *
 * The {@code request} is what a generation <em>resolved</em>, not what was typed: the starters a
 * wizard added and the datasource roles defaulted are in it, so generating it again gives the
 * same project.
 */
public final class BackendModel {

    private BackendModel() {
    }

    public static final String FILE = "menora-model.json";
    public static final String FORMAT = "menora-backend-model/1";
    public static final String KIND_BACKEND = "backend";
    public static final String KIND_MULTI_MODULE = "multimodule";

    /** The model file's block about the generation itself — never part of the model. */
    public static final String GENERATOR_KEY = "generator";

    private static final ObjectMapper MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * {@code .gitattributes} with one line ending declared for every text file, before whatever
     * the file already says (the framework's own lines about the Maven wrapper, which override it
     * for the files they name). Without it git checks a project out with the machine's line
     * endings, and a patch made from generated files — which are LF — meets CRLF ones.
     */
    public static String gitAttributes(String existing) {
        String rest = existing == null ? "" : existing.replace("\r\n", "\n");
        if (rest.contains("* text=auto eol=lf")) return rest;
        return "# One line ending for every text file, whatever the machine: an update compares generated\n"
                + "# files with yours (see HELP.md, \"Reopening and updating this project\").\n"
                + "* text=auto eol=lf\n" + rest;
    }

    /** A value (a wizard's options record) as the JSON a request carries it in; nulls left out. */
    public static JsonNode wire(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static ObjectNode newRequest() {
        return MAPPER.createObjectNode();
    }

    /** {@code ["a", "b"]}. */
    public static ArrayNode array(Collection<String> values) {
        ArrayNode out = MAPPER.createArrayNode();
        values.forEach(out::add);
        return out;
    }

    /** {@code {"dep": "text"}} for the entries that have text, in key order; null when none has. */
    public static ObjectNode texts(Map<String, String> byDep) {
        ObjectNode out = MAPPER.createObjectNode();
        new TreeMap<>(byDep).forEach((dep, text) -> {
            if (text != null && !text.isBlank()) out.put(dep, text);
        });
        return out.isEmpty() ? null : out;
    }

    /** The file's text: pretty-printed, one trailing newline. */
    public static String json(String kind, ObjectNode request, String catalogHash) {
        ObjectNode model = MAPPER.createObjectNode();
        model.put("format", FORMAT);
        model.put("kind", kind);
        model.set("request", request);
        if (catalogHash != null) model.putObject(GENERATOR_KEY).put("catalogHash", catalogHash);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(model).replace("\r\n", "\n") + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The model could not be written", e);
        }
    }

    /** The {@code generator.catalogHash} of a model file, or null when it has none. */
    public static String catalogHashOf(byte[] modelFile) {
        if (modelFile == null) return null;
        try {
            JsonNode hash = MAPPER.readTree(modelFile).path(GENERATOR_KEY).path("catalogHash");
            return hash.isTextual() ? hash.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * One id for the catalog rows a project is generated from: the SHA-256 of every file
     * contribution and build customization of its dependencies (line endings aside, so the same
     * catalog hashes alike on every machine; in a fixed order, whatever order the rows were
     * loaded in). It changes when one of those rows is edited — in the seed or in admin — and
     * tells an update whether the project was generated with the catalog of the day.
     */
    public static String catalogHash(Collection<FileContributionEntity> files, Collection<BuildCustomizationEntity> builds) {
        Set<String> lines = new TreeSet<>();
        for (FileContributionEntity f : files) {
            lines.add(String.join("\u0001", "file", s(f.getDependencyId()), String.valueOf(f.getFileType()), s(f.getTargetPath()),
                    String.valueOf(f.getSubstitutionType()), s(f.getSubOptionId()), s(f.getJavaVersion()),
                    String.valueOf(f.getSortOrder()), s(f.getContent()).replace("\r\n", "\n")));
        }
        for (BuildCustomizationEntity c : builds) {
            lines.add(String.join("\u0001", "build", s(c.getDependencyId()), String.valueOf(c.getCustomizationType()),
                    s(c.getMavenGroupId()), s(c.getMavenArtifactId()), s(c.getVersion()), s(c.getExcludeFromGroupId()),
                    s(c.getExcludeFromArtifactId()), s(c.getRepoId()), s(c.getRepoName()), s(c.getRepoUrl()),
                    String.valueOf(c.isSnapshotsEnabled()), s(c.getScope()), s(c.getSubOptionId()),
                    s(c.getCompatibilityRange()), String.valueOf(c.getSortOrder())));
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                sha.update(line.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String s(String value) {
        return value == null ? "" : value;
    }

    /** The names under {@code request.modules} of a multi-module model; empty for any other. */
    public static List<String> modulesOf(JsonNode request) {
        List<String> out = new ArrayList<>();
        JsonNode modules = request == null ? null : request.get("modules");
        if (modules != null && modules.isArray()) {
            modules.forEach(m -> {
                if (m.isTextual() && !m.asText().isBlank()) out.add(m.asText());
            });
        }
        return out;
    }
}
