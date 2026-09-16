package com.menora.initializr.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Filesystem helpers shared by every generation endpoint — zipping a generated tree,
 * reading a file for the preview JSON, and copying one tree into another.
 *
 * <p>Each of these previously existed as a private copy in {@code WizardStarterController},
 * {@code FullstackStarterController}, {@code MultiModuleController} and
 * {@code FrontendProjectGenerator}. They are public because the frontend generator lives
 * in a different package; the tree-shaping counterpart is {@link PreviewTreeBuilder}.
 */
public final class GeneratedProjectFiles {

    private GeneratedProjectFiles() {}

    /** Zip every regular file under {@code dir}, nested beneath a single {@code rootDirName} folder. */
    public static byte[] zipDirectory(Path dir, String rootDirName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String entryName = rootDirName + "/" + dir.relativize(p).toString().replace('\\', '/');
                            try {
                                zos.putNextEntry(new ZipEntry(entryName));
                                Files.copy(p, zos);
                                zos.closeEntry();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        }
        return baos.toByteArray();
    }

    /**
     * Read a generated file as UTF-8 text for the preview response. Binary content (or an
     * unreadable file) yields a placeholder rather than failing the whole preview.
     */
    public static String readSafely(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[binary file]";
        }
    }

    /** Recursively copy {@code source} into {@code target}, overwriting existing files. */
    public static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(s -> {
                Path t = target.resolve(source.relativize(s).toString());
                try {
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(t);
                    } else {
                        if (t.getParent() != null) Files.createDirectories(t.getParent());
                        Files.copy(s, t, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
