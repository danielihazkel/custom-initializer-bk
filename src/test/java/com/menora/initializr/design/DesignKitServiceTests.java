package com.menora.initializr.design;

import com.menora.initializr.TestInvokerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kit lifecycle: one draft, immutable published versions, read-only built-ins, rollback, diff. */
@SpringBootTest
@Import(TestInvokerConfiguration.class)
class DesignKitServiceTests {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DesignKitService service;

    @Test
    void builtInKitsAreSeededPublishedAndReadOnly() {
        for (String key : BuiltInKits.KEYS) {
            DesignKitService.KitSummary kit = service.get(key);
            assertThat(kit.builtIn()).isTrue();
            assertThat(kit.latestVersion()).isNotNull();
            assertStatus(() -> service.openDraft(key, null, "t"), HttpStatus.CONFLICT);
            assertStatus(() -> service.delete(key), HttpStatus.CONFLICT);
        }
        assertThat(service.get("tailwind-default").paletteAware()).isTrue();
        assertThat(service.get("menora-digital").paletteAware()).isFalse();
        assertThat(service.get("menora-digital").defaults().locale()).isEqualTo("he");
    }

    @Test
    void seedingAgainWithUnchangedContentPublishesNothing() {
        Integer before = service.get("menora-digital").latestVersion();
        service.seedBuiltIns();
        assertThat(service.get("menora-digital").latestVersion()).isEqualTo(before);
    }

    @Test
    void aCloneStartsAsADraftAndPublishesVersionOne() {
        String key = newKit();
        assertThat(service.get(key).latestVersion()).isNull();
        assertThat(service.get(key).hasDraft()).isTrue();
        assertThat(service.get(key).clonedFrom()).startsWith("tailwind-default@");
        assertThat(service.list(true)).noneMatch(k -> k.key().equals(key)); // nothing to generate yet

        DesignKitService.VersionView v1 = service.publish(key, "first", "t");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.checksum()).hasSize(64);
        assertThat(service.get(key).hasDraft()).isFalse();
        assertThat(service.list(true)).anyMatch(k -> k.key().equals(key));
    }

    @Test
    void publishedVersionsCannotChange() {
        String key = newKit();
        service.publish(key, null, "t");
        assertStatus(() -> service.saveDraft(key, new DesignKitService.SaveDraft(null, null, "x", null)), HttpStatus.CONFLICT);
        assertStatus(() -> service.putAsset(key, "public/x.svg", "IMAGE", null, FullstackDesignKitTests.SVG), HttpStatus.CONFLICT);
        assertStatus(() -> service.publish(key, null, "t"), HttpStatus.CONFLICT);

        service.openDraft(key, null, "t");
        assertStatus(() -> service.openDraft(key, null, "t"), HttpStatus.CONFLICT); // one draft at a time
    }

    @Test
    void anInvalidDraftIsNotPublished() {
        String key = newKit();
        service.saveDraft(key, new DesignKitService.SaveDraft(new DesignTokens(DesignTokens.SCHEMA, List.of(
                new DesignTokens.Token("color-brand", null, "red; } body { background: url(x)", null, null))), null, null, null));
        DesignKitService.ValidationReport report = service.validateDraft(key);
        assertThat(report.ok()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.startsWith("token 'color-brand' value"))
                .anyMatch(p -> p.contains("'color-fg' is required"));
        assertThatThrownBy(() -> service.publish(key, null, "t"))
                .isInstanceOfSatisfying(DesignKitException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.problems()).isNotEmpty();
                });
    }

    @Test
    void restoreDiffAndChecksum() {
        String key = newKit();
        DesignKitService.VersionView v1 = service.publish(key, null, "t");
        service.openDraft(key, null, "t");
        service.saveDraft(key, new DesignKitService.SaveDraft(
                FullstackDesignKitTests.withBrand(service.draft(key).tokens(), "#123456"), null, null, null));
        service.publish(key, null, "t");

        DesignKitService.Diff diff = service.diff(key, "1", "2");
        assertThat(diff.tokens()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("color-brand");
            assertThat(c.change()).isEqualTo("changed");
            assertThat(c.after()).isEqualTo("#123456");
        });
        assertThat(diff.cssChanged()).isFalse();

        DesignKitService.VersionView restored = service.restore(key, 1, "t");
        assertThat(restored.basedOnVersion()).isEqualTo(1);
        DesignKitService.VersionView v3 = service.publish(key, "rollback", "t");
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.checksum()).isEqualTo(v1.checksum()); // same content, same checksum
        assertThat(service.versions(key)).extracting(DesignKitService.VersionSummary::version).containsExactly(3, 2, 1);
    }

    @Test
    void assetsAreCheckedByTheirBytes() {
        String key = newKit();
        assertStatus(() -> service.putAsset(key, "public/logo.png", "LOGO", null, FullstackDesignKitTests.SVG), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.putAsset(key, "../etc/passwd.svg", "LOGO", null, FullstackDesignKitTests.SVG), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.putAsset(key, "public/x.svg", "LOGO", null,
                "<svg onload=\"alert(1)\"></svg>".getBytes()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.putAsset(key, "src/assets/fonts/f.woff2", "FONT", "{\"family\":\"F\"}",
                FullstackDesignKitTests.SVG), HttpStatus.BAD_REQUEST);
        DesignKitService.AssetView logo = service.putAsset(key, "public/logo.svg", "LOGO", null, FullstackDesignKitTests.SVG);
        assertThat(logo.contentType()).isEqualTo("image/svg+xml");
        assertThat(logo.url()).isEqualTo("/metadata/design/assets/" + logo.sha256());
        service.putAsset(key, "src/assets/fonts/f.woff2", "FONT", "{\"family\":\"Bad;Name\"}", FullstackDesignKitTests.WOFF2);
        assertThat(service.validateDraft(key).problems()).anyMatch(p -> p.contains("meta.family"));
    }

    private String newKit() {
        String key = "kit-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(key, "Kit " + key, null, "tailwind-default"), "t");
        return key;
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DesignKitException.class, e -> assertThat(e.status()).isEqualTo(status));
    }
}
