package com.menora.initializr.fullstack.patch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LineDiffTest {

    /** The first text, as the edits keep and remove it; the second, as they keep and add it. */
    private static List<String> before(List<LineDiff.Edit> edits) {
        return edits.stream().filter(e -> e.op() != LineDiff.Op.INSERT).map(LineDiff.Edit::line).toList();
    }

    private static List<String> after(List<LineDiff.Edit> edits) {
        return edits.stream().filter(e -> e.op() != LineDiff.Op.DELETE).map(LineDiff.Edit::line).toList();
    }

    private static long changes(List<LineDiff.Edit> edits) {
        return edits.stream().filter(e -> e.op() != LineDiff.Op.KEEP).count();
    }

    @Test
    void splitsLinesKeepingTheirTerminators() {
        assertThat(LineDiff.lines("")).isEmpty();
        assertThat(LineDiff.lines("a\nb\n")).containsExactly("a\n", "b\n");
        // Only the last line can lack its newline, and then it is a different line.
        assertThat(LineDiff.lines("a\nb")).containsExactly("a\n", "b");
        assertThat(LineDiff.lines("\n\n")).containsExactly("\n", "\n");
    }

    @Test
    void findsTheShortestEdit() {
        List<String> a = List.of("a", "b", "c", "a", "b", "b", "a");
        List<String> b = List.of("c", "b", "a", "b", "a", "c");
        List<LineDiff.Edit> edits = LineDiff.diff(a, b);
        assertThat(before(edits)).isEqualTo(a);
        assertThat(after(edits)).isEqualTo(b);
        // Myers' own example: five changes, no fewer.
        assertThat(changes(edits)).isEqualTo(5);
    }

    @Test
    void keepsWhatDidNotChange() {
        List<String> a = List.of("one", "two", "three", "four");
        assertThat(LineDiff.diff(a, a)).allMatch(e -> e.op() == LineDiff.Op.KEEP).hasSize(4);
        assertThat(LineDiff.diff(List.of(), List.of())).isEmpty();
        assertThat(LineDiff.diff(List.of(), a)).allMatch(e -> e.op() == LineDiff.Op.INSERT).hasSize(4);
        assertThat(LineDiff.diff(a, List.of())).allMatch(e -> e.op() == LineDiff.Op.DELETE).hasSize(4);

        List<LineDiff.Edit> one = LineDiff.diff(a, List.of("one", "two", "2.5", "three", "four"));
        assertThat(one).extracting(LineDiff.Edit::op).containsExactly(LineDiff.Op.KEEP, LineDiff.Op.KEEP,
                LineDiff.Op.INSERT, LineDiff.Op.KEEP, LineDiff.Op.KEEP);
    }

    @Test
    void everyDiffTurnsTheFirstTextIntoTheSecond() {
        Random random = new Random(20261111);
        for (int round = 0; round < 400; round++) {
            List<String> a = randomLines(random, random.nextInt(40));
            // A text of its own, or the first with a few lines dropped, changed and added.
            List<String> b = round % 3 == 0 ? randomLines(random, random.nextInt(40)) : mutated(random, a);
            List<LineDiff.Edit> edits = LineDiff.diff(a, b);
            assertThat(before(edits)).as("round %d", round).isEqualTo(a);
            assertThat(after(edits)).as("round %d", round).isEqualTo(b);
            // Never worse than throwing the first away and writing the second.
            assertThat(changes(edits)).isLessThanOrEqualTo((long) a.size() + b.size());
        }
    }

    @Test
    void aTextTooDifferentToSearchIsStillDiffedCorrectly() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        a.add("same first line");
        b.add("same first line");
        for (int i = 0; i < LineDiff.MAX_DISTANCE; i++) {
            a.add("old " + i);
            b.add("new " + i);
        }
        a.add("same last line");
        b.add("same last line");
        List<LineDiff.Edit> edits = LineDiff.diff(a, b);
        assertThat(before(edits)).isEqualTo(a);
        assertThat(after(edits)).isEqualTo(b);
        assertThat(edits.get(0).op()).isEqualTo(LineDiff.Op.KEEP);
        assertThat(edits.get(edits.size() - 1).op()).isEqualTo(LineDiff.Op.KEEP);
    }

    private static List<String> randomLines(Random random, int count) {
        List<String> lines = new ArrayList<>();
        // A small alphabet, so lines repeat and the search has real choices to make.
        for (int i = 0; i < count; i++) lines.add("line " + random.nextInt(6));
        return lines;
    }

    private static List<String> mutated(Random random, List<String> source) {
        List<String> out = new ArrayList<>();
        for (String line : source) {
            int roll = random.nextInt(10);
            if (roll == 0) continue;
            if (roll == 1) out.add("changed " + random.nextInt(6));
            else out.add(line);
            if (roll == 2) out.add("added " + random.nextInt(6));
        }
        return out;
    }
}
