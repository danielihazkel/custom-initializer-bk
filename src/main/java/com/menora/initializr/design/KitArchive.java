package com.menora.initializr.design;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A kit version as one file, {@code <key>-v<N>.menora-kit.zip}, to move a design system between
 * generators (or keep it outside one):
 * <pre>
 * menora-kit.json     the kit and version: name, base set, tokens, manifest, and the lists below
 * kit.css             the kit's own CSS (absent when empty)
 * files/&lt;path&gt;        each component file (a slot replacement or a helper), at its app path
 * assets/&lt;path&gt;       each asset's bytes, at its target path
 * </pre>
 * Reading is bounded (entry count and total size) and never touches the disk: paths are only
 * keys, checked by the same validators as a file saved in the Studio when the archive is imported.
 */
public final class KitArchive {

    public static final String FORMAT = "menora-kit/1";
    public static final String DESCRIPTOR = "menora-kit.json";
    static final int MAX_ENTRIES = 512;
    static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private KitArchive() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Descriptor(String format, String key, String name, String description, String baseSetKey,
                      String defaultPaletteId, Integer version, String notes, DesignTokens tokens, KitManifest manifest,
                      List<FileEntry> files, List<AssetEntry> assets) {
    }

    record FileEntry(String path, String role) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AssetEntry(String targetPath, String role, String meta) {
    }

    public static byte[] write(DesignKitService.KitContent c, ObjectMapper mapper) {
        Descriptor d = new Descriptor(FORMAT, c.key(), c.name(), c.description(), c.baseSetKey(), c.defaultPaletteId(),
                c.version(), c.notes(), c.tokens(), c.manifest(),
                c.files().stream().map(f -> new FileEntry(f.path(), f.role())).toList(),
                c.assets().stream().map(a -> new AssetEntry(a.targetPath(), a.role(), a.meta())).toList());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, DESCRIPTOR, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(d));
            if (c.css() != null && !c.css().isEmpty()) put(zip, DesignKitService.KIT_CSS, c.css().getBytes(StandardCharsets.UTF_8));
            for (ResolvedKit.File f : c.files()) put(zip, "files/" + f.path(), f.content().getBytes(StandardCharsets.UTF_8));
            for (ResolvedKit.Asset a : c.assets()) put(zip, "assets/" + a.targetPath(), a.content());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the kit archive", e);
        }
        return bytes.toByteArray();
    }

    /** The archive's content; a 400 for anything that is not a kit archive of this format. */
    public static DesignKitService.KitContent read(byte[] archive, ObjectMapper mapper) {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (entries.size() >= MAX_ENTRIES) throw DesignKitException.badRequest("The archive has more than " + MAX_ENTRIES + " files");
                byte[] content = readBounded(zip, MAX_TOTAL_BYTES - total);
                total += content.length;
                entries.put(e.getName(), content);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw DesignKitException.badRequest("Not a kit archive (.menora-kit.zip): " + e.getMessage());
        }
        byte[] json = entries.get(DESCRIPTOR);
        if (json == null) throw DesignKitException.badRequest("Not a kit archive: it has no " + DESCRIPTOR);
        Descriptor d;
        try {
            d = mapper.readValue(json, Descriptor.class);
        } catch (IOException e) {
            throw DesignKitException.badRequest(DESCRIPTOR + " is not valid: " + e.getMessage());
        }
        if (!FORMAT.equals(d.format())) {
            throw DesignKitException.badRequest(DESCRIPTOR + ": format must be '" + FORMAT + "', not '" + d.format() + "'");
        }
        List<ResolvedKit.File> files = new ArrayList<>();
        for (FileEntry f : d.files() == null ? List.<FileEntry>of() : d.files()) {
            byte[] content = entries.get("files/" + f.path());
            if (f.path() == null || content == null) throw DesignKitException.badRequest("The archive lists " + f.path() + " but does not hold it");
            files.add(new ResolvedKit.File(f.path(), f.role(), new String(content, StandardCharsets.UTF_8)));
        }
        List<ResolvedKit.Asset> assets = new ArrayList<>();
        for (AssetEntry a : d.assets() == null ? List.<AssetEntry>of() : d.assets()) {
            byte[] content = entries.get("assets/" + a.targetPath());
            if (a.targetPath() == null || content == null) throw DesignKitException.badRequest("The archive lists " + a.targetPath() + " but does not hold it");
            assets.add(new ResolvedKit.Asset(a.targetPath(), a.role(), null, a.meta(), content));
        }
        byte[] css = entries.get(DesignKitService.KIT_CSS);
        return new DesignKitService.KitContent(d.key(), d.name(), d.description(), d.baseSetKey(), d.defaultPaletteId(),
                d.version(), d.notes(), d.tokens(), d.manifest(), css == null ? "" : new String(css, StandardCharsets.UTF_8),
                files, assets);
    }

    /** {@code <key>-v<N>.menora-kit.zip} ({@code -draft} for a draft). */
    public static String fileName(DesignKitService.KitContent c) {
        return c.key() + "-" + (c.version() == null ? "draft" : "v" + c.version()) + ".menora-kit.zip";
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] readBounded(InputStream in, long remaining) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > remaining) {
                throw DesignKitException.badRequest("The archive unpacks to more than " + MAX_TOTAL_BYTES / 1024 / 1024 + " MB");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
