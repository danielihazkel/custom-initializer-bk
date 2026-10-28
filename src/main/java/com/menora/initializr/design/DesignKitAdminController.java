package com.menora.initializr.design;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The Design Studio's API: kits, their draft, its assets, publishing and history. Behind the
 * {@code /admin/*} token filter like the rest of the admin API.
 */
@RestController
@RequestMapping("/admin/design/kits")
public class DesignKitAdminController {

    /** The admin API has one shared password and no user names. */
    private static final String ADMIN = "admin";

    private final DesignKitService service;

    public DesignKitAdminController(DesignKitService service) {
        this.service = service;
    }

    @GetMapping
    public List<DesignKitService.KitSummary> list() {
        return service.list(false);
    }

    @PostMapping
    public ResponseEntity<DesignKitService.KitSummary> create(@RequestBody DesignKitService.CreateKit body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body, ADMIN));
    }

    @GetMapping("/{key}")
    public DesignKitService.KitSummary get(@PathVariable String key) {
        return service.get(key);
    }

    @PutMapping("/{key}")
    public DesignKitService.KitSummary update(@PathVariable String key, @RequestBody DesignKitService.UpdateKit body) {
        return service.update(key, body);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        service.delete(key);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{key}/draft")
    public DesignKitService.VersionView draft(@PathVariable String key) {
        return service.draft(key);
    }

    /** Opens a draft from the latest published version, or {@code ?from=N}. */
    @PostMapping("/{key}/draft")
    public ResponseEntity<DesignKitService.VersionView> openDraft(@PathVariable String key,
                                                                   @RequestParam(required = false) Integer from) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.openDraft(key, from, ADMIN));
    }

    @PutMapping("/{key}/draft")
    public DesignKitService.VersionView saveDraft(@PathVariable String key, @RequestBody DesignKitService.SaveDraft body) {
        return service.saveDraft(key, body);
    }

    @DeleteMapping("/{key}/draft")
    public ResponseEntity<Void> discardDraft(@PathVariable String key) {
        service.discardDraft(key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{key}/draft/assets")
    public ResponseEntity<DesignKitService.AssetView> putAsset(@PathVariable String key,
                                                               @RequestParam("file") MultipartFile file,
                                                               @RequestParam String targetPath,
                                                               @RequestParam String role,
                                                               @RequestParam(required = false) String meta) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.putAsset(key, targetPath, role, meta, file.getBytes()));
    }

    @DeleteMapping("/{key}/draft/assets")
    public ResponseEntity<Void> deleteAsset(@PathVariable String key, @RequestParam String targetPath) {
        service.deleteAsset(key, targetPath);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{key}/draft/validate")
    public DesignKitService.ValidationReport validate(@PathVariable String key) {
        return service.validateDraft(key);
    }

    @PostMapping("/{key}/draft/publish")
    public DesignKitService.VersionView publish(@PathVariable String key, @RequestBody(required = false) Map<String, String> body) {
        return service.publish(key, body == null ? null : body.get("notes"), ADMIN);
    }

    @GetMapping("/{key}/versions")
    public List<DesignKitService.VersionSummary> versions(@PathVariable String key) {
        return service.versions(key);
    }

    /** A version by number, or {@code latest} / {@code draft}. */
    @GetMapping("/{key}/versions/{version}")
    public DesignKitService.VersionView version(@PathVariable String key, @PathVariable String version) {
        return service.version(key, version);
    }

    /** Rollback: the draft becomes a copy of version N (publish it to make it current). */
    @PostMapping("/{key}/versions/{version}/restore")
    public DesignKitService.VersionView restore(@PathVariable String key, @PathVariable int version) {
        return service.restore(key, version, ADMIN);
    }

    @GetMapping("/{key}/diff")
    public DesignKitService.Diff diff(@PathVariable String key, @RequestParam String from, @RequestParam String to) {
        return service.diff(key, from, to);
    }
}
