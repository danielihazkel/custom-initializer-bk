package com.menora.initializr;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An entity's generated list page, as the tests read it: the view ({@code pages/<e>/ui/<E>Page.tsx},
 * which each frontend set draws its own way) followed by the hook it runs on
 * ({@code pages/<e>/model/use<E>List.tsx}, shared by the sets). What the page does is in one or the
 * other, so an assertion about the page reads both.
 */
final class ListPages {

    private static final Pattern PAGE = Pattern.compile("(.*/)ui/(\\w+)Page\\.tsx$");

    private ListPages() {}

    /**
     * The page at {@code path} — a key of {@code files}, or the end of one — and its hook; null when
     * there is no such page.
     */
    static String of(Map<String, String> files, String path) {
        String page = find(files, path);
        if (page == null) return null;
        Matcher m = PAGE.matcher(path);
        if (!m.matches()) throw new IllegalArgumentException("Not an entity list page: " + path);
        String hook = find(files, m.group(1) + "model/use" + m.group(2) + "List.tsx");
        return hook == null ? page : page + "\n" + hook;
    }

    private static String find(Map<String, String> files, String path) {
        String exact = files.get(path);
        if (exact != null) return exact;
        return files.entrySet().stream().filter(e -> e.getKey().endsWith(path)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
