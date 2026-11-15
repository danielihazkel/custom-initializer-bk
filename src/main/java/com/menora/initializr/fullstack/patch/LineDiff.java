package com.menora.initializr.fullstack.patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A line diff: the shortest list of kept, removed and added lines that turns one text into
 * another (Myers' O(ND) algorithm). Written here because the build is offline and carries no
 * diff library.
 *
 * <p>Lines are compared as they are — callers that care about a missing final newline split
 * with the terminator kept ({@link #lines}), so {@code "x\n"} and {@code "x"} differ.
 */
public final class LineDiff {

    private LineDiff() {}

    public enum Op { KEEP, DELETE, INSERT }

    /** One line of the result. */
    public record Edit(Op op, String line) {}

    /**
     * Past this many differing lines the search is abandoned (its memory grows with the square of
     * the distance) and the differing stretch is reported as removed then added — a correct
     * diff, just not the shortest.
     */
    static final int MAX_DISTANCE = 1500;

    /** Splits text into lines, each keeping its {@code \n} — only the last can lack one. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl + 1;
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    public static List<Edit> diff(List<String> a, List<String> b) {
        // What both ends share is never part of the answer; searching only the middle keeps the
        // usual case (a few changed lines in a long file) cheap.
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;

        List<Edit> edits = new ArrayList<>(Math.max(a.size(), b.size()) + 8);
        for (int i = 0; i < prefix; i++) edits.add(new Edit(Op.KEEP, a.get(i)));
        edits.addAll(middle(a.subList(prefix, a.size() - suffix), b.subList(prefix, b.size() - suffix)));
        for (int i = a.size() - suffix; i < a.size(); i++) edits.add(new Edit(Op.KEEP, a.get(i)));
        return edits;
    }

    private static List<Edit> middle(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        if (n == 0 && m == 0) return List.of();
        if (n == 0 || m == 0) return replaced(a, b);

        int max = Math.min(n + m, MAX_DISTANCE);
        // v[k + offset] = the furthest x reached on diagonal k (x - y = k) so far.
        int offset = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        search:
        for (int d = 0; d <= max; d++) {
            // What v held before this round, for diagonals -d-1..d+1: the way back.
            int[] before = new int[2 * d + 3];
            System.arraycopy(v, offset - d - 1, before, 0, 2 * d + 3);
            trace.add(before);
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]))
                        ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break search;
                }
            }
        }
        if (found < 0) return replaced(a, b);

        List<Edit> reversed = new ArrayList<>(n + m);
        int x = n;
        int y = m;
        for (int d = found; d > 0; d--) {
            int[] before = trace.get(d);
            int at = d + 1;
            int k = x - y;
            int prevK = (k == -d || (k != d && before[at + k - 1] < before[at + k + 1])) ? k + 1 : k - 1;
            int prevX = before[at + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                reversed.add(new Edit(Op.KEEP, a.get(--x)));
                y--;
            }
            if (x == prevX) reversed.add(new Edit(Op.INSERT, b.get(--y)));
            else reversed.add(new Edit(Op.DELETE, a.get(--x)));
        }
        while (x > 0 && y > 0) {
            reversed.add(new Edit(Op.KEEP, a.get(--x)));
            y--;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static List<Edit> replaced(List<String> a, List<String> b) {
        List<Edit> edits = new ArrayList<>(a.size() + b.size());
        for (String line : a) edits.add(new Edit(Op.DELETE, line));
        for (String line : b) edits.add(new Edit(Op.INSERT, line));
        return edits;
    }
}
