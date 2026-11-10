package com.menora.initializr.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetDefaultDepEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetDefaultDepRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the fullstack template sets in the database in step with the ones this release ships
 * under {@code templates/fullstack/<set>/manifest.json}.
 *
 * <p>The sets are edited in the admin UI, so the database is the source of truth at generation
 * time — but a release that changes a template must still reach an existing database. Each row
 * remembers the hash of the shipped content it was seeded with
 * ({@link EntityTemplateFileEntity#getSeedHash()}); on startup:
 * <ul>
 *   <li>a shipped file with no row is added;</li>
 *   <li>a row whose content still matches its hash is replaced by the shipped file, and removed
 *       when the release no longer ships it;</li>
 *   <li>a row an admin edited, created or imported is kept as it is;</li>
 *   <li>a row from before the hash existed (null) is treated as untouched, once.</li>
 * </ul>
 * {@code initializr.fullstack.template-sync=off} restores the old behaviour (seed an empty table,
 * otherwise leave everything alone). {@link #reset()} puts every shipped file back regardless of
 * edits.
 */
@Component
public class EntityTemplateSetSync {

    private static final Logger log = LoggerFactory.getLogger(EntityTemplateSetSync.class);

    static final String BASE = "templates/fullstack/";
    /** The shipped sets, base sets before the variants that borrow from them. */
    static final List<String> SETS = List.of(
            "spring-jpa-crud", "spring-jpa-crud-lombok", "react-tailwind-crud", "react-menora-digital-crud");

    /** What a sync did, per kind of row. */
    public record Result(int added, int updated, int removed, int kept) {
        Result plus(Result o) {
            return new Result(added + o.added, updated + o.updated, removed + o.removed, kept + o.kept);
        }

        boolean changedAnything() {
            return added + updated + removed > 0;
        }
    }

    /** One file of a shipped set, its content already read. */
    record SeedFile(String path, String content, FileContributionEntity.SubstitutionType substitutionType,
                    EntityTemplateFileEntity.FileType fileType, boolean perEntity, boolean perPage,
                    int sortOrder, String gatedBy) {
    }

    private final EntityTemplateSetRepository setRepo;
    private final EntityTemplateFileRepository fileRepo;
    private final EntityTemplateSetDefaultDepRepository defaultDepRepo;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public EntityTemplateSetSync(EntityTemplateSetRepository setRepo,
                                 EntityTemplateFileRepository fileRepo,
                                 EntityTemplateSetDefaultDepRepository defaultDepRepo,
                                 @Value("${initializr.fullstack.template-sync:auto}") String mode) {
        this.setRepo = setRepo;
        this.fileRepo = fileRepo;
        this.defaultDepRepo = defaultDepRepo;
        this.enabled = !"off".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    /** Whether shipped content follows the release ({@code template-sync} is not {@code off}). */
    public boolean enabled() {
        return enabled;
    }

    /** The startup pass: seed what is missing and bring untouched rows up to this release. */
    @Transactional
    public Result sync() throws IOException {
        if (!enabled) {
            if (setRepo.count() > 0) {
                log.debug("Entity template sets already present and template-sync is off — skipping");
                return new Result(0, 0, 0, 0);
            }
            log.info("Seeding entity template sets from classpath manifests");
        }
        Result total = run(false);
        if (total.changedAnything() || total.kept() > 0) {
            log.info("Entity template sets: {} files added, {} updated, {} removed, {} admin-edited kept",
                    total.added(), total.updated(), total.removed(), total.kept());
        }
        return total;
    }

    /** Puts every shipped file back, admin edits included. Rows the release never shipped stay. */
    @Transactional
    public Result reset() throws IOException {
        Result total = run(true);
        log.info("Entity template sets reset to the shipped files: {} added, {} updated, {} removed",
                total.added(), total.updated(), total.removed());
        return total;
    }

    private Result run(boolean force) throws IOException {
        Result total = new Result(0, 0, 0, 0);
        for (String key : SETS) {
            total = total.plus(syncSet(BASE + key + "/", force));
        }
        return total;
    }

    private Result syncSet(String baseDir, boolean force) throws IOException {
        JsonNode root = mapper.readTree(readClasspath(baseDir + "manifest.json"));
        String setKey = root.get("setKey").asText();
        List<SeedFile> shipped = readFiles(root, baseDir, setKey);

        EntityTemplateSetEntity set = setRepo.findBySetKey(setKey).orElse(null);
        if (set == null) {
            set = setRepo.save(newSet(root));
            for (SeedFile f : shipped) fileRepo.save(apply(new EntityTemplateFileEntity(), set.getId(), f));
            int deps = seedDefaultDeps(root, set.getId());
            log.info("Seeded entity template set '{}' with {} files and {} default deps", setKey, shipped.size(), deps);
            return new Result(shipped.size(), 0, 0, 0);
        }

        List<EntityTemplateFileEntity> rows = new ArrayList<>(fileRepo.findBySetIdOrderBySortOrderAsc(set.getId()));
        Map<SeedFile, EntityTemplateFileEntity> matches = match(shipped, rows);
        int added = 0, updated = 0, removed = 0, kept = 0;

        for (SeedFile f : shipped) {
            EntityTemplateFileEntity row = matches.get(f);
            if (row == null) {
                fileRepo.save(apply(new EntityTemplateFileEntity(), set.getId(), f));
                added++;
            } else if (force || untouched(row) || hash(row.getContent()).equals(hash(f.content()))) {
                if (differs(row, f)) {
                    fileRepo.save(apply(row, set.getId(), f));
                    updated++;
                } else if (!hash(f.content()).equals(row.getSeedHash())) {
                    row.setSeedHash(hash(f.content()));
                    fileRepo.save(row);
                }
            } else {
                log.info("Entity template set '{}': keeping the admin-edited '{}'", setKey, row.getPathTemplate());
                kept++;
            }
        }
        // A row this release no longer ships goes when it was a shipped file nobody edited; one an
        // admin added (or edited) stays.
        for (EntityTemplateFileEntity row : rows) {
            if (matches.containsValue(row)) continue;
            boolean wasShipped = !EntityTemplateFileEntity.CUSTOM_SEED_HASH.equals(row.getSeedHash());
            if (wasShipped && untouched(row)) {
                log.info("Entity template set '{}': removing '{}', no longer shipped", setKey, row.getPathTemplate());
                fileRepo.delete(row);
                removed++;
            }
        }
        return new Result(added, updated, removed, kept);
    }

    /** A row still holding the content it was seeded with — or one from before hashes were kept. */
    private static boolean untouched(EntityTemplateFileEntity row) {
        return row.getSeedHash() == null || row.getSeedHash().equals(hash(row.getContent()));
    }

    private static boolean differs(EntityTemplateFileEntity row, SeedFile f) {
        return !Objects.equals(row.getContent(), f.content())
                || row.getSubstitutionType() != f.substitutionType()
                || row.getFileType() != f.fileType()
                || row.isPerEntity() != f.perEntity()
                || row.isPerPage() != f.perPage()
                || row.getSortOrder() != f.sortOrder()
                || !Objects.equals(row.getGatedBy(), f.gatedBy());
    }

    /**
     * Pairs each shipped file with its row. A path is usually unique in a set, and then it alone is
     * the key — so a file whose gate changed between releases still finds its row. Only the page
     * screens share one path, told apart by their gate.
     */
    private static Map<SeedFile, EntityTemplateFileEntity> match(List<SeedFile> shipped,
                                                                 List<EntityTemplateFileEntity> rows) {
        Map<String, List<SeedFile>> shippedByPath = new LinkedHashMap<>();
        for (SeedFile f : shipped) shippedByPath.computeIfAbsent(f.path(), p -> new ArrayList<>()).add(f);
        Map<String, List<EntityTemplateFileEntity>> rowsByPath = new LinkedHashMap<>();
        for (EntityTemplateFileEntity r : rows) rowsByPath.computeIfAbsent(r.getPathTemplate(), p -> new ArrayList<>()).add(r);

        Map<SeedFile, EntityTemplateFileEntity> matches = new LinkedHashMap<>();
        for (Map.Entry<String, List<SeedFile>> e : shippedByPath.entrySet()) {
            List<EntityTemplateFileEntity> candidates = rowsByPath.getOrDefault(e.getKey(), List.of());
            if (e.getValue().size() == 1 && candidates.size() == 1) {
                matches.put(e.getValue().get(0), candidates.get(0));
                continue;
            }
            List<EntityTemplateFileEntity> free = new ArrayList<>(candidates);
            for (SeedFile f : e.getValue()) {
                for (EntityTemplateFileEntity r : free) {
                    if (Objects.equals(r.getGatedBy(), f.gatedBy())) {
                        matches.put(f, r);
                        free.remove(r);
                        break;
                    }
                }
            }
        }
        return matches;
    }

    private static EntityTemplateFileEntity apply(EntityTemplateFileEntity row, Long setId, SeedFile f) {
        row.setSetId(setId);
        row.setPathTemplate(f.path());
        row.setContent(f.content());
        row.setSubstitutionType(f.substitutionType());
        row.setFileType(f.fileType());
        row.setPerEntity(f.perEntity());
        row.setPerPage(f.perPage());
        row.setSortOrder(f.sortOrder());
        row.setGatedBy(f.gatedBy());
        row.setSeedHash(hash(f.content()));
        return row;
    }

    private static EntityTemplateSetEntity newSet(JsonNode root) {
        EntityTemplateSetEntity set = new EntityTemplateSetEntity();
        set.setSetKey(root.get("setKey").asText());
        set.setName(root.get("name").asText());
        if (root.hasNonNull("description")) set.setDescription(root.get("description").asText());
        set.setKind(EntityTemplateSetEntity.Kind.valueOf(root.get("kind").asText()));
        set.setEnabled(true);
        set.setSortOrder(root.hasNonNull("sortOrder") ? root.get("sortOrder").asInt() : 0);
        if (root.hasNonNull("designSystem")) {
            set.setDesignSystem(EntityTemplateSetEntity.DesignSystem.valueOf(root.get("designSystem").asText()));
        }
        if (root.hasNonNull("bootVersion")) set.setBootVersion(root.get("bootVersion").asText());
        if (root.hasNonNull("javaVersion")) set.setJavaVersion(root.get("javaVersion").asText());
        return set;
    }

    private int seedDefaultDeps(JsonNode root, Long setId) {
        JsonNode defaultDeps = root.get("defaultDeps");
        if (defaultDeps == null || !defaultDeps.isArray()) return 0;
        int order = 0;
        for (JsonNode d : defaultDeps) {
            String depId = d.asText();
            if (depId == null || depId.isBlank()) continue;
            EntityTemplateSetDefaultDepEntity dd = new EntityTemplateSetDefaultDepEntity();
            dd.setSetId(setId);
            dd.setDepId(depId.trim());
            dd.setSortOrder(order++);
            defaultDepRepo.save(dd);
        }
        return order;
    }

    /**
     * A manifest's files with their content. A file may borrow its content from another set's
     * directory via {@code sourceSet}, so a variant set (e.g. {@code spring-jpa-crud-lombok})
     * authors only the files that differ.
     */
    static List<SeedFile> readFiles(JsonNode root, String baseDir, String setKey) {
        JsonNode files = root.get("files");
        if (files == null || !files.isArray()) {
            log.warn("Template set '{}' has no 'files' array — set seeded with no contents", setKey);
            return List.of();
        }
        List<SeedFile> result = new ArrayList<>();
        for (JsonNode f : files) {
            JsonNode sourceNode = f.get("source");
            if (sourceNode == null || sourceNode.asText().isBlank()) {
                throw new IllegalStateException("Template set '" + setKey
                        + "' has a file entry with a missing/blank 'source' (path="
                        + f.path("path").asText() + ")");
            }
            String sourceDir = f.hasNonNull("sourceSet") ? BASE + f.get("sourceSet").asText() + "/" : baseDir;
            String resourcePath = sourceDir + sourceNode.asText();
            String content;
            try {
                content = readClasspath(resourcePath);
            } catch (IOException e) {
                throw new IllegalStateException("Template set '" + setKey
                        + "' references missing content file '" + sourceNode.asText()
                        + "' (expected at " + resourcePath + ")", e);
            }
            String gatedBy = f.hasNonNull("gatedBy") && !f.get("gatedBy").asText().isBlank()
                    ? f.get("gatedBy").asText() : null;
            result.add(new SeedFile(
                    f.get("path").asText(),
                    content,
                    FileContributionEntity.SubstitutionType.valueOf(f.get("substitutionType").asText()),
                    EntityTemplateFileEntity.FileType.valueOf(f.get("fileType").asText()),
                    f.hasNonNull("perEntity") && f.get("perEntity").asBoolean(),
                    f.hasNonNull("perPage") && f.get("perPage").asBoolean(),
                    f.hasNonNull("sortOrder") ? f.get("sortOrder").asInt() : 0,
                    gatedBy));
        }
        return result;
    }

    /**
     * sha256 of a text with its line endings normalized: a Windows checkout ships CRLF templates,
     * and a browser's textarea hands an untouched one back with LF — neither is an edit.
     */
    public static String hash(String content) {
        try {
            byte[] bytes = (content == null ? "" : content).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) throw new IOException("Classpath resource not found: " + path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
