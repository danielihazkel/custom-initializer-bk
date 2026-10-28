package com.menora.initializr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Fullstack requests built from the seeded examples, the way the editor builds them, and their zips. */
public final class FullstackExampleRequests {

    private FullstackExampleRequests() {
    }

    public static List<Map<String, Object>> examples() throws IOException {
        try (InputStream in = new ClassPathResource("catalog/fullstack-examples.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<>() {});
        }
    }

    public static boolean hasPages(Map<String, Object> example) {
        return example.get("pages") instanceof List<?> p && !p.isEmpty();
    }

    /**
     * An example's entities, its pages (unless classic) and its settings, with the given frontend
     * set (null: none named), locale and — for Hebrew — RTL.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> body(Map<String, Object> example, String set, String locale, boolean layout) {
        Map<String, Object> settings = example.get("settings") instanceof Map<?, ?> s
                ? (Map<String, Object>) s : Map.of();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "golden-" + example.get("exampleId"));
        body.put("packageName", "com.menora.golden");
        body.put("bootVersion", "3.2.1");
        if (set != null) body.put("frontendTemplateSet", set);
        body.put("locale", locale);
        for (String key : List.of("dashboardTitle", "dashboardOverview", "colorPalette")) {
            if (settings.get(key) != null) body.put(key, settings.get(key));
        }
        List<String> scaffold = new ArrayList<>(settings.get("scaffold") instanceof List<?> l
                ? (List<String>) l : List.of());
        if ("he".equals(locale)) scaffold.add("rtl");
        body.put("opts", Map.of("scaffold", scaffold));
        body.put("entities", example.get("entities"));
        if (layout) {
            body.put("pages", example.get("pages"));
            if (settings.get("nav") != null) body.put("nav", settings.get("nav"));
        }
        return body;
    }

    /** POSTs a fullstack request; the response, whatever its status. */
    public static ResponseEntity<byte[]> post(TestRestTemplate rest, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.zip", HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }

    /** Every file of a zip by path. */
    public static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zin.transferTo(out);
                files.put(entry.getName(), out.toByteArray());
            }
        }
        return files;
    }
}
