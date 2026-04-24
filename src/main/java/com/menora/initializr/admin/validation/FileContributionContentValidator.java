package com.menora.initializr.admin.validation;

import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.FileContributionEntity.FileType;
import com.menora.initializr.db.entity.FileContributionEntity.SubstitutionType;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates the syntax of {@link FileContributionEntity#getContent()} before
 * the entity is persisted by the admin API. Dispatches to a per-language
 * validator based on the extension of {@code targetPath}. For TEMPLATE +
 * MUSTACHE contributions, the template is compiled and rendered against a
 * "maximally enabled" dummy context before the rendered output is validated.
 *
 * <p>Returns an empty list when content is valid, {@code fileType} is
 * {@code DELETE}, or the target path has no validator we recognise.
 */
@Service
public class FileContributionContentValidator {

    private static final Mustache.Compiler MUSTACHE =
            Mustache.compiler().escapeHTML(false).defaultValue("");

    private final Map<String, ContentSyntaxValidator> byExtension;

    public FileContributionContentValidator(
            JavaContentValidator java,
            YamlContentValidator yaml,
            XmlContentValidator xml,
            JsonContentValidator json,
            SqlContentValidator sql,
            PropertiesContentValidator properties) {
        this.byExtension = Map.of(
                "java", java,
                "yaml", yaml,
                "yml", yaml,
                "xml", xml,
                "json", json,
                "sql", sql,
                "properties", properties
        );
    }

    public List<String> validate(FileContributionEntity fc) {
        if (fc.getFileType() == FileType.DELETE) return List.of();

        String content = fc.getContent();
        if (content == null || content.isEmpty()) return List.of();

        ContentSyntaxValidator validator = pickValidator(fc.getTargetPath());
        if (validator == null) return List.of();

        if (fc.getFileType() == FileType.TEMPLATE
                && fc.getSubstitutionType() == SubstitutionType.MUSTACHE) {
            Template template;
            try {
                template = MUSTACHE.compile(content);
            } catch (MustacheException e) {
                return List.of("Mustache syntax error: " + e.getMessage());
            }
            String rendered;
            try {
                rendered = template.execute(maximalContext());
            } catch (MustacheException e) {
                return List.of("Mustache render error: " + e.getMessage());
            }
            return validator.validate(rendered);
        }

        return validator.validate(content);
    }

    private ContentSyntaxValidator pickValidator(String targetPath) {
        if (targetPath == null) return null;
        int slash = Math.max(targetPath.lastIndexOf('/'), targetPath.lastIndexOf('\\'));
        String name = slash >= 0 ? targetPath.substring(slash + 1) : targetPath;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return byExtension.get(ext);
    }

    /**
     * Every {@code has*} and {@code opt*} flag returns TRUE so every
     * conditional section expands — we validate the "fullest" possible
     * rendering. Known project keys get plausible placeholders; anything
     * else falls back to the compiler's empty-string default.
     */
    private Mustache.CustomContext maximalContext() {
        return name -> {
            if (name == null) return "";
            switch (name) {
                case "artifactId": return "demo";
                case "groupId":    return "com.example";
                case "version":    return "1.0.0";
                case "packageName": return "com.example.demo";
                case "packagePath": return "com/example/demo";
                case "javaVersion": return "21";
                case "packaging":   return "jar";
                default: break;
            }
            if (name.startsWith("has") || name.startsWith("opt")) return Boolean.TRUE;
            return "";
        };
    }
}
