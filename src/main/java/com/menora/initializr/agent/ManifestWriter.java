package com.menora.initializr.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.menora.initializr.agent.dto.MenoraInitManifest;
import com.menora.initializr.agent.dto.ScaffoldRequest;
import io.spring.initializr.generator.project.ProjectDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds and writes {@code .menora-init.json} into a freshly-generated project
 * directory. Called from {@link AgentScaffoldController} <em>after</em>
 * {@code invokeProjectStructureGeneration} returns, so per-file checksums
 * match the post-deletion state of the project on disk.
 *
 * <p>Side-effect free aside from the single file write — safe to call from
 * the same thread that invoked the project generator.
 */
@Component
public class ManifestWriter {

    public static final String MANIFEST_FILENAME = ".menora-init.json";
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String generatorVersion;

    public ManifestWriter(@Value("${menora.generator.version:dev}") String generatorVersion) {
        this.generatorVersion = generatorVersion;
    }

    /**
     * Builds the manifest from the request + the resolved
     * {@link ProjectDescription}, writes it to {@code projectRoot/.menora-init.json},
     * and returns the parsed object so the controller can echo it in the JSON
     * response without re-reading the file.
     */
    public MenoraInitManifest writeAndReturn(Path projectRoot,
                                             ScaffoldRequest request,
                                             ProjectDescription description) throws IOException {
        MenoraInitManifest manifest = build(projectRoot, request, description);
        Files.writeString(projectRoot.resolve(MANIFEST_FILENAME),
                serialize(manifest), StandardCharsets.UTF_8);
        return manifest;
    }

    private MenoraInitManifest build(Path projectRoot,
                                     ScaffoldRequest req,
                                     ProjectDescription description) throws IOException {
        return new MenoraInitManifest(
                SCHEMA_VERSION,
                new MenoraInitManifest.Generator(
                        "menora-initializr",
                        generatorVersion,
                        Instant.now().toString()),
                inputsFrom(req, description),
                checksumTree(projectRoot));
    }

    private MenoraInitManifest.Inputs inputsFrom(ScaffoldRequest req, ProjectDescription d) {
        return new MenoraInitManifest.Inputs(
                req.resolvedMode(),
                firstNonBlank(req.groupId(), d.getGroupId()),
                firstNonBlank(req.artifactId(), d.getArtifactId()),
                firstNonBlank(req.name(), d.getName()),
                firstNonBlank(req.packageName(), d.getPackageName()),
                firstNonBlank(req.bootVersion(), d.getPlatformVersion() != null
                        ? d.getPlatformVersion().toString() : null),
                firstNonBlank(req.javaVersion(), d.getLanguage() != null
                        ? d.getLanguage().jvmVersion() : null),
                firstNonBlank(req.packaging(), d.getPackaging() != null
                        ? d.getPackaging().id() : null),
                firstNonBlank(req.language(), d.getLanguage() != null
                        ? d.getLanguage().id() : null),
                req.type(),
                firstNonBlank(req.version(), d.getVersion()),
                req.dependencies() == null ? List.of() : List.copyOf(req.dependencies()),
                req.modules() == null ? List.of() : List.copyOf(req.modules()),
                req.opts() == null ? Map.of() : copyOpts(req.opts()),
                buildWizards(req)
        );
    }

    private static MenoraInitManifest.Wizards buildWizards(ScaffoldRequest req) {
        Map<String, Object> sql = new LinkedHashMap<>();
        if (req.sqlByDep() != null && !req.sqlByDep().isEmpty()) sql.put("sqlByDep", req.sqlByDep());
        if (req.sqlOptions() != null && !req.sqlOptions().isEmpty()) sql.put("sqlOptions", req.sqlOptions());

        Map<String, Object> oa = new LinkedHashMap<>();
        if (req.specByDep() != null && !req.specByDep().isEmpty()) oa.put("specByDep", req.specByDep());
        if (req.openApiOptions() != null && !req.openApiOptions().isEmpty()) oa.put("openApiOptions", req.openApiOptions());

        Map<String, Object> soap = new LinkedHashMap<>();
        if (req.wsdlByDep() != null && !req.wsdlByDep().isEmpty()) soap.put("wsdlByDep", req.wsdlByDep());
        if (req.soapOptions() != null && !req.soapOptions().isEmpty()) soap.put("soapOptions", req.soapOptions());

        if (sql.isEmpty() && oa.isEmpty() && soap.isEmpty()) return null;
        return new MenoraInitManifest.Wizards(
                sql.isEmpty() ? null : sql,
                oa.isEmpty() ? null : oa,
                soap.isEmpty() ? null : soap);
    }

    private static List<MenoraInitManifest.FileEntry> checksumTree(Path projectRoot) throws IOException {
        List<MenoraInitManifest.FileEntry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        String rel = projectRoot.relativize(p).toString().replace('\\', '/');
                        if (MANIFEST_FILENAME.equals(rel)) return;
                        entries.add(new MenoraInitManifest.FileEntry(rel, sha256(p)));
                    });
        }
        return entries;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(path));
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "ERROR";
        }
    }

    private static String serialize(MenoraInitManifest manifest) throws JsonProcessingException {
        return MAPPER.writeValueAsString(manifest);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return (preferred == null || preferred.isBlank()) ? fallback : preferred;
    }

    private static Map<String, List<String>> copyOpts(Map<String, List<String>> src) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
        }
        return copy;
    }
}
