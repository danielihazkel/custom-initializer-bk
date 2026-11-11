package com.menora.initializr.fullstack.patch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What changed between two generations of one project, packaged for a team that has already
 * edited the first: {@code update.patch} for {@code git apply --3way}, the new and changed files
 * whole under {@code files/}, the removed paths in {@code DELETED.txt}, and {@code UPDATE.md}
 * saying what to do with them.
 *
 * <p>Both generations are made now, with today's templates, from the old model and the new one.
 * So the patch carries what the <em>model</em> change does and nothing else — it is not a way to
 * pick up newer templates, and where the templates have moved on since the project was first
 * generated a hunk's surroundings may not match (the three-way merge copes with most of that).
 */
public final class ProjectUpdateBuilder {

    private ProjectUpdateBuilder() {}

    public static final String PATCH_FILE = "update.patch";
    public static final String NOTES_FILE = "UPDATE.md";
    public static final String DELETED_FILE = "DELETED.txt";
    public static final String FILES_DIR = "files/";

    /** What the two generations were made with, for the notes. Either hash may be null. */
    public record Info(String artifactId, String baseTemplatesHash, String templatesHash) {}

    /** The update: its zip, and the paths by what happened to them (sorted). */
    public record ProjectUpdate(byte[] zip, String patch, List<String> added, List<String> changed,
                                List<String> deleted, List<String> whole) {
        public boolean isEmpty() {
            return added.isEmpty() && changed.isEmpty() && deleted.isEmpty();
        }
    }

    /**
     * @param base      the project as generated from the old model: path → bytes
     * @param target    the project as generated from the new model
     * @param replaced  paths never patched, only delivered whole: files nobody edits by hand and
     *                  every generation rewrites (the model file)
     */
    public static ProjectUpdate build(Map<String, byte[]> base, Map<String, byte[]> target, Info info,
                                      Set<String> replaced) throws IOException {
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> binary = new ArrayList<>();
        GitPatchWriter patch = new GitPatchWriter();

        TreeSet<String> paths = new TreeSet<>(base.keySet());
        paths.addAll(target.keySet());
        for (String path : paths) {
            byte[] before = base.get(path);
            byte[] after = target.get(path);
            if (before != null && after != null && Arrays.equals(before, after)) continue;
            // A patch is lines of text. Anything else travels whole, under files/.
            boolean text = !replaced.contains(path)
                    && (before == null || isText(before)) && (after == null || isText(after));
            if (!text) binary.add(path);
            if (before == null) {
                added.add(path);
                if (text) patch.added(path, after);
            } else if (after == null) {
                deleted.add(path);
                if (text) patch.deleted(path, before);
            } else {
                changed.add(path);
                if (text) patch.changed(path, before, after);
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            String root = info.artifactId() + "-update/";
            entry(zip, root + NOTES_FILE, notes(info, added, changed, deleted, binary).getBytes(StandardCharsets.UTF_8));
            entry(zip, root + PATCH_FILE, patch.toString().getBytes(StandardCharsets.UTF_8));
            if (!deleted.isEmpty()) {
                entry(zip, root + DELETED_FILE, (String.join("\n", deleted) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            for (String path : added) entry(zip, root + FILES_DIR + path, target.get(path));
            for (String path : changed) entry(zip, root + FILES_DIR + path, target.get(path));
        }
        return new ProjectUpdate(bytes.toByteArray(), patch.toString(), List.copyOf(added), List.copyOf(changed),
                List.copyOf(deleted), List.copyOf(binary));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        // A fixed time: the same two models give the same zip, byte for byte.
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    /** Text is UTF-8 without a NUL byte — the test {@code git} uses, plus decoding. */
    static boolean isText(byte[] content) {
        for (byte b : content) if (b == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static String notes(Info info, List<String> added, List<String> changed, List<String> deleted, List<String> binary) {
        StringBuilder md = new StringBuilder();
        md.append("# Update for ").append(info.artifactId()).append("\n\n");
        if (added.isEmpty() && changed.isEmpty() && deleted.isEmpty()) {
            md.append("The two models generate the same project: there is nothing to apply.\n");
            return md.toString();
        }
        md.append("What changing the model changes in the generated project: ")
                .append(added.size()).append(" added, ")
                .append(changed.size()).append(" changed, ")
                .append(deleted.size()).append(" removed.\n\n");

        md.append("## Apply it\n\n")
                .append("From the project's root (the folder holding `backend/` and `frontend/`), with your own work\n")
                .append("committed or stashed:\n\n")
                .append("```\n")
                .append("git apply --3way --whitespace=nowarn path/to/").append(PATCH_FILE).append("\n")
                .append("git status\n")
                .append("```\n\n")
                .append("`--3way` merges the change with edits you made to the same files. Where both touched the\n")
                .append("same lines the file is left with conflict markers (`<<<<<<<`), listed by `git status` as\n")
                .append("\"both modified\" — resolve those as you would after a merge, then `git add` them.\n\n")
                .append("If git reports `repository lacks the necessary blob`, the file was never committed as\n")
                .append("generated, so there is nothing to merge against. Then either:\n\n")
                .append("- `git apply --reject --whitespace=nowarn path/to/").append(PATCH_FILE)
                .append("` — applies every hunk that fits and writes the rest to `*.rej` files beside the\n")
                .append("  files they belong to; or\n")
                .append("- copy the file from `").append(FILES_DIR).append("` (the whole file as the new model generates it) and\n")
                .append("  re-apply your edits by hand.\n\n")
                .append("A working tree checked out with CRLF line endings may also need `--ignore-whitespace`.\n\n");

        if (!binary.isEmpty()) {
            md.append("## Not in the patch\n\n")
                    .append("The patch does not carry these: they are not text, or they are files a generation\n")
                    .append("rewrites whole (`menora-model.json`). Copy the added and changed ones over yours from\n")
                    .append("`").append(FILES_DIR).append("`, and delete the removed ones:\n\n");
            for (String path : binary) {
                md.append("- `").append(path).append("` (")
                        .append(added.contains(path) ? "added" : deleted.contains(path) ? "removed" : "changed").append(")\n");
            }
            md.append('\n');
        }

        md.append("## What this is not\n\n")
                .append("Both sides of the comparison were generated now, from the model the project had and the\n")
                .append("model it should have. The patch is the difference the *model* makes — it does not bring in\n")
                .append("changes the generator's templates have had since the project was first generated.\n");
        if (info.baseTemplatesHash() != null && info.templatesHash() != null) {
            if (info.baseTemplatesHash().equals(info.templatesHash())) {
                md.append("\nThe templates are the ones the project was generated with (`")
                        .append(shortHash(info.templatesHash())).append("`), so every hunk should fit code you have not edited.\n");
            } else {
                md.append("\nThe templates have changed since the project was generated (`")
                        .append(shortHash(info.baseTemplatesHash())).append("` then, `")
                        .append(shortHash(info.templatesHash())).append("` now). A hunk next to code the newer templates write\n")
                        .append("differently may not fit; `--3way` usually still places it, and what it cannot is left as a\n")
                        .append("conflict.\n");
            }
        }
        md.append('\n');

        section(md, "Added", added);
        section(md, "Changed", changed);
        section(md, "Removed", deleted);
        if (!deleted.isEmpty()) {
            md.append("`").append(DELETED_FILE).append("` lists the removed paths, one to a line, for a script.\n");
        }
        return md.toString();
    }

    private static void section(StringBuilder md, String title, List<String> paths) {
        if (paths.isEmpty()) return;
        md.append("## ").append(title).append("\n\n");
        for (String path : paths) md.append("- `").append(path).append("`\n");
        md.append('\n');
    }

    private static String shortHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }
}
