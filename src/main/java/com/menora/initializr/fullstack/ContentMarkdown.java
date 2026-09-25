package com.menora.initializr.fullstack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small Markdown subset a {@code content} page is written in, read at generation time so the
 * generated screen holds plain JSX — every piece of text a quoted string, no HTML injected at run
 * time and no parser shipped to the browser.
 *
 * <p>Blocks, separated by blank lines: {@code #}/{@code ##}/{@code ###} headings, paragraphs,
 * {@code -}/{@code *} bullet lists, {@code 1.} numbered lists, {@code >} callouts and a
 * {@code ---} rule. Inline: {@code **bold**}, {@code *em*}, {@code `code`} and
 * {@code [text](target)}, where the target is another page ({@code #/id} or {@code page:id}) or an
 * {@code http(s)://} / {@code mailto:} address. A marker without its closing half is plain text.
 */
public final class ContentMarkdown {

    public enum BlockKind { HEADING, PARAGRAPH, CALLOUT, BULLETS, NUMBERS, RULE }

    public enum RunKind { TEXT, BOLD, EM, CODE, PAGE_LINK, LINK }

    /** One stretch of inline text. {@code target} is the page id of a {@link RunKind#PAGE_LINK}
     *  and the address of a {@link RunKind#LINK}; null otherwise. */
    public record Run(RunKind kind, String text, String target) {}

    /**
     * @param level a heading's level, 1–3
     * @param items the block's inline content: one entry for a heading, paragraph or callout, one per
     *              item for a list, none for a rule
     */
    public record Block(BlockKind kind, int level, List<List<Run>> items) {
        public Block {
            items = List.copyOf(items);
        }
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^[-*]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\d{1,3}[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^-{3,}$");
    private static final Pattern PAGE_ID = FullstackPageValidator.PAGE_ID;

    private ContentMarkdown() {}

    public static List<Block> parse(String body) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> paragraph = new ArrayList<>();
        List<String> callout = new ArrayList<>();
        List<List<Run>> items = new ArrayList<>();
        BlockKind listKind = null;
        for (String raw : lines) {
            String line = raw.strip();
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            BlockKind itemKind = RULE.matcher(line).matches() ? null
                    : bullet.matches() ? BlockKind.BULLETS : numbered.matches() ? BlockKind.NUMBERS : null;
            // A block ends where a line of another kind (or a blank one) starts.
            if (listKind != null && itemKind != listKind) {
                blocks.add(new Block(listKind, 0, items));
                items = new ArrayList<>();
                listKind = null;
            }
            if (!callout.isEmpty() && !line.startsWith(">")) {
                blocks.add(new Block(BlockKind.CALLOUT, 0, List.of(inline(String.join(" ", callout)))));
                callout.clear();
            }
            boolean paragraphLine = !line.isEmpty() && itemKind == null && !line.startsWith(">")
                    && !HEADING.matcher(line).matches() && !RULE.matcher(line).matches();
            if (!paragraph.isEmpty() && !paragraphLine) {
                blocks.add(new Block(BlockKind.PARAGRAPH, 0, List.of(inline(String.join(" ", paragraph)))));
                paragraph.clear();
            }
            if (line.isEmpty()) continue;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                blocks.add(new Block(BlockKind.HEADING, heading.group(1).length(), List.of(inline(heading.group(2).strip()))));
            } else if (RULE.matcher(line).matches()) {
                blocks.add(new Block(BlockKind.RULE, 0, List.of()));
            } else if (itemKind != null) {
                listKind = itemKind;
                items.add(inline((itemKind == BlockKind.BULLETS ? bullet.group(1) : numbered.group(1)).strip()));
            } else if (line.startsWith(">")) {
                callout.add(line.substring(1).strip());
            } else {
                paragraph.add(line);
            }
        }
        if (listKind != null) blocks.add(new Block(listKind, 0, items));
        if (!callout.isEmpty()) blocks.add(new Block(BlockKind.CALLOUT, 0, List.of(inline(String.join(" ", callout)))));
        if (!paragraph.isEmpty()) blocks.add(new Block(BlockKind.PARAGRAPH, 0, List.of(inline(String.join(" ", paragraph)))));
        return blocks;
    }

    /** The page a link target names ({@code #/orders} or {@code page:orders}), or null when it names none. */
    public static String pageIdOf(String target) {
        String id = target.startsWith("#/") ? target.substring(2) : target.regionMatches(true, 0, "page:", 0, 5) ? target.substring(5) : null;
        return id != null && PAGE_ID.matcher(id).matches() ? id : null;
    }

    /** Whether a link target is an address the generated page may open: http(s) or mailto. */
    public static boolean isExternal(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return (lower.startsWith("https://") || lower.startsWith("http://")) && target.length() > (lower.startsWith("https") ? 8 : 7)
                || lower.startsWith("mailto:") && target.length() > 7;
    }

    static List<Run> inline(String text) {
        List<Run> runs = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int end;
            if (c == '`' && (end = text.indexOf('`', i + 1)) > i + 1) {
                flush(runs, plain);
                runs.add(new Run(RunKind.CODE, text.substring(i + 1, end), null));
                i = end + 1;
            } else if (text.startsWith("**", i) && (end = text.indexOf("**", i + 2)) > i + 2) {
                flush(runs, plain);
                runs.add(new Run(RunKind.BOLD, text.substring(i + 2, end), null));
                i = end + 2;
            } else if (c == '*' && (end = text.indexOf('*', i + 1)) > i + 1) {
                flush(runs, plain);
                runs.add(new Run(RunKind.EM, text.substring(i + 1, end), null));
                i = end + 1;
            } else if (c == '[' && (end = text.indexOf("](", i + 1)) > i + 1 && text.indexOf(')', end + 2) > end + 2) {
                int close = text.indexOf(')', end + 2);
                String label = text.substring(i + 1, end);
                String target = text.substring(end + 2, close).strip();
                flush(runs, plain);
                String page = pageIdOf(target);
                runs.add(page != null ? new Run(RunKind.PAGE_LINK, label, page) : new Run(RunKind.LINK, label, target));
                i = close + 1;
            } else {
                plain.append(c);
                i++;
            }
        }
        flush(runs, plain);
        return runs;
    }

    private static void flush(List<Run> runs, StringBuilder plain) {
        if (!plain.isEmpty()) {
            runs.add(new Run(RunKind.TEXT, plain.toString(), null));
            plain.setLength(0);
        }
    }
}
