package com.menora.initializr.design;

import com.menora.initializr.db.entity.DesignAssetBlobEntity;
import com.menora.initializr.db.entity.DesignKitAssetEntity;
import com.menora.initializr.db.entity.DesignKitEntity;
import com.menora.initializr.db.entity.DesignKitFileEntity;
import com.menora.initializr.db.entity.DesignKitVersionEntity;
import com.menora.initializr.db.repository.DesignAssetBlobRepository;
import com.menora.initializr.db.repository.DesignKitAssetRepository;
import com.menora.initializr.db.repository.DesignKitFileRepository;
import com.menora.initializr.db.repository.DesignKitRepository;
import com.menora.initializr.db.repository.DesignKitVersionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Design kits in the admin configuration export. Unlike the catalog, import <em>merges</em>: a
 * published version is immutable and team models pin it, so it is added when missing, skipped
 * when the database holds the same content, and refused (before anything is wiped) when the same
 * {@code key@version} holds different content. Built-in kits come from the classpath and are
 * neither exported nor imported.
 */
@Component
public class DesignKitTransfer {

    public record KitExport(String key, String name, String description, String baseSetKey, String defaultPaletteId,
                            String clonedFrom, boolean enabled, int sortOrder, List<VersionExport> versions) {
    }

    /** {@code version} null is the kit's draft. */
    public record VersionExport(Integer version, Integer basedOnVersion, int contractVersion, String tokens, String manifest,
                                String checksum, String notes, String publishedBy, Instant publishedAt,
                                List<FileExport> files, List<AssetExport> assets) {
    }

    public record FileExport(String path, String role, String content) {
    }

    public record AssetExport(String targetPath, String role, String blobSha256, String meta) {
    }

    public record BlobExport(String sha256, String contentType, String base64) {
    }

    public record Export(List<KitExport> kits, List<BlobExport> blobs) {
    }

    private final DesignKitRepository kits;
    private final DesignKitVersionRepository versions;
    private final DesignKitFileRepository files;
    private final DesignKitAssetRepository assets;
    private final DesignAssetBlobRepository blobs;
    private final DesignKitService service;

    public DesignKitTransfer(DesignKitRepository kits, DesignKitVersionRepository versions, DesignKitFileRepository files,
                             DesignKitAssetRepository assets, DesignAssetBlobRepository blobs, DesignKitService service) {
        this.kits = kits;
        this.versions = versions;
        this.files = files;
        this.assets = assets;
        this.blobs = blobs;
        this.service = service;
    }

    public Export export() {
        List<KitExport> out = new ArrayList<>();
        Map<String, BlobExport> usedBlobs = new LinkedHashMap<>();
        for (DesignKitEntity k : kits.findAllByOrderBySortOrderAscIdAsc()) {
            if (k.isBuiltIn()) continue;
            List<VersionExport> vs = new ArrayList<>();
            List<DesignKitVersionEntity> rows = new ArrayList<>(
                    versions.findByKitIdAndStatusOrderByVersionDesc(k.getId(), DesignKitVersionEntity.Status.PUBLISHED));
            versions.findByKitIdAndDraftMarker(k.getId(), 1).ifPresent(rows::add);
            for (DesignKitVersionEntity v : rows) {
                List<AssetExport> as = assets.findByVersionIdOrderByTargetPathAsc(v.getId()).stream().map(a -> {
                    usedBlobs.computeIfAbsent(a.getBlobSha256(), sha -> blobs.findById(sha)
                            .map(b -> new BlobExport(sha, b.getContentType(), Base64.getEncoder().encodeToString(b.getContent())))
                            .orElseThrow());
                    return new AssetExport(a.getTargetPath(), a.getRole().name(), a.getBlobSha256(), a.getMeta());
                }).toList();
                vs.add(new VersionExport(v.getVersion(), v.getBasedOnVersion(), v.getContractVersion(), v.getTokens(),
                        v.getManifest(), v.getChecksum(), v.getNotes(), v.getPublishedBy(), v.getPublishedAt(),
                        files.findByVersionIdOrderByPathAsc(v.getId()).stream()
                                .map(f -> new FileExport(f.getPath(), f.getRole().name(), f.getContent())).toList(),
                        as));
            }
            out.add(new KitExport(k.getKitKey(), k.getName(), k.getDescription(), k.getBaseSetKey(), k.getDefaultPaletteId(),
                    k.getClonedFrom(), k.isEnabled(), k.getSortOrder(), vs));
        }
        return new Export(out, new ArrayList<>(usedBlobs.values()));
    }

    /** Refuses an import that would change a published version; runs before the catalog is wiped. */
    public void check(List<KitExport> imported) {
        for (KitExport ke : imported == null ? List.<KitExport>of() : imported) {
            if (ke.key() == null || !DesignKitService.KEY.matcher(ke.key()).matches()) {
                throw DesignKitException.badRequest("designKits: '" + ke.key() + "' is not a kit key");
            }
            Optional<DesignKitEntity> local = kits.findByKitKey(ke.key());
            if (local.isPresent() && local.get().isBuiltIn()) {
                throw DesignKitException.conflict("designKits: '" + ke.key() + "' is a built-in kit and cannot be imported");
            }
            if (local.isEmpty()) continue;
            for (VersionExport ve : ke.versions()) {
                if (ve.version() == null) continue;
                versions.findByKitIdAndVersion(local.get().getId(), ve.version()).ifPresent(existing -> {
                    if (!existing.getChecksum().equals(ve.checksum())) {
                        throw DesignKitException.conflict("designKits: " + ke.key() + "@" + ve.version()
                                + " already exists here with different content — published versions never change");
                    }
                });
            }
        }
    }

    /** Merges the imported kits in; returns how many versions were added. */
    public int merge(Export data) {
        if (data == null || data.kits() == null) return 0;
        check(data.kits());
        Map<String, BlobExport> blobsBySha = new LinkedHashMap<>();
        for (BlobExport b : data.blobs() == null ? List.<BlobExport>of() : data.blobs()) blobsBySha.put(b.sha256(), b);
        int added = 0;
        for (KitExport ke : data.kits()) {
            DesignKitEntity k = kits.findByKitKey(ke.key()).orElse(null);
            if (k == null) {
                k = new DesignKitEntity();
                k.setKitKey(ke.key());
                k.setName(ke.name());
                k.setDescription(ke.description());
                k.setBaseSetKey(ke.baseSetKey());
                k.setDefaultPaletteId(ke.defaultPaletteId());
                k.setClonedFrom(ke.clonedFrom());
                k.setEnabled(ke.enabled());
                k.setSortOrder(ke.sortOrder());
                k.setCreatedBy("import");
                k.setCreatedAt(Instant.now());
                k.setUpdatedAt(k.getCreatedAt());
                kits.save(k);
            }
            for (VersionExport ve : ke.versions()) {
                boolean draft = ve.version() == null;
                if (draft ? versions.findByKitIdAndDraftMarker(k.getId(), 1).isPresent()
                        : versions.findByKitIdAndVersion(k.getId(), ve.version()).isPresent()) {
                    continue; // the same published content (checked), or a local draft that wins
                }
                DesignKitVersionEntity v = new DesignKitVersionEntity();
                v.setKitId(k.getId());
                v.setVersion(ve.version());
                v.setStatus(draft ? DesignKitVersionEntity.Status.DRAFT : DesignKitVersionEntity.Status.PUBLISHED);
                v.setDraftMarker(draft ? 1 : null);
                v.setBasedOnVersion(ve.basedOnVersion());
                v.setContractVersion(ve.contractVersion());
                v.setTokens(ve.tokens());
                v.setManifest(ve.manifest());
                v.setNotes(ve.notes());
                v.setCreatedBy("import");
                v.setCreatedAt(Instant.now());
                v.setUpdatedAt(v.getCreatedAt());
                v.setPublishedBy(ve.publishedBy());
                v.setPublishedAt(ve.publishedAt());
                versions.saveAndFlush(v);
                for (FileExport fe : ve.files()) {
                    DesignKitFileEntity f = new DesignKitFileEntity();
                    f.setVersionId(v.getId());
                    f.setPath(fe.path());
                    f.setRole(DesignKitFileEntity.Role.valueOf(fe.role()));
                    f.setContent(fe.content());
                    f.setSha256(DesignKitService.sha256(fe.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    files.save(f);
                }
                for (AssetExport ae : ve.assets()) {
                    if (!blobs.existsById(ae.blobSha256())) {
                        BlobExport be = blobsBySha.get(ae.blobSha256());
                        if (be == null) throw DesignKitException.badRequest("designKits: asset " + ae.targetPath() + " has no blob in the export");
                        byte[] bytes = Base64.getDecoder().decode(be.base64());
                        if (!DesignKitService.sha256(bytes).equals(ae.blobSha256())) {
                            throw DesignKitException.badRequest("designKits: blob " + ae.blobSha256() + " does not match its content");
                        }
                        DesignAssetBlobEntity b = new DesignAssetBlobEntity();
                        b.setSha256(ae.blobSha256());
                        b.setContentType(be.contentType());
                        b.setSizeBytes(bytes.length);
                        b.setContent(bytes);
                        b.setCreatedAt(Instant.now());
                        blobs.save(b);
                    }
                    DesignKitAssetEntity a = new DesignKitAssetEntity();
                    a.setVersionId(v.getId());
                    a.setTargetPath(ae.targetPath());
                    a.setRole(DesignKitAssetEntity.Role.valueOf(ae.role()));
                    a.setBlobSha256(ae.blobSha256());
                    a.setMeta(ae.meta());
                    assets.save(a);
                }
                files.flush();
                assets.flush();
                if (!draft) {
                    String checksum = service.checksum(v);
                    if (!checksum.equals(ve.checksum())) {
                        throw DesignKitException.badRequest("designKits: " + ke.key() + "@" + ve.version() + " does not match its checksum");
                    }
                    v.setChecksum(checksum);
                    versions.save(v);
                }
                added++;
            }
        }
        return added;
    }
}
