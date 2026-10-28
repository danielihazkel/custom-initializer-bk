package com.menora.initializr.design;

import com.menora.initializr.db.entity.DesignAssetBlobEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Design kits as the generator UI reads them: the enabled kits with their latest published
 * version, any published version in full (immutable, so cached by its checksum) and asset bytes.
 */
@RestController
@RequestMapping("/metadata/design")
public class DesignKitController {

    private final DesignKitService service;

    public DesignKitController(DesignKitService service) {
        this.service = service;
    }

    /** What a kit can do with this generator (see {@link DesignContract}). */
    @GetMapping("/contract")
    public Map<String, Object> contract() {
        return DesignContract.describe();
    }

    @GetMapping("/kits")
    public List<DesignKitService.KitSummary> kits() {
        return service.list(true);
    }

    @GetMapping("/kits/{key}/versions")
    public List<DesignKitService.VersionSummary> versions(@PathVariable String key) {
        return service.versions(key);
    }

    /** A published version by number or {@code latest}; drafts are admin-only. */
    @GetMapping("/kits/{key}/versions/{version}")
    public ResponseEntity<DesignKitService.VersionView> version(@PathVariable String key, @PathVariable String version) {
        if ("draft".equals(version)) throw DesignKitException.notFound("Drafts are only visible in the Design Studio");
        DesignKitService.VersionView v = service.version(key, version);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok().eTag("\"" + v.checksum() + "\"");
        if (!"latest".equals(version)) ok.cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        return ok.body(v);
    }

    /** Asset bytes by content hash — immutable; served so a browser never sniffs or runs them. */
    @GetMapping("/assets/{sha256}")
    public ResponseEntity<byte[]> asset(@PathVariable String sha256) {
        DesignAssetBlobEntity b = service.blob(sha256)
                .orElseThrow(() -> DesignKitException.notFound("No asset " + sha256));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(b.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(b.getContent());
    }
}
