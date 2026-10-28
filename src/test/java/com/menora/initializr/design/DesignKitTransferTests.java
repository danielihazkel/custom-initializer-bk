package com.menora.initializr.design;

import com.menora.initializr.TestInvokerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kits in the admin export: exported with their assets, merged back by key@version, never rewritten. */
@SpringBootTest
@Import(TestInvokerConfiguration.class)
@Transactional
class DesignKitTransferTests {

    @Autowired
    private DesignKitService service;
    @Autowired
    private DesignKitTransfer transfer;

    @Test
    void exportThenImportRoundTripsAndIsIdempotent() {
        String key = "xfer-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(key, "Transfer", null, "tailwind-default"), "t");
        service.putAsset(key, "public/brand.svg", "LOGO", null, FullstackDesignKitTests.SVG);
        service.putComponentFile(key, "src/shared/ui/kit/tone.ts", KitComponentsTests.TONE);
        String checksum = service.publish(key, null, "t").checksum();

        DesignKitTransfer.Export export = transfer.export();
        assertThat(export.kits()).noneMatch(k -> BuiltInKits.KEYS.contains(k.key()));
        DesignKitTransfer.KitExport mine = export.kits().stream().filter(k -> k.key().equals(key)).findFirst().orElseThrow();
        assertThat(mine.versions()).singleElement().satisfies(v -> assertThat(v.assets()).hasSize(1));
        assertThat(export.blobs()).anyMatch(b -> b.sha256().equals(mine.versions().get(0).assets().get(0).blobSha256()));

        // The same content again adds nothing.
        assertThat(transfer.merge(export)).isZero();

        // Into a database without the kit, it arrives with the same checksum.
        service.delete(key);
        assertThat(transfer.merge(new DesignKitTransfer.Export(List.of(mine), export.blobs()))).isEqualTo(1);
        assertThat(service.version(key, "1").checksum()).isEqualTo(checksum);
        assertThat(service.version(key, "1").assets()).hasSize(1);
        assertThat(service.fileContent(key, "1", "src/shared/ui/kit/tone.ts")).isEqualTo(KitComponentsTests.TONE);
    }

    @Test
    void aDifferentPublishedVersionUnderTheSameNumberIsRefused() {
        String key = "xfer-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(key, "Transfer", null, "tailwind-default"), "t");
        service.publish(key, null, "t");
        DesignKitTransfer.KitExport mine = transfer.export().kits().stream().filter(k -> k.key().equals(key)).findFirst().orElseThrow();
        DesignKitTransfer.VersionExport v = mine.versions().get(0);
        DesignKitTransfer.VersionExport tampered = new DesignKitTransfer.VersionExport(v.version(), v.basedOnVersion(),
                v.contractVersion(), v.tokens(), v.manifest(), "0".repeat(64), v.notes(), v.publishedBy(), v.publishedAt(),
                v.files(), v.assets());
        DesignKitTransfer.KitExport changed = new DesignKitTransfer.KitExport(mine.key(), mine.name(), mine.description(),
                mine.baseSetKey(), mine.defaultPaletteId(), mine.clonedFrom(), mine.enabled(), mine.sortOrder(), List.of(tampered));
        assertThatThrownBy(() -> transfer.check(List.of(changed)))
                .isInstanceOfSatisfying(DesignKitException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        DesignKitTransfer.KitExport builtIn = new DesignKitTransfer.KitExport("menora-digital", "x", null, "react-menora-digital-crud",
                null, null, true, 0, List.of());
        assertThatThrownBy(() -> transfer.check(List.of(builtIn))).hasMessageContaining("built-in");
    }
}
