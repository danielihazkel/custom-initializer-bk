package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.menora.initializr.observability.AuditDetails;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * What a generation actually wrote, named by its content — so an update can start from it.
 *
 * <p>An update used to generate both sides with the generator of the day, so its patch carried
 * the model's change only: a fix to a template, a catalog row or the Java behind them never
 * reached a project generated before it. Now every project's {@code menora-model.json} names its
 * tree ({@code generator.snapshot}: the SHA-256 of every other file's path and bytes, paths in
 * order), the server keeps the tree of each download ({@code observability/SnapshotStore}), and an
 * update compares that stored tree with the new model generated today — model change and generator
 * fixes alike.
 *
 * <p>Content-addressed: the same output is the same id (two downloads of one project are stored
 * once, and {@code /starter.zip} and the wizard endpoint without a document still give the same
 * bytes). The model file is left out of its own id.
 */
public final class GeneratedSnapshots {

    public static final String MODEL_FILE = "menora-model.json";
    public static final String GENERATOR_KEY = "generator";
    public static final String SNAPSHOT_KEY = "snapshot";
    /** The response header that names the snapshot of the project (or of the update's result). */
    public static final String HEADER = "X-Snapshot";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeneratedSnapshots() {}

    /**
     * Names the project under {@code root}: writes its id into the model file and hands the tree
     * to the request's audit details (stored if the request turns out to be a download). Does
     * nothing for a project without a model file (a fullstack backend, a module — parts of
     * another project). Returns the id, or null.
     */
    public static String stamp(Path root) throws IOException {
        Path model = root.resolve(MODEL_FILE);
        if (!Files.isRegularFile(model)) {
            // A request with a base directory: the project is the one folder under the root.
            java.util.List<Path> children;
            try (java.util.stream.Stream<Path> list = Files.list(root)) {
                children = list.toList();
            }
            if (children.size() == 1 && Files.isRegularFile(children.get(0).resolve(MODEL_FILE))) {
                return stamp(children.get(0));
            }
            return null;
        }
        Map<String, byte[]> tree = GeneratedProjectFiles.readTree(root);
        tree.remove(MODEL_FILE);
        String id = idOf(tree);
        Files.writeString(model, withSnapshot(Files.readString(model, StandardCharsets.UTF_8), id), StandardCharsets.UTF_8);
        AuditDetails.snapshot(id, tree);
        return id;
    }

    /**
     * The same for a tree in memory (an update's result): its id written into its model file, and
     * the tree handed to the audit details. Returns the id, or null when it has no model file.
     */
    public static String stamp(Map<String, byte[]> tree) {
        byte[] model = tree.get(MODEL_FILE);
        if (model == null) return null;
        Map<String, byte[]> rest = new TreeMap<>(tree);
        rest.remove(MODEL_FILE);
        String id = idOf(rest);
        tree.put(MODEL_FILE, withSnapshot(new String(model, StandardCharsets.UTF_8), id).getBytes(StandardCharsets.UTF_8));
        AuditDetails.snapshot(id, rest);
        return id;
    }

    /** The id of a tree (paths relative to the project, {@code /}-separated), the model file aside. */
    public static String idOf(Map<String, byte[]> tree) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> e : new TreeMap<>(tree).entrySet()) {
                if (e.getKey().equals(MODEL_FILE)) continue;
                sha.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(e.getValue());
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code modelJson} with {@code generator.snapshot} set (last in the block), pretty, LF, one final newline. */
    static String withSnapshot(String modelJson, String id) {
        try {
            JsonNode parsed = MAPPER.readTree(modelJson);
            if (!(parsed instanceof ObjectNode model)) return modelJson;
            ObjectNode generator = model.get(GENERATOR_KEY) instanceof ObjectNode g ? g : MAPPER.createObjectNode();
            generator.remove(SNAPSHOT_KEY);
            generator.put(SNAPSHOT_KEY, id);
            // The block stays last, as every model writer puts it.
            model.remove(GENERATOR_KEY);
            model.set(GENERATOR_KEY, generator);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(model).replace("\r\n", "\n") + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code generator.snapshot} of a model (file bytes, or a parsed model / request node); null when it has none. */
    public static String snapshotOf(byte[] modelFile) {
        if (modelFile == null) return null;
        try {
            return snapshotOf(MAPPER.readTree(modelFile));
        } catch (IOException e) {
            return null;
        }
    }

    public static String snapshotOf(JsonNode model) {
        if (model == null) return null;
        JsonNode id = model.path(GENERATOR_KEY).path(SNAPSHOT_KEY);
        return id.isTextual() && isId(id.asText()) ? id.asText() : null;
    }

    /** A snapshot id is a SHA-256 in lower-case hex: anything else is not looked up. */
    public static boolean isId(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    // ── Storage form ──────────────────────────────────────────────────────────

    /** A tree as a zip, entries in path order. */
    public static byte[] zip(Map<String, byte[]> tree) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(tree).entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> tree = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) tree.put(entry.getName(), zip.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tree;
    }
}
