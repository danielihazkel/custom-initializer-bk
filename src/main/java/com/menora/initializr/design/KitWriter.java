package com.menora.initializr.design;

import com.menora.initializr.config.GeneratedProjectFiles;
import com.menora.initializr.extension.frontend.PackageJsonBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies a design kit to a rendered fullstack frontend, after its template set and the substrate
 * tooling are in place: {@code src/index.css} from the kit's tokens, {@code src/main.tsx} and the
 * {@code package.json} font for its typeface, its asset files, and its component files — a slot
 * only where the set wrote that component under this request's flags (a gated file the set left
 * out stays out), a helper always. A built-in kit reproduces its set's own files byte for byte
 * ({@code BuiltInKitParityTests}); built-ins own no component files.
 */
@Component
public class KitWriter {

    private final DesignKitService service;
    private final PackageJsonBuilder packageJson;

    public KitWriter(DesignKitService service, PackageJsonBuilder packageJson) {
        this.service = service;
        this.packageJson = packageJson;
    }

    public void apply(ResolvedKit kit, KitCssWriter.Palette palette, Path frontendDir) throws IOException {
        List<KitCssWriter.FontFace> faces = kit.assets().stream()
                .filter(a -> "FONT".equals(a.role()))
                .map(a -> service.fontFace(a.targetPath(), a.meta()))
                .toList();
        boolean ownShell = kit.files().stream().anyMatch(f -> "SLOT".equals(f.role()) && TokenSchema.SHELL_SLOT.equals(f.path()));
        write(frontendDir.resolve("src/index.css"), KitCssWriter.write(
                TokenSchema.withShellDefaults(kit.tokens(), ownShell), kit.manifest(), faces, kit.css(), palette));

        KitManifest.Fonts fonts = kit.manifest().fonts();
        if (fonts != null) {
            write(frontendDir.resolve("src/main.tsx"), KitEntryWriter.write(fonts));
            Path pkg = frontendDir.resolve("package.json");
            if (Files.exists(pkg)) {
                write(pkg, packageJson.replaceFonts(Files.readString(pkg, StandardCharsets.UTF_8), fonts.packageName(), fonts.version()));
            }
        }
        for (ResolvedKit.Asset a : kit.assets()) {
            if ("THUMBNAIL".equals(a.role())) continue;
            Path target = frontendDir.resolve(a.targetPath()).normalize();
            if (!target.startsWith(frontendDir)) throw DesignKitException.badRequest("asset path escapes the project: " + a.targetPath());
            GeneratedProjectFiles.createParentDirectories(target);
            Files.write(target, a.content());
        }
        for (ResolvedKit.File f : kit.files()) {
            Path target = frontendDir.resolve(f.path()).normalize();
            if (!target.startsWith(frontendDir)) throw DesignKitException.badRequest("kit file path escapes the project: " + f.path());
            if ("SLOT".equals(f.role()) && !Files.exists(target)) continue;
            write(target, f.content());
        }
    }

    private static void write(Path path, String content) throws IOException {
        GeneratedProjectFiles.createParentDirectories(path);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
