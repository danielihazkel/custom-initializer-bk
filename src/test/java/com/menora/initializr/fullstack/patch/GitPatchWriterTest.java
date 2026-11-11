package com.menora.initializr.fullstack.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class GitPatchWriterTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void blobIdsAreGits() {
        // `git hash-object` of an empty file and of "hello\n".
        assertThat(GitPatchWriter.blobId(new byte[0])).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
        assertThat(GitPatchWriter.blobId(utf8("hello\n"))).isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
    }

    @Test
    void aChangeIsAHunkWithThreeLinesAround() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
        String after = "1\n2\n3\n4\nfive\n6\n7\n8\n9\n10\n";
        GitPatchWriter patch = new GitPatchWriter();
        patch.changed("src/Numbers.txt", utf8(before), utf8(after));
        assertThat(patch.toString()).isEqualTo("""
                diff --git a/src/Numbers.txt b/src/Numbers.txt
                index %s..%s 100644
                --- a/src/Numbers.txt
                +++ b/src/Numbers.txt
                @@ -2,7 +2,7 @@
                 2
                 3
                 4
                -5
                +five
                 6
                 7
                 8
                """.formatted(GitPatchWriter.blobId(utf8(before)), GitPatchWriter.blobId(utf8(after))));
    }

    @Test
    void changesCloseTogetherShareAHunkAndDistantOnesDoNot() {
        StringBuilder before = new StringBuilder();
        for (int i = 1; i <= 30; i++) before.append(i).append('\n');
        String after = before.toString().replace("\n3\n", "\n3a\n").replace("\n8\n", "\n8a\n").replace("\n25\n", "\n25a\n");
        GitPatchWriter patch = new GitPatchWriter();
        patch.changed("f", utf8(before.toString()), utf8(after));
        // 3 and 8 are four kept lines apart: one hunk. 25 is far: its own.
        assertThat(patch.toString()).contains("@@ -1,11 +1,11 @@\n").contains("@@ -22,7 +22,7 @@\n");
        assertThat(patch.toString().split("\n@@ ")).hasSize(3);
    }

    @Test
    void newAndRemovedFilesNameNoBlobOnTheirOtherSide() {
        GitPatchWriter patch = new GitPatchWriter();
        patch.added("a/New.java", utf8("class New {}\n"));
        patch.deleted("a/Old.java", utf8("class Old {}\n"));
        patch.added("frontend/entrypoint.sh", utf8("#!/bin/sh\n"));
        patch.added("empty.txt", new byte[0]);
        assertThat(patch.toString()).isEqualTo("""
                diff --git a/a/New.java b/a/New.java
                new file mode 100644
                index 0000000000000000000000000000000000000000..%s
                --- /dev/null
                +++ b/a/New.java
                @@ -0,0 +1,1 @@
                +class New {}
                diff --git a/a/Old.java b/a/Old.java
                deleted file mode 100644
                index %s..0000000000000000000000000000000000000000
                --- a/a/Old.java
                +++ /dev/null
                @@ -1,1 +0,0 @@
                -class Old {}
                diff --git a/frontend/entrypoint.sh b/frontend/entrypoint.sh
                new file mode 100755
                index 0000000000000000000000000000000000000000..%s
                --- /dev/null
                +++ b/frontend/entrypoint.sh
                @@ -0,0 +1,1 @@
                +#!/bin/sh
                diff --git a/empty.txt b/empty.txt
                new file mode 100644
                index 0000000000000000000000000000000000000000..e69de29bb2d1d6434b8b29ae775ad8c2e48c5391
                """.formatted(GitPatchWriter.blobId(utf8("class New {}\n")), GitPatchWriter.blobId(utf8("class Old {}\n")),
                GitPatchWriter.blobId(utf8("#!/bin/sh\n"))));
    }

    @Test
    void aMissingFinalNewlineIsSaid() {
        GitPatchWriter patch = new GitPatchWriter();
        patch.changed("f", utf8("a\nb"), utf8("a\nb\n"));
        assertThat(patch.toString()).endsWith("""
                @@ -1,2 +1,2 @@
                 a
                -b
                \\ No newline at end of file
                +b
                """);
    }

    @Test
    void everyPatchAppliesToWhatItWasMadeFrom() {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            String before = randomText(random);
            String after = round % 4 == 0 ? randomText(random) : mutate(random, before);
            if (before.equals(after)) continue;
            GitPatchWriter patch = new GitPatchWriter();
            patch.changed("f", utf8(before), utf8(after));
            assertThat(apply(before, patch.toString())).as("round %d\n%s", round, patch).isEqualTo(after);
        }
    }

    @Test
    void gitAppliesWhatIsWritten(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git is not on the PATH");
        Random random = new Random(11);
        GitPatchWriter patch = new GitPatchWriter();
        List<String[]> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String path = "dir" + (i % 3) + "/file" + i + ".txt";
            String before = randomText(random);
            String after = mutate(random, before);
            Files.createDirectories(repo.resolve(path).getParent());
            Files.writeString(repo.resolve(path), before);
            if (before.equals(after)) continue;
            patch.changed(path, utf8(before), utf8(after));
            expected.add(new String[] {path, after});
        }
        Files.writeString(repo.resolve("gone.txt"), "going\naway\n");
        patch.deleted("gone.txt", utf8("going\naway\n"));
        patch.added("fresh/new.txt", utf8("brand\nnew\n"));
        patch.added("fresh/empty.txt", new byte[0]);
        Path patchFile = repo.resolveSibling(repo.getFileName() + ".patch");
        Files.writeString(patchFile, patch.toString());
        try {
            git(repo, "init", "-q");
            git(repo, "-c", "core.autocrlf=false", "apply", "--check", patchFile.toString());
            git(repo, "-c", "core.autocrlf=false", "apply", patchFile.toString());
            for (String[] e : expected) assertThat(Files.readString(repo.resolve(e[0]))).as(e[0]).isEqualTo(e[1]);
            assertThat(repo.resolve("gone.txt")).doesNotExist();
            assertThat(Files.readString(repo.resolve("fresh/new.txt"))).isEqualTo("brand\nnew\n");
            assertThat(repo.resolve("fresh/empty.txt")).isEmptyFile();
        } finally {
            Files.deleteIfExists(patchFile);
        }
    }

    // ── A strict applier: every context and removed line must be exactly where the hunk says ──

    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@");

    static String apply(String before, String patch) {
        List<String> source = LineDiff.lines(before);
        List<String> out = new ArrayList<>();
        int at = 0; // lines of the source consumed
        String[] lines = patch.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher hunk = HUNK.matcher(lines[i]);
            if (!hunk.matches()) continue;
            int oldCount = Integer.parseInt(hunk.group(2));
            int start = Integer.parseInt(hunk.group(1)) - (oldCount == 0 ? 0 : 1);
            int newCount = Integer.parseInt(hunk.group(4));
            while (at < start) out.add(source.get(at++));
            int seenOld = 0;
            int seenNew = 0;
            while (seenOld < oldCount || seenNew < newCount) {
                String line = lines[++i];
                String text = line.substring(1) + "\n";
                if (i + 1 < lines.length && lines[i + 1].startsWith("\\")) {
                    text = line.substring(1);
                    i++;
                }
                char sign = line.charAt(0);
                if (sign != '+') {
                    assertThat(source.get(at)).as("line %d of the source", at + 1).isEqualTo(text);
                    at++;
                    seenOld++;
                }
                if (sign != '-') {
                    out.add(text);
                    seenNew++;
                }
            }
        }
        while (at < source.size()) out.add(source.get(at++));
        return String.join("", out);
    }

    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int count = random.nextInt(30);
        for (int i = 0; i < count; i++) text.append("line ").append(random.nextInt(8)).append('\n');
        // Now and then, no newline at the end.
        if (count > 0 && random.nextInt(5) == 0) text.setLength(text.length() - 1);
        return text.toString();
    }

    private static String mutate(Random random, String source) {
        StringBuilder out = new StringBuilder();
        for (String line : LineDiff.lines(source)) {
            int roll = random.nextInt(12);
            if (roll == 0) continue;
            if (roll == 1) out.append("changed ").append(random.nextInt(8)).append('\n');
            else out.append(line.endsWith("\n") ? line : line + "\n");
            if (roll == 2) out.append("added ").append(random.nextInt(8)).append('\n');
        }
        if (!out.isEmpty() && random.nextInt(6) == 0) out.setLength(out.length() - 1);
        return out.toString();
    }

    // ── git ──────────────────────────────────────────────────────────────────

    public static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Runs git in {@code dir}; fails with its output unless it exits 0. Returns the output. */
    public static String git(Path dir, String... args) throws IOException, InterruptedException {
        return git(dir, 0, args);
    }

    static String git(Path dir, int expectedExit, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectInput(new File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null"))
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(p.exitValue()).as("git %s\n%s", String.join(" ", args), output).isEqualTo(expectedExit);
        return output;
    }
}
