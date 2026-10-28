package com.menora.initializr.design;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.DesignAssetBlobEntity;
import com.menora.initializr.db.entity.DesignKitAssetEntity;
import com.menora.initializr.db.entity.DesignKitEntity;
import com.menora.initializr.db.entity.DesignKitFileEntity;
import com.menora.initializr.db.entity.DesignKitVersionEntity;
import com.menora.initializr.db.entity.DesignKitVersionEntity.Status;
import com.menora.initializr.db.repository.DesignAssetBlobRepository;
import com.menora.initializr.db.repository.DesignKitAssetRepository;
import com.menora.initializr.db.repository.DesignKitFileRepository;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.repository.DesignKitRepository;
import com.menora.initializr.db.repository.DesignKitVersionRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Design kits and their versions. A kit has at most one draft — the only editable version — and
 * any number of published versions, which never change ({@link #requireDraft}). Built-in kits are
 * seeded from the classpath ({@link BuiltInKits}) and read-only: customizing one means cloning it.
 */
@Service
public class DesignKitService {

    private static final Logger log = LoggerFactory.getLogger(DesignKitService.class);

    /** The kit contract major version this generator accepts (slots, token schema). */
    public static final int CONTRACT_VERSION = 1;
    public static final String KIT_CSS = "kit.css";
    static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{1,48}$");
    static final String SYSTEM = "system";

    private final DesignKitRepository kits;
    private final DesignKitVersionRepository versions;
    private final DesignKitFileRepository files;
    private final DesignKitAssetRepository assets;
    private final DesignAssetBlobRepository blobs;
    private final EntityTemplateSetRepository sets;
    private final EntityTemplateFileRepository templateFiles;
    private final ObjectMapper mapper;

    public DesignKitService(DesignKitRepository kits, DesignKitVersionRepository versions, DesignKitFileRepository files,
                            DesignKitAssetRepository assets, DesignAssetBlobRepository blobs,
                            EntityTemplateSetRepository sets, EntityTemplateFileRepository templateFiles, ObjectMapper mapper) {
        this.kits = kits;
        this.versions = versions;
        this.files = files;
        this.assets = assets;
        this.templateFiles = templateFiles;
        this.blobs = blobs;
        this.sets = sets;
        this.mapper = mapper;
    }

    // ── Views ───────────────────────────────────────────────────────────────

    /**
     * A kit as the lists show it. {@code defaultKit}: the house kit (a request naming none generates
     * with it); {@code aliases}: the frontend sets that generate as this kit; {@code swatch}: the
     * light values of {@link #SWATCH} as the latest version has them ({@code {palette.*}} left in).
     */
    public record KitSummary(String key, String name, String description, String baseSetKey, boolean builtIn,
                             boolean enabled, int sortOrder, String defaultPaletteId, String clonedFrom,
                             Integer latestVersion, boolean hasDraft, boolean paletteAware,
                             KitManifest.Defaults defaults, boolean defaultKit, KitManifest.Traits traits,
                             List<String> aliases, Map<String, String> swatch, List<DeprecatedVersion> deprecated,
                             String thumbnail, List<String> navIcons) {
    }

    /** A published version that still generates but is no longer offered, and why. */
    public record DeprecatedVersion(int version, String note) {
    }

    /** The tokens a kit's swatch shows, in order: the shell's ground, identity, action, page. */
    static final List<String> SWATCH = List.of("color-ink", "color-brand", "color-primary", "color-canvas");

    public record AssetView(String targetPath, String role, String contentType, long sizeBytes, String sha256,
                            String meta, String url) {
    }

    public record VersionView(String key, Integer version, String status, Integer basedOnVersion, String checksum,
                              String notes, Instant createdAt, Instant publishedAt, String publishedBy,
                              DesignTokens tokens, KitManifest manifest, String css, List<AssetView> assets,
                              List<FileView> files) {
    }

    /** One of a version's component files (its content is read on its own). */
    public record FileView(String path, String role, int bytes, String sha256) {
    }

    /** A slot of the kit's base set, and whether the version shown replaces it. */
    public record SlotView(String path, List<String> exports, String gatedBy, int bytes, boolean replaced) {
    }

    public record Components(String baseSetKey, List<SlotView> slots, List<FileView> helpers) {
    }

    public record VersionSummary(Integer version, String status, String checksum, String notes,
                                 Instant publishedAt, String publishedBy, boolean deprecated, String deprecationNote) {
    }

    public record ValidationReport(boolean ok, List<String> problems) {
    }

    public record TokenChange(String name, String change, String before, String after) {
    }

    public record Diff(String from, String to, List<TokenChange> tokens, boolean manifestChanged, boolean cssChanged,
                       List<Map<String, String>> assets, List<Map<String, String>> files) {
    }

    /** {@code cloneFrom} copies a version; {@code baseSetKey} instead starts a blank kit on that set. */
    public record CreateKit(String key, String name, String description, String cloneFrom, String baseSetKey) {
        public CreateKit(String key, String name, String description, String cloneFrom) {
            this(key, name, description, cloneFrom, null);
        }
    }

    /** {@code defaultKit: true} makes the kit the house kit (the one before stops being it). */
    public record UpdateKit(String name, String description, Boolean enabled, Integer sortOrder, String defaultPaletteId,
                            Boolean defaultKit) {
        public UpdateKit(String name, String description, Boolean enabled, Integer sortOrder, String defaultPaletteId) {
            this(name, description, enabled, sortOrder, defaultPaletteId, null);
        }
    }

    public record Deprecate(Boolean deprecated, String note) {
    }

    public record SaveDraft(DesignTokens tokens, KitManifest manifest, String css, String notes) {
    }

    // ── Kits ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<KitSummary> list(boolean enabledOnly) {
        return kits.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(k -> !enabledOnly || k.isEnabled())
                .map(this::summary)
                .filter(s -> !enabledOnly || s.latestVersion() != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public KitSummary get(String key) {
        return summary(kit(key));
    }

    @Transactional
    public KitSummary create(CreateKit body, String user) {
        if (body == null) throw DesignKitException.badRequest("Request body is required");
        String key = trim(body.key());
        if (key == null || !KEY.matcher(key).matches()) {
            throw DesignKitException.badRequest("key must be 2–49 lower-case letters, digits and '-', starting with a letter");
        }
        if (kits.findByKitKey(key).isPresent()) throw DesignKitException.conflict("A kit with key '" + key + "' already exists");
        String name = requireName(body.name());
        if (trim(body.cloneFrom()) != null && trim(body.baseSetKey()) != null) {
            throw DesignKitException.badRequest("Give cloneFrom (a kit to copy) or baseSetKey (a blank kit), not both");
        }
        if (trim(body.baseSetKey()) != null) return createBlank(key, name, body, user);
        if (trim(body.cloneFrom()) == null) {
            throw DesignKitException.badRequest("cloneFrom (a kit key, or key@version) or baseSetKey (a blank kit) is required");
        }
        Ref ref = Ref.parse(body.cloneFrom());
        DesignKitEntity source = kit(ref.key());
        DesignKitVersionEntity from = ref.version() == null ? latest(source)
                .orElseThrow(() -> DesignKitException.conflict("Kit '" + source.getKitKey() + "' has no published version to clone"))
                : published(source, ref.version());

        DesignKitEntity k = new DesignKitEntity();
        k.setKitKey(key);
        k.setName(name);
        k.setDescription(description(body.description()));
        k.setBaseSetKey(source.getBaseSetKey());
        k.setDefaultPaletteId(source.getDefaultPaletteId());
        k.setClonedFrom(source.getKitKey() + "@" + from.getVersion());
        k.setSortOrder(source.getSortOrder() + 1);
        k.setCreatedBy(user);
        k.setCreatedAt(Instant.now());
        k.setUpdatedAt(k.getCreatedAt());
        kits.save(k);
        copyInto(from, newDraft(k, null, user));
        return summary(k);
    }

    /**
     * A blank kit on a base set: only the tokens the generator's files use, at the values of the
     * set's own built-in kit (so it generates as that kit does until it is edited), and its font —
     * no kit CSS, no component files, no assets, no defaults.
     */
    private KitSummary createBlank(String key, String name, CreateKit body, String user) {
        String setKey = trim(body.baseSetKey());
        DesignKitEntity own = kits.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(k -> k.isBuiltIn() && setKey.equals(k.getBaseSetKey())
                        && !BuiltInKits.FOR_DESIGN_SYSTEM.containsValue(k.getKitKey()))
                .findFirst()
                .orElseThrow(() -> DesignKitException.badRequest("baseSetKey must be a frontend template set a kit can style ("
                        + String.join(", ", baseSets()) + ")"));
        DesignKitVersionEntity from = latest(own)
                .orElseThrow(() -> DesignKitException.conflict("Kit '" + own.getKitKey() + "' has no published version"));
        DesignTokens ownTokens = tokensOf(from);
        List<DesignTokens.Token> required = TokenSchema.REQUIRED.stream()
                .map(r -> ownTokens.tokens().stream().filter(t -> t.name().equals(r.name())).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();

        DesignKitEntity k = new DesignKitEntity();
        k.setKitKey(key);
        k.setName(name);
        k.setDescription(description(body.description()));
        k.setBaseSetKey(setKey);
        k.setDefaultPaletteId(own.getDefaultPaletteId());
        k.setSortOrder(own.getSortOrder() + 1);
        k.setCreatedBy(user);
        k.setCreatedAt(Instant.now());
        k.setUpdatedAt(k.getCreatedAt());
        kits.save(k);
        DesignKitVersionEntity d = newDraft(k, null, user);
        d.setTokens(json(new DesignTokens(DesignTokens.SCHEMA, required)));
        d.setManifest(json(new KitManifest(List.of(), manifestOf(from).fonts(), null)));
        versions.save(d);
        return summary(k);
    }

    /** The frontend sets a blank kit can start on: those with a built-in kit of their own. */
    private List<String> baseSets() {
        return kits.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(k -> k.isBuiltIn() && !BuiltInKits.FOR_DESIGN_SYSTEM.containsValue(k.getKitKey()))
                .map(DesignKitEntity::getBaseSetKey).distinct().toList();
    }

    @Transactional
    public KitSummary update(String key, UpdateKit body) {
        DesignKitEntity k = kit(key);
        if (body == null) throw DesignKitException.badRequest("Request body is required");
        if (!k.isBuiltIn()) {
            if (body.name() != null) k.setName(requireName(body.name()));
            if (body.description() != null) k.setDescription(description(body.description()));
            if (body.defaultPaletteId() != null) k.setDefaultPaletteId(trim(body.defaultPaletteId()));
        } else if (body.name() != null || body.description() != null || body.defaultPaletteId() != null) {
            throw DesignKitException.conflict("Built-in kit '" + key + "' is read-only — clone it to change it");
        }
        if (Boolean.FALSE.equals(body.enabled()) && k.isDefaultKit()) {
            throw DesignKitException.conflict("'" + key + "' is the house kit — make another kit the house kit before disabling it");
        }
        if (Boolean.FALSE.equals(body.defaultKit()) && k.isDefaultKit()) {
            throw DesignKitException.conflict("There is always a house kit — make another kit the house kit instead");
        }
        if (Boolean.TRUE.equals(body.defaultKit()) && !k.isDefaultKit()) {
            boolean enabled = body.enabled() == null ? k.isEnabled() : body.enabled();
            if (!enabled) throw DesignKitException.conflict("A disabled kit cannot be the house kit");
            if (latest(k).isEmpty()) throw DesignKitException.conflict("Publish '" + key + "' before making it the house kit");
            kits.findAll().stream().filter(DesignKitEntity::isDefaultKit).forEach(other -> {
                other.setDefaultKit(false);
                kits.save(other);
            });
            k.setDefaultKit(true);
        }
        if (body.enabled() != null) k.setEnabled(body.enabled());
        if (body.sortOrder() != null) k.setSortOrder(body.sortOrder());
        k.setUpdatedAt(Instant.now());
        return summary(kits.save(k));
    }

    @Transactional
    public void delete(String key) {
        DesignKitEntity k = kit(key);
        if (k.isBuiltIn()) throw DesignKitException.conflict("Built-in kit '" + key + "' cannot be deleted (disable it instead)");
        if (k.isDefaultKit()) throw DesignKitException.conflict("'" + key + "' is the house kit — make another kit the house kit before deleting it");
        kits.delete(k);
    }

    // ── Drafts ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public VersionView draft(String key) {
        DesignKitEntity k = kit(key);
        return view(k, draftOf(k).orElseThrow(() -> DesignKitException.notFound("Kit '" + key + "' has no draft")));
    }

    /** Opens a draft from a published version (the latest when null); 409 when one is already open. */
    @Transactional
    public VersionView openDraft(String key, Integer fromVersion, String user) {
        DesignKitEntity k = editable(key);
        if (draftOf(k).isPresent()) throw DesignKitException.conflict("Kit '" + key + "' already has a draft");
        DesignKitVersionEntity from = fromVersion == null
                ? latest(k).orElseThrow(() -> DesignKitException.conflict("Kit '" + key + "' has no published version"))
                : published(k, fromVersion);
        DesignKitVersionEntity d = newDraft(k, from.getVersion(), user);
        copyInto(from, d);
        return view(k, d);
    }

    @Transactional
    public VersionView saveDraft(String key, SaveDraft body) {
        DesignKitEntity k = editable(key);
        DesignKitVersionEntity d = requireDraft(k);
        if (body == null) throw DesignKitException.badRequest("Request body is required");
        if (body.tokens() != null) d.setTokens(json(body.tokens()));
        if (body.manifest() != null) d.setManifest(json(body.manifest()));
        if (body.css() != null) {
            if (body.css().length() > TokenValidator.MAX_CSS) throw DesignKitException.badRequest("kit CSS is larger than 64 KB");
            putFile(d, KIT_CSS, DesignKitFileEntity.Role.CSS, body.css());
        }
        if (body.notes() != null) d.setNotes(body.notes().length() > 1000 ? body.notes().substring(0, 1000) : body.notes());
        d.setUpdatedAt(Instant.now());
        versions.save(d);
        return view(k, d);
    }

    @Transactional
    public void discardDraft(String key) {
        DesignKitEntity k = editable(key);
        versions.delete(requireDraft(k));
    }

    @Transactional
    public AssetView putAsset(String key, String targetPath, String role, String meta, byte[] content) {
        DesignKitEntity k = editable(key);
        DesignKitVersionEntity d = requireDraft(k);
        DesignKitAssetEntity.Role r = parseRole(role);
        String type = AssetValidator.check(targetPath, r, content);
        if (meta != null && meta.length() > 1000) throw DesignKitException.badRequest("meta must be at most 1000 characters");
        long others = assets.findByVersionIdOrderByTargetPathAsc(d.getId()).stream()
                .filter(a -> !a.getTargetPath().equals(targetPath))
                .mapToLong(a -> blobs.findById(a.getBlobSha256()).map(DesignAssetBlobEntity::getSizeBytes).orElse(0L))
                .sum();
        if (others + content.length > AssetValidator.MAX_VERSION_BYTES) {
            throw DesignKitException.badRequest("a kit version may hold at most " + AssetValidator.MAX_VERSION_BYTES / 1024 / 1024 + " MB of assets");
        }
        String sha = storeBlob(content, type);
        DesignKitAssetEntity a = assets.findByVersionIdAndTargetPath(d.getId(), targetPath).orElseGet(DesignKitAssetEntity::new);
        a.setVersionId(d.getId());
        a.setTargetPath(targetPath);
        a.setRole(r);
        a.setBlobSha256(sha);
        a.setMeta(trim(meta));
        assets.save(a);
        d.setUpdatedAt(Instant.now());
        return assetView(a);
    }

    @Transactional
    public void deleteAsset(String key, String targetPath) {
        DesignKitVersionEntity d = requireDraft(editable(key));
        DesignKitAssetEntity a = assets.findByVersionIdAndTargetPath(d.getId(), targetPath)
                .orElseThrow(() -> DesignKitException.notFound("The draft has no asset at " + targetPath));
        assets.delete(a);
    }

    @Transactional(readOnly = true)
    public ValidationReport validateDraft(String key) {
        DesignKitEntity k = kit(key);
        return validate(k, requireDraft(k));
    }

    @Transactional
    public VersionView publish(String key, String notes, String user) {
        DesignKitEntity k = editable(key);
        DesignKitVersionEntity d = requireDraft(k);
        ValidationReport report = validate(k, d);
        if (!report.ok()) throw DesignKitException.invalid(report.problems());
        int next = latest(k).map(v -> v.getVersion() + 1).orElse(1);
        if (notes != null) d.setNotes(notes.length() > 1000 ? notes.substring(0, 1000) : notes);
        seal(d, next, user);
        k.setUpdatedAt(Instant.now());
        return view(k, d);
    }

    /** Replaces the draft's content with a published version's — the start of a rollback. */
    @Transactional
    public VersionView restore(String key, int version, String user) {
        DesignKitEntity k = editable(key);
        DesignKitVersionEntity from = published(k, version);
        draftOf(k).ifPresent(d -> {
            versions.delete(d);
            versions.flush();
        });
        DesignKitVersionEntity d = newDraft(k, version, user);
        copyInto(from, d);
        return view(k, d);
    }

    // ── Published versions ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VersionSummary> versions(String key) {
        DesignKitEntity k = kit(key);
        return versions.findByKitIdAndStatusOrderByVersionDesc(k.getId(), Status.PUBLISHED).stream()
                .map(this::versionSummary)
                .toList();
    }

    /**
     * Deprecates a published version (or takes the mark back). A deprecated version still
     * generates — models and examples pinned to it keep working — but the generator offers the
     * latest instead. The latest version cannot be deprecated: publish a newer one first, or
     * disable the kit. Metadata, not content: the checksum does not change.
     */
    @Transactional
    public VersionSummary deprecate(String key, int version, Deprecate body) {
        DesignKitEntity k = kit(key);
        DesignKitVersionEntity v = published(k, version);
        boolean on = body == null || body.deprecated() == null || body.deprecated();
        if (on && latest(k).map(l -> l.getId().equals(v.getId())).orElse(false)) {
            throw DesignKitException.conflict("v" + version + " is the latest version of '" + key
                    + "' — publish a newer version first, or disable the kit");
        }
        String note = body == null ? null : trim(body.note());
        if (note != null && note.length() > 500) throw DesignKitException.badRequest("note must be at most 500 characters");
        v.setDeprecated(on);
        v.setDeprecationNote(on ? note : null);
        versions.save(v);
        return versionSummary(v);
    }

    private VersionSummary versionSummary(DesignKitVersionEntity v) {
        return new VersionSummary(v.getVersion(), v.getStatus().name(), v.getChecksum(), v.getNotes(),
                v.getPublishedAt(), v.getPublishedBy(), v.isDeprecated(), v.getDeprecationNote());
    }

    /** One version: a number, {@code latest} or {@code draft}. */
    @Transactional(readOnly = true)
    public VersionView version(String key, String which) {
        DesignKitEntity k = kit(key);
        return view(k, select(k, which));
    }

    @Transactional(readOnly = true)
    public Diff diff(String key, String from, String to) {
        DesignKitEntity k = kit(key);
        DesignKitVersionEntity a = select(k, from);
        DesignKitVersionEntity b = select(k, to);
        Map<String, DesignTokens.Token> before = byName(tokensOf(a));
        Map<String, DesignTokens.Token> after = byName(tokensOf(b));
        List<TokenChange> changes = new ArrayList<>();
        before.forEach((name, t) -> {
            DesignTokens.Token n = after.get(name);
            if (n == null) changes.add(new TokenChange(name, "removed", describe(t), null));
            else if (!n.equals(t)) changes.add(new TokenChange(name, "changed", describe(t), describe(n)));
        });
        after.forEach((name, t) -> {
            if (!before.containsKey(name)) changes.add(new TokenChange(name, "added", null, describe(t)));
        });
        Map<String, String> assetsA = assetShas(a);
        Map<String, String> assetsB = assetShas(b);
        List<Map<String, String>> assetChanges = new ArrayList<>();
        assetsA.forEach((p, sha) -> {
            if (!assetsB.containsKey(p)) assetChanges.add(Map.of("path", p, "change", "removed"));
            else if (!assetsB.get(p).equals(sha)) assetChanges.add(Map.of("path", p, "change", "changed"));
        });
        assetsB.keySet().stream().filter(p -> !assetsA.containsKey(p))
                .forEach(p -> assetChanges.add(Map.of("path", p, "change", "added")));
        Map<String, String> filesA = componentShas(a);
        Map<String, String> filesB = componentShas(b);
        List<Map<String, String>> fileChanges = new ArrayList<>();
        filesA.forEach((p, sha) -> {
            if (!filesB.containsKey(p)) fileChanges.add(Map.of("path", p, "change", "removed"));
            else if (!filesB.get(p).equals(sha)) fileChanges.add(Map.of("path", p, "change", "changed"));
        });
        filesB.keySet().stream().filter(p -> !filesA.containsKey(p))
                .forEach(p -> fileChanges.add(Map.of("path", p, "change", "added")));
        return new Diff(label(a), label(b), changes, !Objects.equals(a.getManifest(), b.getManifest()),
                !Objects.equals(cssOf(a), cssOf(b)), assetChanges, fileChanges);
    }

    @Transactional(readOnly = true)
    public Optional<DesignAssetBlobEntity> blob(String sha256) {
        return blobs.findById(sha256);
    }

    // ── Generation ──────────────────────────────────────────────────────────

    /**
     * The kit a request names ({@code key}, {@code key@N}; {@code key@draft} only when
     * {@code allowDraft}), fully read. 400 for anything that cannot generate.
     */
    @Transactional(readOnly = true)
    public ResolvedKit resolve(String refText, boolean allowDraft) {
        Ref ref = Ref.parse(refText);
        DesignKitEntity k = kits.findByKitKey(ref.key())
                .orElseThrow(() -> DesignKitException.badRequest("Unknown design kit '" + ref.key() + "'"));
        DesignKitVersionEntity v;
        if (ref.draft()) {
            if (!allowDraft) throw DesignKitException.badRequest("Design kit drafts can only be tried from the Design Studio");
            v = draftOf(k).orElseThrow(() -> DesignKitException.badRequest("Design kit '" + k.getKitKey() + "' has no draft"));
            ValidationReport report = validate(k, v);
            if (!report.ok()) throw DesignKitException.invalid(report.problems());
        } else {
            if (!k.isEnabled() && ref.version() == null) {
                throw DesignKitException.badRequest("Design kit '" + k.getKitKey() + "' is disabled");
            }
            v = ref.version() == null
                    ? latest(k).orElseThrow(() -> DesignKitException.badRequest("Design kit '" + k.getKitKey() + "' has no published version"))
                    : versions.findByKitIdAndVersion(k.getId(), ref.version())
                            .orElseThrow(() -> DesignKitException.badRequest("Design kit '" + k.getKitKey() + "' has no version " + ref.version()));
        }
        if (v.getContractVersion() != CONTRACT_VERSION) {
            throw DesignKitException.badRequest("Design kit " + k.getKitKey() + "@" + v.getVersion()
                    + " was made for another generator version — open it in the Design Studio and publish it again");
        }
        List<ResolvedKit.Asset> resolvedAssets = assets.findByVersionIdOrderByTargetPathAsc(v.getId()).stream()
                .map(a -> {
                    DesignAssetBlobEntity b = blobs.findById(a.getBlobSha256()).orElseThrow();
                    return new ResolvedKit.Asset(a.getTargetPath(), a.getRole().name(), b.getContentType(), a.getMeta(), b.getContent());
                })
                .toList();
        List<ResolvedKit.File> resolvedFiles = componentFiles(v).stream()
                .map(f -> new ResolvedKit.File(f.getPath(), f.getRole().name(), f.getContent()))
                .toList();
        return new ResolvedKit(k.getKitKey(), v.getVersion(), k.getName(), k.getBaseSetKey(), k.getDefaultPaletteId(),
                tokensOf(v), manifestOf(v), cssOf(v), resolvedAssets, resolvedFiles);
    }

    /**
     * The built-in kit a frontend set stands for, if any: a set tagged with a design system that
     * a built-in kit implements (Menora Digital) is that kit on the kit's base set — generating it
     * means generating the base set with the kit.
     */
    @Transactional(readOnly = true)
    public Optional<String> kitOfSet(String setKey) {
        if (setKey == null || setKey.isBlank()) return Optional.empty();
        return sets.findBySetKey(setKey)
                .map(EntityTemplateSetEntity::getDesignSystem)
                .map(BuiltInKits.FOR_DESIGN_SYSTEM::get);
    }

    /**
     * The house kit for a request that names no kit, on the frontend set it generates: the kit
     * marked as the house kit, when it is enabled, published and styles that set. Otherwise none —
     * the set's own files, which for the shipped set are {@code tailwind-default}'s bytes.
     */
    @Transactional(readOnly = true)
    public Optional<String> houseKitFor(String setKey) {
        return kits.findAll().stream()
                .filter(k -> k.isDefaultKit() && k.isEnabled() && k.getBaseSetKey().equals(setKey))
                .filter(k -> latest(k).isPresent())
                .map(DesignKitEntity::getKitKey)
                .findFirst();
    }

    /** The frontend set a kit styles, if the kit exists. */
    @Transactional(readOnly = true)
    public Optional<String> baseSetOf(String kitKey) {
        return kits.findByKitKey(kitKey).map(DesignKitEntity::getBaseSetKey);
    }

    // ── Component files (P3) ────────────────────────────────────────────────

    /**
     * The slots of a frontend template set: its plain {@code .tsx} components under
     * {@code src/shared/ui/} (not per entity, not per page, written verbatim), with the names they
     * export — what the generator's glue imports from them.
     */
    @Transactional(readOnly = true)
    public Map<String, KitSourceValidator.Slot> slots(String setKey) {
        Map<String, KitSourceValidator.Slot> out = new LinkedHashMap<>();
        sets.findBySetKey(setKey).ifPresent(set -> templateFiles.findBySetIdOrderBySortOrderAsc(set.getId()).stream()
                .filter(f -> !f.isPerEntity() && !f.isPerPage()
                        && f.getSubstitutionType() == FileContributionEntity.SubstitutionType.NONE
                        && KitSourceValidator.SLOT.matcher(f.getPathTemplate()).matches())
                .sorted(java.util.Comparator.comparing(EntityTemplateFileEntity::getPathTemplate))
                .forEach(f -> out.put(f.getPathTemplate(), new KitSourceValidator.Slot(f.getPathTemplate(),
                        List.copyOf(KitSourceValidator.exportsOf(f.getContent())), f.getGatedBy(),
                        f.getContent().getBytes(StandardCharsets.UTF_8).length))));
        return out;
    }

    /** The kit's components: its base set's slots (which the draft — else the latest version —
     *  replaces) and its helpers. */
    @Transactional(readOnly = true)
    public Components components(String key) {
        DesignKitEntity k = kit(key);
        Optional<DesignKitVersionEntity> v = draftOf(k).or(() -> latest(k));
        Set<String> replaced = new java.util.HashSet<>();
        List<FileView> helpers = new ArrayList<>();
        v.ifPresent(version -> componentFiles(version).forEach(f -> {
            if (f.getRole() == DesignKitFileEntity.Role.SLOT) replaced.add(f.getPath());
            else helpers.add(fileView(f));
        }));
        List<SlotView> slotViews = slots(k.getBaseSetKey()).values().stream()
                .map(s -> new SlotView(s.path(), s.exports(), s.gatedBy(), s.bytes(), replaced.contains(s.path())))
                .toList();
        return new Components(k.getBaseSetKey(), slotViews, helpers);
    }

    /** The base set's own source of a slot — what a kit starts from, and returns to on a reset. */
    @Transactional(readOnly = true)
    public String baseSource(String key, String path) {
        DesignKitEntity k = kit(key);
        if (!slots(k.getBaseSetKey()).containsKey(path)) throw DesignKitException.notFound("'" + path + "' is not a component of " + k.getBaseSetKey());
        EntityTemplateSetEntity set = sets.findBySetKey(k.getBaseSetKey()).orElseThrow();
        return templateFiles.findBySetIdOrderBySortOrderAsc(set.getId()).stream()
                .filter(f -> f.getPathTemplate().equals(path)).findFirst().orElseThrow().getContent();
    }

    /** A component file of a version ({@code draft}, {@code latest} or a number). */
    @Transactional(readOnly = true)
    public String fileContent(String key, String which, String path) {
        DesignKitEntity k = kit(key);
        DesignKitVersionEntity v = select(k, which);
        return files.findByVersionIdAndPath(v.getId(), path)
                .filter(f -> f.getRole() != DesignKitFileEntity.Role.CSS)
                .map(DesignKitFileEntity::getContent)
                .orElseThrow(() -> DesignKitException.notFound(label(v) + " has no file " + path));
    }

    /** Saves a component file into the draft: a slot replacement or a helper, checked first. */
    @Transactional
    public FileView putComponentFile(String key, String path, String content) {
        DesignKitEntity k = editable(key);
        DesignKitVersionEntity d = requireDraft(k);
        Map<String, KitSourceValidator.Slot> slots = slots(k.getBaseSetKey());
        List<String> problems = KitSourceValidator.check(path, content, slots);
        if (!problems.isEmpty()) throw DesignKitException.invalid(problems);
        boolean isNew = files.findByVersionIdAndPath(d.getId(), path).isEmpty();
        if (isNew && componentFiles(d).size() >= KitSourceValidator.MAX_FILES) {
            throw DesignKitException.badRequest("a kit version may hold at most " + KitSourceValidator.MAX_FILES + " component files");
        }
        DesignKitFileEntity.Role role = KitSourceValidator.kindOf(path, slots) == KitSourceValidator.Kind.SLOT
                ? DesignKitFileEntity.Role.SLOT : DesignKitFileEntity.Role.HELPER;
        putFile(d, path, role, content);
        d.setUpdatedAt(Instant.now());
        versions.save(d);
        return fileView(files.findByVersionIdAndPath(d.getId(), path).orElseThrow());
    }

    /** Drops a component file from the draft: a slot goes back to the base set's. */
    @Transactional
    public void deleteComponentFile(String key, String path) {
        DesignKitEntity k = editable(key);
        DesignKitVersionEntity d = requireDraft(k);
        DesignKitFileEntity f = files.findByVersionIdAndPath(d.getId(), path)
                .filter(x -> x.getRole() != DesignKitFileEntity.Role.CSS)
                .orElseThrow(() -> DesignKitException.notFound("The draft has no file " + path));
        files.delete(f);
        d.setUpdatedAt(Instant.now());
        versions.save(d);
    }

    private List<DesignKitFileEntity> componentFiles(DesignKitVersionEntity v) {
        return files.findByVersionIdOrderByPathAsc(v.getId()).stream()
                .filter(f -> f.getRole() != DesignKitFileEntity.Role.CSS)
                .toList();
    }

    private FileView fileView(DesignKitFileEntity f) {
        return new FileView(f.getPath(), f.getRole().name(), f.getContent().getBytes(StandardCharsets.UTF_8).length, f.getSha256());
    }

    private Map<String, String> componentShas(DesignKitVersionEntity v) {
        Map<String, String> out = new LinkedHashMap<>();
        componentFiles(v).forEach(f -> out.put(f.getPath(), f.getSha256()));
        return out;
    }

    // ── Archives (.menora-kit.zip) ──────────────────────────────────────────

    /** A version's whole content, as an archive carries it ({@code version} null for a draft). */
    public record KitContent(String key, String name, String description, String baseSetKey, String defaultPaletteId,
                             Integer version, String notes, DesignTokens tokens, KitManifest manifest, String css,
                             List<ResolvedKit.File> files, List<ResolvedKit.Asset> assets) {
        public KitContent {
            files = files == null ? List.of() : List.copyOf(files);
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    /** One version ({@code latest}, {@code draft} or a number) with every file and asset read. */
    @Transactional(readOnly = true)
    public KitContent content(String key, String which) {
        DesignKitEntity k = kit(key);
        DesignKitVersionEntity v = select(k, which);
        List<ResolvedKit.Asset> av = assets.findByVersionIdOrderByTargetPathAsc(v.getId()).stream()
                .map(a -> {
                    DesignAssetBlobEntity b = blobs.findById(a.getBlobSha256()).orElseThrow();
                    return new ResolvedKit.Asset(a.getTargetPath(), a.getRole().name(), b.getContentType(), a.getMeta(), b.getContent());
                })
                .toList();
        List<ResolvedKit.File> fv = componentFiles(v).stream()
                .map(f -> new ResolvedKit.File(f.getPath(), f.getRole().name(), f.getContent()))
                .toList();
        return new KitContent(k.getKitKey(), k.getName(), k.getDescription(), k.getBaseSetKey(), k.getDefaultPaletteId(),
                v.getVersion(), v.getNotes(), tokensOf(v), manifestOf(v), cssOf(v), fv, av);
    }

    /**
     * Brings an archived version in as a <b>draft</b> — nothing is published until someone has
     * looked at it. Into a new kit ({@code asKey}, else the archive's key) when the key is free;
     * into an existing custom kit's new draft otherwise (409 when it already has one, or is a
     * built-in). Every file and asset passes the same checks as one saved in the Studio, so a
     * refused archive leaves nothing behind.
     */
    @Transactional
    public KitSummary importContent(KitContent content, String asKey, String user) {
        if (content == null) throw DesignKitException.badRequest("The archive is empty");
        String key = trim(asKey) != null ? trim(asKey) : trim(content.key());
        if (key == null || !KEY.matcher(key).matches()) {
            throw DesignKitException.badRequest("key must be 2–49 lower-case letters, digits and '-', starting with a letter");
        }
        String base = trim(content.baseSetKey());
        if (base == null || sets.findBySetKey(base).filter(set -> set.getKind() == EntityTemplateSetEntity.Kind.FRONTEND_REACT).isEmpty()) {
            throw DesignKitException.badRequest("The archive styles the frontend set '" + base + "', which this generator does not have");
        }
        if (content.tokens() == null || content.manifest() == null) throw DesignKitException.badRequest("The archive has no tokens or manifest");
        DesignKitEntity k = kits.findByKitKey(key).orElse(null);
        DesignKitVersionEntity d;
        if (k == null) {
            k = new DesignKitEntity();
            k.setKitKey(key);
            k.setName(requireName(content.name()));
            k.setDescription(description(content.description()));
            k.setBaseSetKey(base);
            k.setDefaultPaletteId(trim(content.defaultPaletteId()));
            if (content.key() != null && content.version() != null) {
                String from = content.key() + "@" + content.version();
                k.setClonedFrom(from.length() > 80 ? null : from);
            }
            k.setSortOrder(100);
            k.setCreatedBy(user);
            k.setCreatedAt(Instant.now());
            k.setUpdatedAt(k.getCreatedAt());
            kits.save(k);
            d = newDraft(k, null, user);
        } else {
            if (k.isBuiltIn()) throw DesignKitException.conflict("Built-in kit '" + key + "' is read-only — import the archive under another key");
            if (!k.getBaseSetKey().equals(base)) {
                throw DesignKitException.conflict("Kit '" + key + "' styles '" + k.getBaseSetKey() + "', the archive '" + base + "'");
            }
            if (draftOf(k).isPresent()) throw DesignKitException.conflict("Kit '" + key + "' already has a draft — publish or discard it first");
            d = newDraft(k, latest(k).map(DesignKitVersionEntity::getVersion).orElse(null), user);
        }
        List<String> problems = new ArrayList<>(TokenValidator.validate(content.tokens(), content.manifest(), content.css() == null ? "" : content.css()));
        d.setTokens(json(content.tokens()));
        d.setManifest(json(content.manifest()));
        if (content.notes() != null) d.setNotes(content.notes().length() > 1000 ? content.notes().substring(0, 1000) : content.notes());
        if (content.css() != null && !content.css().isEmpty()) putFile(d, KIT_CSS, DesignKitFileEntity.Role.CSS, content.css());
        Map<String, KitSourceValidator.Slot> slots = slots(base);
        if (content.files().size() > KitSourceValidator.MAX_FILES) problems.add("an archive may hold at most " + KitSourceValidator.MAX_FILES + " component files");
        for (ResolvedKit.File f : content.files()) {
            List<String> fileProblems = KitSourceValidator.check(f.path(), f.content(), slots);
            if (!fileProblems.isEmpty()) {
                problems.addAll(fileProblems);
                continue;
            }
            putFile(d, f.path(), KitSourceValidator.kindOf(f.path(), slots) == KitSourceValidator.Kind.SLOT
                    ? DesignKitFileEntity.Role.SLOT : DesignKitFileEntity.Role.HELPER, f.content());
        }
        Map<String, String> imported = new LinkedHashMap<>();
        content.files().forEach(f -> imported.put(f.path(), f.content()));
        problems.addAll(TokenValidator.navIconProblems(content.manifest(), imported));
        long total = 0;
        for (ResolvedKit.Asset a : content.assets()) {
            try {
                DesignKitAssetEntity.Role role = parseRole(a.role());
                String type = AssetValidator.check(a.targetPath(), role, a.content());
                if (a.meta() != null && a.meta().length() > 1000) throw DesignKitException.badRequest(a.targetPath() + ": meta must be at most 1000 characters");
                if (role == DesignKitAssetEntity.Role.FONT) fontFace(a.targetPath(), a.meta());
                total += a.content().length;
                DesignKitAssetEntity row = new DesignKitAssetEntity();
                row.setVersionId(d.getId());
                row.setTargetPath(a.targetPath());
                row.setRole(role);
                row.setBlobSha256(storeBlob(a.content(), type));
                row.setMeta(trim(a.meta()));
                assets.save(row);
            } catch (DesignKitException e) {
                problems.add(a.targetPath() + ": " + e.getMessage());
            }
        }
        if (total > AssetValidator.MAX_VERSION_BYTES) {
            problems.add("a kit version may hold at most " + AssetValidator.MAX_VERSION_BYTES / 1024 / 1024 + " MB of assets");
        }
        if (!problems.isEmpty()) {
            // Thrown out of the transaction, so the new kit or draft is rolled back with it.
            throw DesignKitException.invalid(problems);
        }
        d.setUpdatedAt(Instant.now());
        versions.save(d);
        return summary(k);
    }

    // ── Built-in kits ───────────────────────────────────────────────────────

    /**
     * Publishes each built-in kit from the classpath: v1 on a fresh database, and a new version
     * whenever the classpath content no longer matches the latest one (a release changed it).
     */
    @Transactional
    public void seedBuiltIns() {
        for (String key : BuiltInKits.KEYS) {
            BuiltInKits.Loaded loaded = BuiltInKits.load(key, mapper);
            BuiltInKits.Definition def = loaded.definition();
            List<String> problems = TokenValidator.validate(def.tokens(), def.manifest(), loaded.css());
            if (!problems.isEmpty()) throw new IllegalStateException("Built-in design kit " + key + " is invalid: " + problems);
            DesignKitEntity k = kits.findByKitKey(key).orElse(null);
            if (k != null && !k.isBuiltIn()) {
                log.warn("Kit key '{}' is taken by a custom kit — built-in kit not seeded", key);
                continue;
            }
            if (k == null) {
                k = new DesignKitEntity();
                k.setKitKey(key);
                k.setBuiltIn(true);
                k.setCreatedBy(SYSTEM);
                k.setCreatedAt(Instant.now());
            }
            k.setName(def.name());
            k.setDescription(def.description());
            k.setBaseSetKey(def.baseSet());
            k.setDefaultPaletteId(def.defaultPaletteId());
            k.setSortOrder(def.sortOrder() == null ? 0 : def.sortOrder());
            k.setUpdatedAt(Instant.now());
            kits.save(k);

            DesignKitVersionEntity d = newDraft(k, latest(k).map(DesignKitVersionEntity::getVersion).orElse(null), SYSTEM);
            d.setTokens(json(def.tokens()));
            d.setManifest(json(def.manifest()));
            putFile(d, KIT_CSS, DesignKitFileEntity.Role.CSS, loaded.css());
            Map<String, KitSourceValidator.Slot> slots = slots(def.baseSet());
            for (BuiltInKits.LoadedFile f : loaded.files()) {
                List<String> fileProblems = KitSourceValidator.check(f.path(), f.content(), slots);
                if (!fileProblems.isEmpty()) throw new IllegalStateException("Built-in design kit " + key + " is invalid: " + fileProblems);
                putFile(d, f.path(), KitSourceValidator.kindOf(f.path(), slots) == KitSourceValidator.Kind.SLOT
                        ? DesignKitFileEntity.Role.SLOT : DesignKitFileEntity.Role.HELPER, f.content());
            }
            Map<String, String> seeded = new LinkedHashMap<>();
            loaded.files().forEach(f -> seeded.put(f.path(), f.content()));
            List<String> iconProblems = TokenValidator.navIconProblems(def.manifest(), seeded);
            if (!iconProblems.isEmpty()) throw new IllegalStateException("Built-in design kit " + key + " is invalid: " + iconProblems);
            for (BuiltInKits.LoadedAsset a : loaded.assets()) {
                DesignKitAssetEntity.Role role = parseRole(a.role());
                String sha = storeBlob(a.content(), AssetValidator.check(a.targetPath(), role, a.content()));
                DesignKitAssetEntity row = new DesignKitAssetEntity();
                row.setVersionId(d.getId());
                row.setTargetPath(a.targetPath());
                row.setRole(role);
                row.setBlobSha256(sha);
                row.setMeta(a.meta());
                assets.save(row);
            }
            String checksum = checksum(d);
            Optional<DesignKitVersionEntity> current = latest(k);
            if (current.isPresent() && checksum.equals(current.get().getChecksum())) {
                versions.delete(d);
                continue;
            }
            int next = current.map(v -> v.getVersion() + 1).orElse(1);
            seal(d, next, SYSTEM);
            log.info("Published built-in design kit {}@{}", key, next);
        }
        // There is always a house kit: the default look, unless an admin picked another one.
        if (kits.findAll().stream().noneMatch(DesignKitEntity::isDefaultKit)) {
            kits.findByKitKey(BuiltInKits.HOUSE).filter(DesignKitEntity::isBuiltIn).ifPresent(k -> {
                k.setDefaultKit(true);
                kits.save(k);
            });
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private record Ref(String key, Integer version, boolean draft) {
        static Ref parse(String text) {
            String t = text == null ? "" : text.trim();
            int at = t.indexOf('@');
            String key = at < 0 ? t : t.substring(0, at);
            if (!KEY.matcher(key).matches()) throw DesignKitException.badRequest("'" + t + "' is not a design kit (key or key@version)");
            if (at < 0) return new Ref(key, null, false);
            String v = t.substring(at + 1);
            if (v.equals("draft")) return new Ref(key, null, true);
            if (!v.matches("^[1-9]\\d{0,5}$")) throw DesignKitException.badRequest("'" + t + "': the version must be a number or 'draft'");
            return new Ref(key, Integer.parseInt(v), false);
        }
    }

    private DesignKitEntity kit(String key) {
        return kits.findByKitKey(key).orElseThrow(() -> DesignKitException.notFound("No design kit '" + key + "'"));
    }

    private DesignKitEntity editable(String key) {
        DesignKitEntity k = kit(key);
        if (k.isBuiltIn()) throw DesignKitException.conflict("Built-in kit '" + key + "' is read-only — clone it to change it");
        return k;
    }

    private Optional<DesignKitVersionEntity> draftOf(DesignKitEntity k) {
        return versions.findByKitIdAndDraftMarker(k.getId(), 1);
    }

    /** The only path to a writable version: a published one is never handed out for writing. */
    private DesignKitVersionEntity requireDraft(DesignKitEntity k) {
        return draftOf(k).orElseThrow(() -> DesignKitException.conflict(
                "Kit '" + k.getKitKey() + "' has no draft — published versions cannot change; open a draft first"));
    }

    private Optional<DesignKitVersionEntity> latest(DesignKitEntity k) {
        return versions.findFirstByKitIdAndStatusOrderByVersionDesc(k.getId(), Status.PUBLISHED);
    }

    private DesignKitVersionEntity published(DesignKitEntity k, int version) {
        return versions.findByKitIdAndVersion(k.getId(), version)
                .orElseThrow(() -> DesignKitException.notFound("Kit '" + k.getKitKey() + "' has no version " + version));
    }

    private DesignKitVersionEntity select(DesignKitEntity k, String which) {
        if (which == null || which.equals("latest")) {
            return latest(k).orElseThrow(() -> DesignKitException.notFound("Kit '" + k.getKitKey() + "' has no published version"));
        }
        if (which.equals("draft")) return requireDraft(k);
        try {
            return published(k, Integer.parseInt(which));
        } catch (NumberFormatException e) {
            throw DesignKitException.badRequest("version must be a number, 'latest' or 'draft'");
        }
    }

    private DesignKitVersionEntity newDraft(DesignKitEntity k, Integer basedOn, String user) {
        DesignKitVersionEntity d = new DesignKitVersionEntity();
        d.setKitId(k.getId());
        d.setStatus(Status.DRAFT);
        d.setDraftMarker(1);
        d.setBasedOnVersion(basedOn);
        d.setContractVersion(CONTRACT_VERSION);
        d.setTokens(json(new DesignTokens(DesignTokens.SCHEMA, List.of())));
        d.setManifest(json(new KitManifest(List.of(), null, null)));
        d.setCreatedBy(user);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(d.getCreatedAt());
        return versions.saveAndFlush(d);
    }

    private void copyInto(DesignKitVersionEntity from, DesignKitVersionEntity to) {
        to.setTokens(from.getTokens());
        to.setManifest(from.getManifest());
        to.setNotes(null);
        versions.save(to);
        for (DesignKitFileEntity f : files.findByVersionIdOrderByPathAsc(from.getId())) {
            putFile(to, f.getPath(), f.getRole(), f.getContent());
        }
        for (DesignKitAssetEntity a : assets.findByVersionIdOrderByTargetPathAsc(from.getId())) {
            DesignKitAssetEntity c = new DesignKitAssetEntity();
            c.setVersionId(to.getId());
            c.setTargetPath(a.getTargetPath());
            c.setRole(a.getRole());
            c.setBlobSha256(a.getBlobSha256());
            c.setMeta(a.getMeta());
            assets.save(c);
        }
    }

    private void seal(DesignKitVersionEntity d, int version, String user) {
        d.setChecksum(checksum(d));
        d.setVersion(version);
        d.setStatus(Status.PUBLISHED);
        d.setDraftMarker(null);
        d.setContractVersion(CONTRACT_VERSION);
        d.setPublishedBy(user);
        d.setPublishedAt(Instant.now());
        d.setUpdatedAt(d.getPublishedAt());
        versions.save(d);
    }

    private void putFile(DesignKitVersionEntity d, String path, DesignKitFileEntity.Role role, String content) {
        DesignKitFileEntity f = files.findByVersionIdAndPath(d.getId(), path).orElseGet(DesignKitFileEntity::new);
        f.setVersionId(d.getId());
        f.setPath(path);
        f.setRole(role);
        f.setContent(content);
        f.setSha256(sha256(content.getBytes(StandardCharsets.UTF_8)));
        files.save(f);
    }

    private String storeBlob(byte[] content, String contentType) {
        String sha = sha256(content);
        if (!blobs.existsById(sha)) {
            DesignAssetBlobEntity b = new DesignAssetBlobEntity();
            b.setSha256(sha);
            b.setContentType(contentType);
            b.setSizeBytes(content.length);
            b.setContent(content);
            b.setCreatedAt(Instant.now());
            blobs.save(b);
        }
        return sha;
    }

    private ValidationReport validate(DesignKitEntity k, DesignKitVersionEntity v) {
        List<String> problems = new ArrayList<>();
        DesignTokens tokens;
        KitManifest manifest;
        try {
            tokens = tokensOf(v);
            manifest = manifestOf(v);
        } catch (IllegalStateException e) {
            return new ValidationReport(false, List.of(e.getMessage()));
        }
        problems.addAll(TokenValidator.validate(tokens, manifest, cssOf(v)));
        if (sets.findBySetKey(k.getBaseSetKey()).isEmpty()) {
            problems.add("base template set '" + k.getBaseSetKey() + "' does not exist");
        }
        // Component files are checked against the base set as it is now: a slot it dropped, or an
        // export it renamed, is a problem to fix before the next publish.
        Map<String, KitSourceValidator.Slot> slots = slots(k.getBaseSetKey());
        for (DesignKitFileEntity f : componentFiles(v)) problems.addAll(KitSourceValidator.check(f.getPath(), f.getContent(), slots));
        problems.addAll(TokenValidator.navIconProblems(manifest, fileMap(v)));
        for (DesignKitAssetEntity a : assets.findByVersionIdOrderByTargetPathAsc(v.getId())) {
            if (a.getRole() == DesignKitAssetEntity.Role.FONT) {
                try {
                    fontFace(a);
                } catch (DesignKitException e) {
                    problems.add(e.getMessage());
                }
            }
        }
        return new ValidationReport(problems.isEmpty(), problems);
    }

    /** {@code @font-face} for a FONT asset, from its meta {@code {family, weight, style}}. */
    KitCssWriter.FontFace fontFace(DesignKitAssetEntity a) {
        return fontFace(a.getTargetPath(), a.getMeta());
    }

    KitCssWriter.FontFace fontFace(String targetPath, String meta) {
        Map<String, Object> m;
        try {
            m = meta == null ? Map.of() : mapper.readValue(meta, Map.class);
        } catch (JsonProcessingException e) {
            throw DesignKitException.badRequest("font " + targetPath + ": meta is not JSON");
        }
        Object family = m.get("family");
        if (!(family instanceof String f) || !f.matches("^[A-Za-z][A-Za-z0-9 _-]{0,60}$")) {
            throw DesignKitException.badRequest("font " + targetPath + ": meta.family must be a font name (letters, digits, spaces)");
        }
        int weight = m.get("weight") instanceof Number n ? n.intValue() : 400;
        if (weight < 100 || weight > 900 || weight % 100 != 0) throw DesignKitException.badRequest("font " + targetPath + ": weight must be 100–900");
        String style = "italic".equals(m.get("style")) ? "italic" : "normal";
        String format = targetPath.endsWith(".woff2") ? "woff2" : "woff";
        return new KitCssWriter.FontFace(f, weight, style, "./" + targetPath.substring("src/".length()), format);
    }

    String checksum(DesignKitVersionEntity v) {
        StringBuilder canonical = new StringBuilder()
                .append(v.getTokens()).append('\n')
                .append(v.getManifest()).append('\n');
        for (DesignKitFileEntity f : files.findByVersionIdOrderByPathAsc(v.getId())) {
            canonical.append("file ").append(f.getPath()).append(' ').append(f.getRole()).append(' ').append(f.getSha256()).append('\n');
        }
        for (DesignKitAssetEntity a : assets.findByVersionIdOrderByTargetPathAsc(v.getId())) {
            canonical.append("asset ").append(a.getTargetPath()).append(' ').append(a.getRole()).append(' ')
                    .append(a.getBlobSha256()).append(' ').append(Objects.toString(a.getMeta(), "")).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private KitSummary summary(DesignKitEntity k) {
        Optional<DesignKitVersionEntity> latest = latest(k);
        KitManifest manifest = latest.map(this::manifestOf).orElse(null);
        boolean paletteAware = latest.map(v -> v.getTokens().contains("{palette.primary}")
                || v.getTokens().contains("{palette.secondary}")).orElse(false);
        Map<String, String> swatch = new LinkedHashMap<>();
        latest.map(this::tokensOf).ifPresent(t -> SWATCH.forEach(name -> t.tokens().stream()
                .filter(x -> x.name().equals(name)).findFirst().ifPresent(x -> swatch.put(name, x.value()))));
        // The frontend sets that stand for this (built-in) kit and generate as it.
        List<String> aliases = !k.isBuiltIn() ? List.of() : BuiltInKits.FOR_DESIGN_SYSTEM.entrySet().stream()
                .filter(e -> e.getValue().equals(k.getKitKey()))
                .flatMap(e -> sets.findAllByKindOrderBySortOrderAsc(EntityTemplateSetEntity.Kind.FRONTEND_REACT).stream()
                        .filter(set -> set.getDesignSystem() == e.getKey())
                        .map(EntityTemplateSetEntity::getSetKey))
                .toList();
        return new KitSummary(k.getKitKey(), k.getName(), k.getDescription(), k.getBaseSetKey(), k.isBuiltIn(),
                k.isEnabled(), k.getSortOrder(), k.getDefaultPaletteId(), k.getClonedFrom(),
                latest.map(DesignKitVersionEntity::getVersion).orElse(null), draftOf(k).isPresent(), paletteAware,
                manifest == null ? null : manifest.defaults(), k.isDefaultKit(),
                manifest == null ? null : manifest.traits(), aliases, swatch,
                versions.findByKitIdAndStatusOrderByVersionDesc(k.getId(), Status.PUBLISHED).stream()
                        .filter(DesignKitVersionEntity::isDeprecated)
                        .map(v -> new DeprecatedVersion(v.getVersion(), v.getDeprecationNote()))
                        .toList(),
                latest.flatMap(v -> assets.findByVersionIdOrderByTargetPathAsc(v.getId()).stream()
                        .filter(a -> a.getRole() == DesignKitAssetEntity.Role.THUMBNAIL).findFirst())
                        .map(a -> "/metadata/design/assets/" + a.getBlobSha256()).orElse(null),
                manifest == null || manifest.navIcons() == null ? List.of() : manifest.navIcons().names());
    }

    /** A version's component files, path → source. */
    private Map<String, String> fileMap(DesignKitVersionEntity v) {
        Map<String, String> out = new LinkedHashMap<>();
        componentFiles(v).forEach(f -> out.put(f.getPath(), f.getContent()));
        return out;
    }

    private VersionView view(DesignKitEntity k, DesignKitVersionEntity v) {
        List<AssetView> av = assets.findByVersionIdOrderByTargetPathAsc(v.getId()).stream().map(this::assetView).toList();
        return new VersionView(k.getKitKey(), v.getVersion(), v.getStatus().name(), v.getBasedOnVersion(), v.getChecksum(),
                v.getNotes(), v.getCreatedAt(), v.getPublishedAt(), v.getPublishedBy(), tokensOf(v), manifestOf(v),
                cssOf(v), av, componentFiles(v).stream().map(this::fileView).toList());
    }

    private AssetView assetView(DesignKitAssetEntity a) {
        DesignAssetBlobEntity b = blobs.findById(a.getBlobSha256()).orElseThrow();
        return new AssetView(a.getTargetPath(), a.getRole().name(), b.getContentType(), b.getSizeBytes(), b.getSha256(),
                a.getMeta(), "/metadata/design/assets/" + b.getSha256());
    }

    private Map<String, String> assetShas(DesignKitVersionEntity v) {
        Map<String, String> out = new LinkedHashMap<>();
        assets.findByVersionIdOrderByTargetPathAsc(v.getId()).forEach(a -> out.put(a.getTargetPath(), a.getBlobSha256() + a.getMeta()));
        return out;
    }

    private DesignTokens tokensOf(DesignKitVersionEntity v) {
        try {
            return mapper.readValue(v.getTokens(), DesignTokens.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tokens are not valid JSON: " + e.getOriginalMessage());
        }
    }

    private KitManifest manifestOf(DesignKitVersionEntity v) {
        try {
            return mapper.readValue(v.getManifest(), KitManifest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private String cssOf(DesignKitVersionEntity v) {
        return files.findByVersionIdAndPath(v.getId(), KIT_CSS).map(DesignKitFileEntity::getContent).orElse("");
    }

    private static Map<String, DesignTokens.Token> byName(DesignTokens tokens) {
        Map<String, DesignTokens.Token> out = new LinkedHashMap<>();
        tokens.tokens().forEach(t -> out.put(t.name(), t));
        return out;
    }

    private static String describe(DesignTokens.Token t) {
        return t.dark() == null ? t.value() : t.value() + " / dark " + t.dark();
    }

    private static String label(DesignKitVersionEntity v) {
        return v.getVersion() == null ? "draft" : String.valueOf(v.getVersion());
    }

    private static DesignKitAssetEntity.Role parseRole(String role) {
        try {
            return DesignKitAssetEntity.Role.valueOf(role == null ? "" : role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw DesignKitException.badRequest("role must be one of LOGO, FAVICON, FONT, ICON, IMAGE");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw DesignKitException.badRequest("not serializable: " + e.getOriginalMessage());
        }
    }

    private static String requireName(String name) {
        String n = trim(name);
        if (n == null) throw DesignKitException.badRequest("name is required");
        if (n.length() > 150) throw DesignKitException.badRequest("name must be at most 150 characters");
        return n;
    }

    private static String description(String description) {
        String d = trim(description);
        if (d != null && d.length() > 500) throw DesignKitException.badRequest("description must be at most 500 characters");
        return d;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
