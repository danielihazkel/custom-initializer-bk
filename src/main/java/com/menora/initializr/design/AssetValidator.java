package com.menora.initializr.design;

import com.menora.initializr.db.entity.DesignKitAssetEntity;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Checks an uploaded asset. The type is decided by the file's own bytes, never by the name or the
 * declared content type, and must match the target path's extension. SVG is text, so it is also
 * refused when it can run anything (scripts, event handlers, foreign objects, external links).
 */
public final class AssetValidator {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_VERSION_BYTES = 10 * 1024 * 1024;
    private static final Pattern PATH = Pattern.compile("^(public|src/assets)/[a-z0-9_-]+(/[a-z0-9_-]+)*\\.[a-z0-9]+$");
    /** The kit's picture in the generator's look list: never written into a generated app. */
    public static final Pattern THUMBNAIL = Pattern.compile("^kit/thumbnail\\.(png|jpg|jpeg|webp|svg)$");
    private static final Pattern SVG_DANGER = Pattern.compile(
            "(?is)<\\s*script|\\son[a-z]+\\s*=|<\\s*foreignObject|javascript:|(href|src)\\s*=\\s*[\"']\\s*(https?:|//|data:text)|<!ENTITY");

    private AssetValidator() {
    }

    /** The content type the bytes are, checked against the path; throws with a reason otherwise. */
    public static String check(String targetPath, DesignKitAssetEntity.Role role, byte[] bytes) {
        boolean thumbnail = targetPath != null && THUMBNAIL.matcher(targetPath).matches();
        if (targetPath == null || !(thumbnail || PATH.matcher(targetPath).matches())) {
            throw DesignKitException.badRequest("asset path must be public/<name>.<ext>, src/assets/<dir>/<name>.<ext> "
                    + "(lower-case letters, digits, '-', '_') or kit/thumbnail.<png|jpg|webp|svg>");
        }
        if (thumbnail != (role == DesignKitAssetEntity.Role.THUMBNAIL)) {
            throw DesignKitException.badRequest(thumbnail ? "kit/thumbnail.* takes the THUMBNAIL role" : "the THUMBNAIL role is for kit/thumbnail.<png|jpg|webp|svg>");
        }
        if (bytes == null || bytes.length == 0) throw DesignKitException.badRequest("asset is empty");
        if (bytes.length > MAX_BYTES) throw DesignKitException.badRequest("asset is larger than " + MAX_BYTES / 1024 / 1024 + " MB");
        String type = sniff(bytes);
        if (type == null) throw DesignKitException.badRequest("asset must be PNG, JPEG, WebP, SVG, ICO, WOFF2 or WOFF");
        String ext = targetPath.substring(targetPath.lastIndexOf('.') + 1);
        String expected = switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            default -> null;
        };
        if (!type.equals(expected)) {
            throw DesignKitException.badRequest("asset is " + type + " but its path ends in ." + ext);
        }
        boolean font = type.startsWith("font/");
        if ((role == DesignKitAssetEntity.Role.FONT) != font) {
            throw DesignKitException.badRequest(font ? "a font file must have the FONT role" : "the FONT role is for font files");
        }
        if (font && !targetPath.startsWith("src/assets/fonts/")) {
            throw DesignKitException.badRequest("a font file goes under src/assets/fonts/ (src/index.css declares it with @font-face)");
        }
        if (type.equals("image/svg+xml") && SVG_DANGER.matcher(new String(bytes, StandardCharsets.UTF_8)).find()) {
            throw DesignKitException.badRequest("SVG may not contain scripts, event handlers, foreignObject, entities or external links");
        }
        return type;
    }

    static String sniff(byte[] b) {
        if (starts(b, 0x89, 'P', 'N', 'G')) return "image/png";
        if (starts(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(b, 'R', 'I', 'F', 'F') && b.length > 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (starts(b, 0, 0, 1, 0)) return "image/x-icon";
        if (starts(b, 'w', 'O', 'F', '2')) return "font/woff2";
        if (starts(b, 'w', 'O', 'F', 'F')) return "font/woff";
        String head = new String(b, 0, Math.min(b.length, 512), StandardCharsets.UTF_8).stripLeading();
        if (head.startsWith("\uFEFF")) head = head.substring(1);
        if ((head.startsWith("<svg") || head.startsWith("<?xml")) && head.contains("<svg")) return "image/svg+xml";
        return null;
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xFF) != sig[i]) return false;
        return true;
    }
}
