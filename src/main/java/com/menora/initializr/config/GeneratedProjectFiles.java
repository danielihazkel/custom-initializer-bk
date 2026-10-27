package com.menora.initializr.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final Logger log = LoggerFactory.getLogger(GeneratedProjectFiles.class);

    /** Deletes finished generation trees off the request thread — see {@link #deleteLater}. */
    private static final ExecutorService CLEANUP = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "generated-files-cleanup");
        thread.setDaemon(true);
        return thread;
    });

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

    /**
     * Create {@code file}'s parent directories unless they already exist. Generators call this
     * before every file they write, and most parents exist by then; {@link Files#createDirectories}
     * finds that out by trying to create the directory and catching the failure, which costs an
     * exception and an extra filesystem call per file.
     */
    public static void createParentDirectories(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.isDirectory(parent)) Files.createDirectories(parent);
    }

    /**
     * Move {@code source} to {@code target} (which must not exist). Both are under the temp dir,
     * so this is a rename — far cheaper than copying every file and deleting the original.
     * Falls back to {@link #copyDirectory} when they are on different file stores; the caller
     * still deletes {@code source} as before.
     */
    public static void moveDirectory(Path source, Path target) throws IOException {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException e) {
            copyDirectory(source, target);
        }
    }

    /**
     * Delete a generation's temp tree after the response has its bytes, on a background thread:
     * deleting a few hundred small files is a large share of a request's time (much more so on
     * Windows), and nothing reads the tree once it is zipped or previewed.
     */
    public static void deleteLater(Path dir) {
        CLEANUP.execute(() -> {
            try {
                FileSystemUtils.deleteRecursively(dir);
            } catch (IOException e) {
                log.warn("Could not delete temp directory {}: {}", dir, e.getMessage());
            }
        });
    }
}
