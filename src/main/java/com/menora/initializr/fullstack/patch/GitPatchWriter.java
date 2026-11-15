package com.menora.initializr.fullstack.patch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Writes text file changes in the format {@code git diff} prints and {@code git apply} reads.
 *
 * <p>Every file carries an {@code index <old>..<new>} line with the full blob ids. That is what
 * lets {@code git apply --3way} fall back to a three-way merge when a hunk no longer matches: git
 * looks the old blob up in the repository — it is there if the file was ever committed as
 * generated — and merges the user's edits with the change.
 */
public final class GitPatchWriter {

    /** Lines of unchanged text printed around each change. */
    static final int CONTEXT = 3;

    private static final String NO_BLOB = "0".repeat(40);
    private static final String NO_NEWLINE = "\\ No newline at end of file\n";

    private final StringBuilder out = new StringBuilder();

    /** The id git gives a file's content: the SHA-1 of {@code "blob <length>\0"} and the bytes. */
    public static String blobId(byte[] content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(sha1.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The mode a new file is recorded with. Scripts are executable; a zip made on Windows has no
     * permission bits to read this from, so it goes by name.
     */
    static String modeOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        boolean script = name.equals("mvnw") || name.equals("gradlew") || name.endsWith(".sh")
                || (path.contains(".husky/") && !name.contains("."));
        return script ? "100755" : "100644";
    }

    public void added(String path, byte[] content) {
        List<String> lines = LineDiff.lines(text(content));
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        out.append("new file mode ").append(modeOf(path)).append('\n');
        out.append("index ").append(NO_BLOB).append("..").append(blobId(content)).append('\n');
        if (lines.isEmpty()) return;
        out.append("--- /dev/null\n");
        out.append("+++ b/").append(path).append('\n');
        out.append("@@ -0,0 +1,").append(lines.size()).append(" @@\n");
        for (String line : lines) line('+', line);
    }

    public void deleted(String path, byte[] content) {
        List<String> lines = LineDiff.lines(text(content));
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        out.append("deleted file mode ").append(modeOf(path)).append('\n');
        out.append("index ").append(blobId(content)).append("..").append(NO_BLOB).append('\n');
        if (lines.isEmpty()) return;
        out.append("--- a/").append(path).append('\n');
        out.append("+++ /dev/null\n");
        out.append("@@ -1,").append(lines.size()).append(" +0,0 @@\n");
        for (String line : lines) line('-', line);
    }

    public void changed(String path, byte[] before, byte[] after) {
        List<LineDiff.Edit> edits = LineDiff.diff(LineDiff.lines(text(before)), LineDiff.lines(text(after)));
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        out.append("index ").append(blobId(before)).append("..").append(blobId(after)).append(' ')
                .append(modeOf(path)).append('\n');
        out.append("--- a/").append(path).append('\n');
        out.append("+++ b/").append(path).append('\n');

        int i = 0;
        int oldLine = 0; // lines of the old file before edits[i]
        int newLine = 0;
        while (i < edits.size()) {
            if (edits.get(i).op() == LineDiff.Op.KEEP) {
                i++;
                oldLine++;
                newLine++;
                continue;
            }
            // A hunk opens CONTEXT lines before a change and runs until CONTEXT kept lines after
            // the last one; two changes closer than that share their context, so they share a hunk.
            // (Everything since the previous hunk was kept, and that hunk ended more than CONTEXT
            // lines back, so reaching back never overlaps it.)
            int start = Math.max(0, i - CONTEXT);
            int lead = i - start;
            int end = i;
            int lastChange = i;
            while (end < edits.size()) {
                if (edits.get(end).op() != LineDiff.Op.KEEP) lastChange = end;
                else if (end - lastChange > 2 * CONTEXT) break;
                end++;
            }
            end = Math.min(edits.size(), lastChange + 1 + CONTEXT);

            int oldCount = 0;
            int newCount = 0;
            for (int j = start; j < end; j++) {
                LineDiff.Op op = edits.get(j).op();
                if (op != LineDiff.Op.INSERT) oldCount++;
                if (op != LineDiff.Op.DELETE) newCount++;
            }
            int oldStart = oldLine - lead;
            int newStart = newLine - lead;
            // git counts from 1, and names the line before an empty range.
            out.append("@@ -").append(oldCount == 0 ? oldStart : oldStart + 1).append(',').append(oldCount)
                    .append(" +").append(newCount == 0 ? newStart : newStart + 1).append(',').append(newCount)
                    .append(" @@\n");
            for (int j = start; j < end; j++) {
                LineDiff.Edit edit = edits.get(j);
                line(edit.op() == LineDiff.Op.KEEP ? ' ' : edit.op() == LineDiff.Op.DELETE ? '-' : '+', edit.line());
                if (j >= i) {
                    if (edit.op() != LineDiff.Op.INSERT) oldLine++;
                    if (edit.op() != LineDiff.Op.DELETE) newLine++;
                }
            }
            i = end;
        }
    }

    private void line(char sign, String line) {
        out.append(sign).append(line);
        if (!line.endsWith("\n")) out.append('\n').append(NO_NEWLINE);
    }

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    public boolean isEmpty() {
        return out.isEmpty();
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
