package com.menora.initializr.config;

import io.spring.initializr.metadata.DefaultMetadataElement;
import io.spring.initializr.metadata.Defaultable;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.metadata.SingleSelectCapability;
import io.spring.initializr.web.project.WebProjectRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Builds the framework's {@link WebProjectRequest} for the endpoints that cannot let the
 * framework bind it: the wizard and fullstack endpoints take a JSON body, and the multi-module
 * endpoint generates one project per module.
 *
 * <p>{@code /starter.zip} is bound by the framework, which fills what a caller left out from the
 * metadata. These endpoints each built the request by hand, with their own hard-coded defaults —
 * {@code "3.2.1"} and {@code "21"} in three places — so an admin changing the default Spring Boot
 * or Java version (the Versions table) moved {@code /starter.zip} and nothing else. One builder
 * now, taking every default from the same metadata.
 *
 * <p>It also sets {@code configurationFileFormat}: the servlet filter's default
 * ({@link InitializrWebConfiguration}) only reaches a request the framework binds, and without
 * one the framework fails with "Unrecognized configuration file format id 'null'".
 */
@Component
public class ProjectRequestFactory {

    private final InitializrMetadataProvider metadataProvider;

    public ProjectRequestFactory(InitializrMetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    /**
     * What a caller may say about the project itself. Any of them null or blank means "the
     * default": the metadata's for the versions, type, language, packaging, group and artifact;
     * the artifact id for the name; {@code <group>.<artifact>} for the package.
     */
    public record Base(String groupId, String artifactId, String name, String description, String packageName,
                       String type, String language, String bootVersion, String packaging, String javaVersion,
                       String version, String configurationFileFormat) {
    }

    /**
     * The request for {@code base} with {@code dependencies} (null: none named).
     *
     * @throws WizardArgumentException for a Spring Boot or Java version the metadata does not
     *         offer — a clean 400 here instead of an opaque failure deep in generation
     */
    public WebProjectRequest create(Base base, Collection<String> dependencies) {
        InitializrMetadata metadata = metadataProvider.get();
        WebProjectRequest r = new WebProjectRequest();
        r.setType(orDefault(base.type(), defaultOf(metadata.getTypes())));
        r.setLanguage(orDefault(base.language(), defaultOf(metadata.getLanguages())));
        r.setBootVersion(knownVersion(metadata.getBootVersions(), base.bootVersion(), "bootVersion"));
        r.setGroupId(orDefault(base.groupId(), metadata.getGroupId().getContent()));
        r.setArtifactId(orDefault(base.artifactId(), metadata.getArtifactId().getContent()));
        r.setName(orDefault(base.name(), r.getArtifactId()));
        r.setDescription(orDefault(base.description(), ""));
        r.setPackageName(orDefault(base.packageName(), r.getGroupId() + "." + r.getArtifactId()));
        r.setPackaging(orDefault(base.packaging(), defaultOf(metadata.getPackagings())));
        r.setJavaVersion(knownVersion(metadata.getJavaVersions(), base.javaVersion(), "javaVersion"));
        r.setVersion(orDefault(base.version(), (String) metadata.defaults().get("version")));
        r.setConfigurationFileFormat(orDefault(base.configurationFileFormat(),
                defaultOf(metadata.getConfigurationFileFormats())));
        if (dependencies != null) {
            r.setDependencies(new ArrayList<>(dependencies));
        }
        return r;
    }

    /**
     * The version to generate with: the metadata's default when none was asked for, the asked
     * one when the metadata offers it (a legacy {@code .RELEASE} / {@code -RELEASE} suffix is
     * dropped first), and a 400 naming the parameter otherwise.
     */
    private static String knownVersion(SingleSelectCapability offered, String requested, String parameter) {
        if (requested == null || requested.isBlank()) {
            return defaultOf(offered);
        }
        String value = requested.trim();
        if (offered.get(value) != null) {
            return value;
        }
        String canonical = BootVersions.normalize(value);
        if (!canonical.equals(value) && offered.get(canonical) != null) {
            return canonical;
        }
        throw new WizardArgumentException(parameter + " '" + value + "' is not a known version", parameter);
    }

    private static <T extends DefaultMetadataElement> String defaultOf(Defaultable<T> offered) {
        T element = offered.getDefault();
        return element == null ? null : element.getId();
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
