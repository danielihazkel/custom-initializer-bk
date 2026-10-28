package com.menora.initializr.design;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules for a design kit's own component files (P3). A kit may replace a <b>slot</b> — one of
 * its base set's plain {@code .tsx} components under {@code src/shared/ui/} — and add
 * <b>helpers</b> in a folder of its own under {@code src/shared/ui/} ({@code kit/}, {@code menora/}, …
 * — the base sets keep no folders there): components and modules ({@code .tsx}, {@code .ts}) and
 * stylesheets ({@code .css}, which the kit's manifest imports). The generator's glue (screens, shell, entity
 * pages) imports the slots by name, so a slot keeps exactly the exports of the file it replaces;
 * the code is copied into every zip generated with the kit and runs in the preview runtime, so it
 * may import only the bundled packages and the project's own {@code src/shared}, and may not reach
 * the network, evaluate code or write raw HTML. Types are not checked here: the Studio's preview
 * compiles every file, and a kit can be tried on the examples before it is published.
 */
public final class KitSourceValidator {

    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_FILES = 200;

    /** Where a slot lives: a component of the base set. */
    public static final Pattern SLOT = Pattern.compile("^src/shared/ui/[A-Za-z0-9_-]+\\.tsx$");
    /** Where a kit's own helpers live: a folder of its own under src/shared/ui/. */
    public static final Pattern HELPER = Pattern.compile("^src/shared/ui/[a-z][a-z0-9_-]*/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.(?:tsx|ts|css)$");

    /** Bare imports a kit file may use: what the generated package.json installs and the runtime bundles. */
    public static final List<String> PACKAGES = List.of("react", "react-dom", "react-dom/client", "react/jsx-runtime", "lucide-react", "clsx", "sonner");

    /** What kit code may not do, with the reason shown to the author. */
    static final Map<Pattern, String> BANNED = new LinkedHashMap<>();

    static {
        BANNED.put(Pattern.compile("\\bfetch\\s*\\("), "calls fetch() — components get their data from the generator's hooks (@shared/api)");
        BANNED.put(Pattern.compile("\\b(?:XMLHttpRequest|WebSocket|EventSource)\\b|\\bsendBeacon\\b"), "opens a network connection");
        BANNED.put(Pattern.compile("\\bimport\\s*\\("), "imports a module at run time (import())");
        BANNED.put(Pattern.compile("\\beval\\s*\\(|\\bnew\\s+Function\\b|(?<![\\w.])Function\\s*\\("), "evaluates code from a string");
        BANNED.put(Pattern.compile("dangerouslySetInnerHTML|\\.(?:inner|outer)HTML\\s*=|insertAdjacentHTML|document\\s*\\.\\s*write"), "writes raw HTML");
        BANNED.put(Pattern.compile("document\\s*\\.\\s*cookie"), "reads or writes cookies");
        BANNED.put(Pattern.compile("\\bwindow\\s*\\.\\s*(?:top|parent|opener)\\b"), "reaches another window");
    }

    // `import` not inside a word and not right after a quote: t('import') is a string, not a statement.
    private static final Pattern IMPORT = Pattern.compile(
            "(?:(?<![\\w.$'\"`])import\\b[^'\";]*?\\bfrom\\s*|\\bexport\\b[^'\";]*?\\bfrom\\s*|(?<![\\w.$'\"`])import\\s*|\\brequire\\s*\\(\\s*)['\"]([^'\"]+)['\"]");
    private static final Pattern EXPORT_DECL = Pattern.compile(
            "\\bexport\\s+(?:declare\\s+)?(?:default\\s+)?(?:async\\s+)?(?:function\\s*\\*?|const|let|var|class|interface|type|enum|abstract\\s+class)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern EXPORT_LIST = Pattern.compile("\\bexport\\s+(?:type\\s+)?\\{([^}]*)\\}");
    private static final Pattern EXPORT_DEFAULT = Pattern.compile("\\bexport\\s+default\\b(?!\\s+(?:async\\s+)?(?:function|class)\\s+[A-Za-z_$])");
    private static final Pattern EXPORT_STAR = Pattern.compile("\\bexport\\s*\\*\\s*from");

    private KitSourceValidator() {
    }

    /** A slot as the Studio lists it: its path, the names the generator's glue may import, and the
     *  flag that decides whether the base set writes it at all. */
    public record Slot(String path, List<String> exports, String gatedBy, int bytes) {
    }

    public enum Kind { SLOT, HELPER }

    /** What a path is in this base set, or null when a kit may not write it. */
    public static Kind kindOf(String path, Map<String, Slot> slots) {
        if (path == null) return null;
        if (slots.containsKey(path)) return Kind.SLOT;
        if (HELPER.matcher(path).matches()) return Kind.HELPER;
        return null;
    }

    /** The problems of one kit file, each naming the file. Empty: it may be saved. */
    public static List<String> check(String path, String content, Map<String, Slot> slots) {
        List<String> problems = new ArrayList<>();
        Kind kind = kindOf(path, slots);
        if (kind == null) {
            problems.add(path + ": a kit may replace a component of its base set (" + String.join(", ", slotNames(slots))
                    + ") or add a helper in a folder of its own under src/shared/ui/ (src/shared/ui/kit/…: .tsx, .ts or .css)");
            return problems;
        }
        if (content == null || content.isBlank()) {
            problems.add(path + ": the file is empty");
            return problems;
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            problems.add(path + ": larger than " + MAX_FILE_BYTES / 1024 + " KB");
        }
        if (path.endsWith(".css")) {
            // A stylesheet is spliced into nothing and runs nothing: it only must not reach out.
            TokenValidator.cssProblems(content).forEach(p -> problems.add(path + ": " + p));
            return problems;
        }
        String code = stripComments(content);
        for (Map.Entry<Pattern, String> banned : BANNED.entrySet()) {
            if (banned.getKey().matcher(code).find()) problems.add(path + ": " + banned.getValue());
        }
        Matcher m = IMPORT.matcher(code);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String spec = m.group(1);
            if (seen.add(spec) && !importAllowed(path, spec)) {
                problems.add(path + ": imports '" + spec + "' — a kit file may import " + String.join(", ", PACKAGES)
                        + ", @shared/… and relative paths inside src/shared");
            }
        }
        if (kind == Kind.SLOT) {
            Set<String> base = new LinkedHashSet<>(slots.get(path).exports());
            Set<String> own = exportsOf(content);
            List<String> missing = base.stream().filter(e -> !own.contains(e)).toList();
            List<String> extra = own.stream().filter(e -> !base.contains(e)).toList();
            if (!missing.isEmpty()) problems.add(path + ": the generated screens import " + String.join(", ", missing) + " from it — keep exporting them");
            if (!extra.isEmpty()) problems.add(path + ": exports " + String.join(", ", extra)
                    + ", which the file it replaces does not — put shared code in a helper under src/shared/ui/kit/");
        }
        return problems;
    }

    private static List<String> slotNames(Map<String, Slot> slots) {
        return slots.keySet().stream().map(p -> p.substring(p.lastIndexOf('/') + 1)).toList();
    }

    static boolean importAllowed(String from, String spec) {
        if (PACKAGES.contains(spec)) return true;
        if (spec.startsWith("@shared/") || spec.equals("@shared")) return !spec.contains("..");
        if (!spec.startsWith("./") && !spec.startsWith("../")) return false;
        List<String> parts = new ArrayList<>(List.of(from.split("/")));
        parts.remove(parts.size() - 1);
        for (String part : spec.split("/")) {
            if (part.equals("..")) {
                if (parts.isEmpty()) return false;
                parts.remove(parts.size() - 1);
            } else if (!part.equals(".") && !part.isEmpty()) {
                parts.add(part);
            }
        }
        String resolved = String.join("/", parts);
        return resolved.startsWith("src/shared/");
    }

    /** The names a module exports — declarations, export lists, `default`, `*`. */
    public static Set<String> exportsOf(String source) {
        String code = stripComments(source);
        Set<String> out = new LinkedHashSet<>();
        Matcher d = EXPORT_DECL.matcher(code);
        while (d.find()) {
            boolean isDefault = code.substring(d.start(), d.end()).matches("(?s)export\\s+(?:declare\\s+)?default\\b.*");
            out.add(isDefault ? "default" : d.group(1));
        }
        Matcher l = EXPORT_LIST.matcher(code);
        while (l.find()) {
            for (String item : l.group(1).split(",")) {
                String name = item.trim().replaceFirst("^type\\s+", "");
                if (name.isEmpty()) continue;
                String[] as = name.split("\\s+as\\s+");
                out.add(as[as.length - 1].trim());
            }
        }
        if (EXPORT_DEFAULT.matcher(code).find()) out.add("default");
        if (EXPORT_STAR.matcher(code).find()) out.add("*");
        return out;
    }

    /** The source without comments (string contents are kept), so a word in a comment is not code. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {
                int start = i++;
                while (i < n && source.charAt(i) != c) {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(n, i + 1);
                out.append(source, start, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
