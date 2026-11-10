package com.menora.initializr.fullstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.FullstackExampleRequests;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code fullstack/reserved-type-names.txt} honest: derives, from the fullstack templates
 * themselves, the entity names whose generated code could not compile or would overwrite a fixed
 * file, and requires every one of them to be reserved.
 *
 * <p>What is derived, per template that uses an entity's type by its bare name:
 * <ul>
 *   <li><b>types</b> — every type the file names unqualified (imports, annotations, nested
 *       declarations). An entity called {@code Page} cannot share a file with Spring's;</li>
 *   <li><b>derived names</b> — a fixed name that a per-entity one would spell: the file declares
 *       {@code use{{EntityName}}} and imports {@code useState}, so {@code State} is taken;</li>
 *   <li><b>paths</b> — a fixed file under a directory a per-entity path would also write
 *       ({@code src/pages/{{entityNameKebab}}/} and {@code src/pages/dashboard/});</li>
 *   <li><b>beans</b> — a fixed {@code XController}/{@code XService}/{@code XRepository}, whose
 *       bean name entity {@code X}'s own class would take.</li>
 * </ul>
 * Run with {@code -Dreserved.print=true} to print the derived list grouped by reason.
 */
class ReservedNamesGuardTest {

    private static final String BASE = "templates/fullstack/";
    private static final List<String> BACKEND_SETS = List.of("spring-jpa-crud", "spring-jpa-crud-lombok");
    private static final List<String> FRONTEND_SETS = List.of("react-tailwind-crud", "react-menora-digital-crud");

    /** The Mustache names that render as an entity's class name. */
    private static final String ENTITY_TAG = "(?:EntityName|targetEntity|childEntity|parentEntityName|childEntityName)";
    private static final char TAG = '§';
    /** An entity's class name standing alone — not {@code {{EntityName}}Dto}, not {@code use{{EntityName}}}. */
    private static final Pattern BARE_ENTITY = Pattern.compile(
            "(?<![\\w" + TAG + "])\\{\\{\\{?" + ENTITY_TAG + "\\}?\\}\\}(?![\\w" + TAG + "{])");

    private record Template(String set, String source, String path, boolean perEntity, String text) {
        boolean java() {
            return source.endsWith(".java.mustache") || source.endsWith(".java");
        }

        boolean script() {
            return source.matches(".*\\.(ts|tsx)(\\.mustache)?");
        }
    }

    @Test
    void everyNameTheTemplatesClashWithIsReserved() throws Exception {
        Map<String, Set<String>> derived = derive();
        if (Boolean.getBoolean("reserved.print")) {
            derived.forEach((reason, names) -> {
                System.out.println("# " + reason);
                names.forEach(System.out::println);
                System.out.println();
            });
        }
        Set<String> reserved = ReservedNames.all();
        Map<String, Set<String>> missing = new TreeMap<>();
        derived.forEach((reason, names) -> names.forEach(n -> {
            if (!reserved.contains(n.toLowerCase(Locale.ROOT))) {
                missing.computeIfAbsent(reason, r -> new TreeSet<>()).add(n);
            }
        }));
        assertThat(missing).as("Entity names a template now clashes with but "
                + ReservedNames.RESOURCE + " does not reserve (add them under their heading)").isEmpty();
    }

    @Test
    void noBundledExampleUsesAReservedName() throws Exception {
        for (Map<String, Object> example : FullstackExampleRequests.examples()) {
            for (Object e : (List<?>) example.get("entities")) {
                String name = String.valueOf(((Map<?, ?>) e).get("name"));
                assertThat(ReservedNames.reasonFor(name))
                        .as(example.get("exampleId") + "." + name).isNull();
            }
        }
    }

    @Test
    void reservesTheKnownOffenders() {
        for (String name : List.of("Page", "List", "Entity", "Table", "Column", "String", "Long", "Me",
                "CurrentUser", "Dashboard", "State", "Resource", "Permission", "page", "LOCALDATE")) {
            assertThat(ReservedNames.reasonFor(name)).as(name).isNotNull();
        }
        for (String name : List.of("Order", "Customer", "Tag", "Invoice", "User", "Task")) {
            assertThat(ReservedNames.reasonFor(name)).as(name).isNull();
        }
    }

    @Test
    void anEntityMayNotTakeAnotherEntitysGeneratedClass() {
        Set<String> names = Set.of("order", "orderdto", "orderline");
        assertThat(ReservedNames.ownerOfGeneratedClass("OrderDto", names)).isEqualTo("order");
        assertThat(ReservedNames.ownerOfGeneratedClass("OrderLine", names)).isNull();
        assertThat(ReservedNames.ownerOfGeneratedClass("Order", names)).isNull();
    }

    // ── Derivation ──────────────────────────────────────────────────────────────

    private static Map<String, Set<String>> derive() throws IOException {
        Set<String> types = new TreeSet<>();
        Set<String> derivedNames = new TreeSet<>();
        Set<String> paths = new TreeSet<>();
        Set<String> beans = new TreeSet<>();

        List<Template> all = new ArrayList<>();
        for (String set : BACKEND_SETS) all.addAll(templates(set));
        for (String set : FRONTEND_SETS) all.addAll(templates(set));

        for (Template t : all) {
            if (!t.java() && !t.script()) continue;
            String code = t.java() ? stripJava(t.text()) : stripScript(t.text());
            boolean usesBareEntity = BARE_ENTITY.matcher(t.java() ? withoutQualifiedImports(code) : code).find();
            Set<String> fixed = t.java() ? javaNames(code) : scriptNames(code);
            if (usesBareEntity) {
                for (String name : fixed) if (Character.isUpperCase(name.charAt(0))) types.add(name);
            }
            // {{EntityName}}Suffix / prefix{{EntityName}} against the file's fixed names.
            Matcher m = Pattern.compile("([A-Za-z0-9_]*)\\{\\{\\{?" + ENTITY_TAG + "\\}?\\}\\}([A-Za-z0-9_]*)").matcher(code);
            while (m.find()) {
                String prefix = m.group(1), suffix = m.group(2);
                if (prefix.isEmpty() && suffix.isEmpty()) continue;
                for (String name : fixed) {
                    if (name.length() > prefix.length() + suffix.length()
                            && name.startsWith(prefix) && name.endsWith(suffix)) {
                        String middle = name.substring(prefix.length(), name.length() - suffix.length());
                        if (Character.isUpperCase(middle.charAt(0)) && middle.matches("[A-Za-z0-9]+")) {
                            derivedNames.add(middle);
                        }
                    }
                }
            }
        }

        // Paths: a per-entity directory against the fixed files of the same set.
        for (String set : FRONTEND_SETS) {
            List<Template> ts = templates(set);
            for (Template perEntity : ts) {
                if (!perEntity.path().contains("{{entityNameKebab}}")) continue;
                String[] segments = perEntity.path().split("/");
                int at = 0;
                while (!segments[at].contains("{{entityNameKebab}}")) at++;
                StringBuilder regex = new StringBuilder("^");
                for (int i = 0; i <= at; i++) {
                    if (i > 0) regex.append('/');
                    regex.append(Pattern.quote(segments[i]).replace("{{entityNameKebab}}", "\\E([a-z0-9-]+)\\Q"));
                }
                regex.append("(?:/|$)");
                Pattern dir = Pattern.compile(regex.toString());
                for (Template other : ts) {
                    if (other.path().contains("{{")) continue;
                    Matcher m = dir.matcher(other.path());
                    if (m.find()) paths.add(pascalOfKebab(m.group(1)));
                }
            }
        }

        // Beans: the fixed stereotype classes of the sets, and of the catalog's default dependencies.
        Pattern stereotype = Pattern.compile("([A-Z][A-Za-z0-9]*)(?:Controller|Service|Repository)\\.java$");
        for (String set : BACKEND_SETS) {
            for (Template t : templates(set)) {
                if (t.path().contains("{{EntityName}}")) continue;
                Matcher m = stereotype.matcher(t.path());
                if (m.find()) beans.add(m.group(1));
            }
        }
        JsonNode catalog = new ObjectMapper().readTree(read("catalog/file-contributions.json"));
        Set<String> defaultDeps = defaultDeps();
        for (JsonNode row : catalog) {
            if (!defaultDeps.contains(row.path("depId").asText()) || row.hasNonNull("subOptionId")) continue;
            Matcher m = stereotype.matcher(row.path("targetPath").asText(""));
            if (m.find()) beans.add(m.group(1));
        }

        Map<String, Set<String>> result = new LinkedHashMap<>();
        result.put("a type the generated code already uses", types);
        result.put("a name one of the entity's own generated names would spell", derivedNames);
        result.put("a folder of the generated frontend", paths);
        result.put("a class the project already has, whose bean name the entity's would take", beans);
        return result;
    }

    /** The dependencies every default fullstack backend carries: {@code __common__} and the sets' defaults. */
    private static Set<String> defaultDeps() throws IOException {
        Set<String> deps = new TreeSet<>(Set.of("__common__"));
        for (String set : BACKEND_SETS) {
            for (JsonNode d : new ObjectMapper().readTree(read(BASE + set + "/manifest.json")).path("defaultDeps")) {
                deps.add(d.asText());
            }
        }
        // Either ldap variant may stand in for the default one.
        deps.add("ldap-auth");
        deps.add("ldap-auth-rest");
        return deps;
    }

    private static List<Template> templates(String set) throws IOException {
        JsonNode root = new ObjectMapper().readTree(read(BASE + set + "/manifest.json"));
        List<Template> result = new ArrayList<>();
        for (JsonNode f : root.get("files")) {
            String dir = BASE + (f.hasNonNull("sourceSet") ? f.get("sourceSet").asText() : set) + "/";
            String source = f.get("source").asText();
            result.add(new Template(set, source, f.get("path").asText(),
                    f.path("perEntity").asBoolean(false), read(dir + source)));
        }
        return result;
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    /** Java code with comments and string literals removed and every other Mustache tag as one marker. */
    private static String stripJava(String text) {
        String s = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("(?m)//.*$", " ");
        s = s.replaceAll("\"\"\"(?s).*?\"\"\"", "\"\"");
        s = s.replaceAll("\"(?:\\\\.|[^\"\\\\\\n])*\"", "\"\"");
        return markTags(s);
    }

    /** TypeScript/TSX with comments and string literals removed. JSX text stays: it holds no imports. */
    private static String stripScript(String text) {
        String s = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("(?m)(?<![:\"'`])//.*$", " ");
        return markTags(s);
    }

    /** Every Mustache tag but the entity-name ones becomes a marker, so its neighbours are not read as names. */
    private static String markTags(String s) {
        s = s.replaceAll("\\{\\{\\{?(?!" + ENTITY_TAG + "\\}?\\}\\})[^}]*\\}?\\}\\}", String.valueOf(TAG));
        return s;
    }

    /**
     * Java code without the imports that only reach through an entity ({@code import pkg.Order.Status;}
     * brings {@code Status} into scope, not {@code Order}); an import of the entity itself stays.
     */
    private static String withoutQualifiedImports(String code) {
        return code.replaceAll("(?m)^.*\\bimport\\s[^;\\n]*\\}\\}\\.[^;\\n]*;", " ");
    }

    /** Types a Java file names unqualified: imports, and every capitalized identifier in its code. */
    private static Set<String> javaNames(String code) {
        Set<String> names = new TreeSet<>();
        Matcher imports = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?[\\w.]+\\.([A-Z]\\w*)\\s*;").matcher(code);
        while (imports.find()) names.add(imports.group(1));
        String body = code.replaceAll("(?m)^\\s*(?:import|package)\\s.*$", " ");
        Matcher tokens = Pattern.compile("(?<![.\\w" + TAG + "}])([A-Z][A-Za-z0-9]*)(?![\\w" + TAG + "{])").matcher(body);
        while (tokens.find()) {
            String token = tokens.group(1);
            if (token.chars().anyMatch(Character::isLowerCase)) names.add(token);
        }
        return names;
    }

    /** Names a TypeScript file imports or declares. */
    private static Set<String> scriptNames(String code) {
        Set<String> names = new TreeSet<>();
        Matcher named = Pattern.compile("import\\s+(?:type\\s+)?(?:([A-Za-z_$][\\w$]*)\\s*,?\\s*)?(?:\\{([^}]*)\\})?\\s*from").matcher(code);
        while (named.find()) {
            if (named.group(1) != null) names.add(named.group(1));
            if (named.group(2) != null) {
                for (String part : named.group(2).split(",")) {
                    String p = part.trim().replaceFirst("^type\\s+", "");
                    if (p.isEmpty() || p.indexOf(TAG) >= 0 || p.contains("{{")) continue;
                    String local = p.contains(" as ") ? p.substring(p.indexOf(" as ") + 4).trim() : p;
                    if (local.matches("[A-Za-z_$][\\w$]*")) names.add(local);
                }
            }
        }
        Matcher star = Pattern.compile("import\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)").matcher(code);
        while (star.find()) names.add(star.group(1));
        Matcher declared = Pattern.compile("(?<![\\w" + TAG + "}])(?:function|const|let|type|interface|class|enum)\\s+([A-Za-z_$][\\w$]*)(?![\\w" + TAG + "{])").matcher(code);
        while (declared.find()) names.add(declared.group(1));
        // A constant (OPEN, PAGE_SIZE) never collides with a class name's spelling.
        names.removeIf(n -> n.contains("_") || n.chars().noneMatch(Character::isLowerCase));
        return names;
    }

    private static String pascalOfKebab(String kebab) {
        StringBuilder out = new StringBuilder();
        for (String part : kebab.split("-")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
