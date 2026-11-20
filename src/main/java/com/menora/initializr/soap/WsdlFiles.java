package com.menora.initializr.soap;

import com.menora.initializr.soap.SoapCodeGenerator.SoapParseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.wsdl.xml.WSDLLocator;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The documents of one WSDL: the main one, and the files it is spread over — other WSDLs it
 * imports, the schemas they import, include or redefine — each under its path relative to the
 * main document ({@code schemas/types.xsd}).
 *
 * <p>The server reads nothing but these. A reference is looked up among the files by its path;
 * one that is an address, that leaves the main document's folder or that names a file nobody
 * gave is a problem the request is refused with — never something the server goes and opens.
 * Every document is read first by a parser that takes no DOCTYPE (a WSDL and a schema have none;
 * entities are how a document makes a parser read files), so wsdl4j's own, plain parser only
 * ever sees text that has passed it.
 *
 * @param main  the main document's text
 * @param files the other documents by relative path, sorted; empty for a WSDL in one document
 */
public record WsdlFiles(String main, SortedMap<String, String> files) {

    /** How many files one WSDL may come in, beside the main document. */
    public static final int MAX_FILES = 100;

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    /** One folder or file name of a path: what {@code GeneratedProjectFiles} calls a plain name. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}");
    private static final Pattern ADDRESS = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9+.-]*:|/|\\\\).*", Pattern.DOTALL);
    private static final int MAX_PATH = 200;

    /** Where the locator says the main document is: no file can have this name. */
    private static final String ROOT = "mem:/";
    private static final String MAIN_URI = ROOT + "~main";

    public WsdlFiles {
        files = Collections.unmodifiableSortedMap(new TreeMap<>(files == null ? Map.of() : files));
    }

    /** A WSDL in one document. */
    public static WsdlFiles of(String main) {
        return new WsdlFiles(main, null);
    }

    /**
     * The documents as a request gives them: names tidied ({@code \} and a leading {@code ./}),
     * and refused when one is not a path a project can hold.
     *
     * @throws SoapParseException naming every file that cannot be taken
     */
    public static WsdlFiles of(String main, Map<String, String> files) {
        if (files == null || files.isEmpty()) return of(main);
        List<String> problems = new ArrayList<>();
        if (files.size() > MAX_FILES) {
            throw new SoapParseException(List.of("A WSDL comes in " + files.size() + " files; the wizard takes "
                    + MAX_FILES + " beside the main document"));
        }
        SortedMap<String, String> tidy = new TreeMap<>();
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String given = file.getKey();
            String path = tidyPath(given);
            if (path == null) {
                problems.add("'" + given + "' is not a file name the wizard takes: a path relative to the main WSDL "
                        + "(schemas/types.xsd), of letters, digits, '-', '_' and '.', ending in .wsdl, .xsd or .xml");
                continue;
            }
            if (file.getValue() == null || file.getValue().isBlank()) {
                problems.add("'" + path + "' is empty");
                continue;
            }
            String same = byLowerCase.putIfAbsent(path.toLowerCase(Locale.ROOT), path);
            if (same != null) {
                problems.add("'" + path + "' and '" + same + "' are the same file on a disk that ignores case");
                continue;
            }
            tidy.put(path, file.getValue());
        }
        if (!problems.isEmpty()) throw new SoapParseException(problems);
        return new WsdlFiles(main, tidy);
    }

    /** Whether the WSDL is the main document alone. */
    public boolean alone() {
        return files.isEmpty();
    }

    /** The file with this path, however its name is cased; null when there is none. */
    public String pathIgnoringCase(String path) {
        for (String known : files.keySet()) {
            if (known.equalsIgnoreCase(path)) return known;
        }
        return null;
    }

    // ── Names ─────────────────────────────────────────────────────────────────

    private static String tidyPath(String given) {
        if (given == null) return null;
        String path = given.trim().replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        if (path.isEmpty() || path.length() > MAX_PATH) return null;
        for (String segment : path.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.contains("..")) return null;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".wsdl") || lower.endsWith(".xsd") || lower.endsWith(".xml") ? path : null;
    }

    /**
     * {@code location} as written in the document at {@code from} (null: the main document), as a
     * path from the main document's folder; null when it climbs out of that folder.
     */
    private static String resolve(String from, String location) {
        Deque<String> segments = new ArrayDeque<>();
        if (from != null) {
            String[] own = from.split("/");
            for (int i = 0; i < own.length - 1; i++) segments.addLast(own[i]);
        }
        for (String segment : location.trim().replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return null;
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return String.join("/", segments);
    }

    // ── What the documents refer to ───────────────────────────────────────────

    /**
     * What stands in the way of reading these documents: a reference to a file that was not
     * given, to an address or to somewhere outside the main document's folder, and a file that
     * is neither a WSDL nor a schema. Empty when every reference leads to one of the files.
     *
     * <p>An {@code xsd:import} of a namespace alone is fine: that schema is inline, in the same
     * {@code <types>}.
     *
     * @throws SoapParseException for a document that is not well-formed XML (or has a DOCTYPE)
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        references(null, parse(main, null), out);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Document document = parse(file.getValue(), file.getKey());
            Element root = document.getDocumentElement();
            boolean wsdl = WSDL_NS.equals(root.getNamespaceURI()) && "definitions".equals(root.getLocalName());
            boolean schema = XSD_NS.equals(root.getNamespaceURI()) && "schema".equals(root.getLocalName());
            if (!wsdl && !schema) {
                out.add("'" + file.getKey() + "' is neither a WSDL (wsdl:definitions) nor an XML schema (xsd:schema)");
                continue;
            }
            references(file.getKey(), document, out);
        }
        return out;
    }

    private void references(String from, Document document, List<String> out) {
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String name = element.getLocalName();
            if (WSDL_NS.equals(element.getNamespaceURI()) && "import".equals(name)) {
                check(from, "wsdl:import", element.getAttribute("location"), out);
            } else if (XSD_NS.equals(element.getNamespaceURI())
                    && ("import".equals(name) || "include".equals(name) || "redefine".equals(name))) {
                check(from, "xsd:" + name, element.getAttribute("schemaLocation"), out);
            }
        }
    }

    private void check(String from, String kind, String location, List<String> out) {
        if (location == null || location.isBlank()) return;
        String who = from == null ? "The WSDL" : "'" + from + "'";
        String reference = who + " refers to '" + location + "' (" + kind + "), ";
        if (ADDRESS.matcher(location.trim()).matches()) {
            out.add(reference + "an address. The wizard reads only the documents it is given: save that one, "
                    + "add it to the WSDL's files and refer to it by its file name.");
            return;
        }
        String path = resolve(from, location);
        if (path == null) {
            out.add(reference + "which is outside the folder of the main WSDL. Files are named from that folder "
                    + "and stay in it or below it.");
        } else if (!files.containsKey(path)) {
            out.add(reference + (alone() ? "which is not part of the document given. " : "which is not among the files given. ")
                    + "Add that file to the WSDL's files, or put the imported definitions or schema inline.");
        }
    }

    /**
     * {@code text} as a DOM, read without a DOCTYPE. {@code path} names the file in the message;
     * null is the main document.
     */
    private static Document parse(String text, String path) {
        String who = path == null ? "The WSDL" : "'" + path + "'";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(new StringReader(text)));
        } catch (SAXParseException e) {
            throw new SoapParseException(List.of(who + " is not well-formed XML (line " + e.getLineNumber() + "): "
                    + e.getMessage()));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new SoapParseException(List.of(who + " could not be read: " + e.getMessage()));
        }
    }

    // ── For wsdl4j ────────────────────────────────────────────────────────────

    /**
     * How wsdl4j finds an imported document: among the files, by the path the reference resolves
     * to. Given a locator its reader opens nothing itself. A reference {@link #problems()} did not
     * report always resolves; anything else answers null, which the reader reports as an
     * unlocatable import.
     */
    public WSDLLocator locator() {
        return new WSDLLocator() {
            private String latest = MAIN_URI;

            @Override
            public InputSource getBaseInputSource() {
                return source(main, MAIN_URI);
            }

            @Override
            public String getBaseURI() {
                return MAIN_URI;
            }

            @Override
            public InputSource getImportInputSource(String parentLocation, String importLocation) {
                String parent = parentLocation == null || MAIN_URI.equals(parentLocation) || !parentLocation.startsWith(ROOT)
                        ? null : parentLocation.substring(ROOT.length());
                if (importLocation == null || ADDRESS.matcher(importLocation.trim()).matches()) return null;
                String path = resolve(parent, importLocation);
                String text = path == null ? null : files.get(path);
                if (text == null) return null;
                latest = URI.create(ROOT).resolve(path).toString();
                return source(text, latest);
            }

            @Override
            public String getLatestImportURI() {
                return latest;
            }

            @Override
            public void close() {
                // Nothing is open: the documents are text.
            }

            private static InputSource source(String text, String uri) {
                InputSource source = new InputSource(new StringReader(text));
                source.setSystemId(uri);
                return source;
            }
        };
    }
}
