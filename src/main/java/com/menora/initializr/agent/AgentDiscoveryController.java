package com.menora.initializr.agent;

import com.menora.initializr.agent.dto.AgentManifestResponse;
import com.menora.initializr.agent.dto.AgentManifestResponse.DependencyDto;
import com.menora.initializr.agent.dto.AgentManifestResponse.SubOption;
import com.menora.initializr.agent.dto.AgentManifestResponse.WizardCapability;
import com.menora.initializr.config.ExtensionMetadataController;
import com.menora.initializr.db.DependencyConfigService;
import io.spring.initializr.metadata.DefaultMetadataElement;
import io.spring.initializr.metadata.Dependency;
import io.spring.initializr.metadata.DependencyGroup;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.metadata.Type;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-shot discovery endpoint used by AI agents to learn the entire surface
 * before constructing a {@link com.menora.initializr.agent.dto.ScaffoldRequest}.
 *
 * <p>Response shape is intentionally a fan-out of every existing
 * {@code /metadata/*} endpoint plus the boot/java/language/packaging tables
 * — same underlying data, single network round-trip. Cacheable by the client.
 */
@RestController
public class AgentDiscoveryController {

    private static final int SCHEMA_VERSION = 1;

    private final InitializrMetadataProvider metadataProvider;
    private final ExtensionMetadataController extensions;
    private final DependencyConfigService configService;

    public AgentDiscoveryController(InitializrMetadataProvider metadataProvider,
                                    ExtensionMetadataController extensions,
                                    DependencyConfigService configService) {
        this.metadataProvider = metadataProvider;
        this.extensions = extensions;
        this.configService = configService;
    }

    @GetMapping("/agent/manifest")
    public AgentManifestResponse manifest() {
        InitializrMetadata md = metadataProvider.get();
        Map<String, List<SubOption>> subOptionsByDep = collectSubOptions();

        return new AgentManifestResponse(
                SCHEMA_VERSION,
                ids(md.getBootVersions().getContent()),
                ids(md.getJavaVersions().getContent()),
                ids(md.getLanguages().getContent()),
                ids(md.getPackagings().getContent()),
                typeIds(md.getTypes().getContent()),
                buildDependencies(md, subOptionsByDep),
                extensions.starterTemplates(),
                extensions.moduleTemplates(),
                extensions.compatibility(),
                buildWizards(),
                defaultString(md.getGroupId().getContent()),
                defaultString(md.getArtifactId().getContent()),
                defaultId(md.getBootVersions()),
                defaultId(md.getJavaVersions())
        );
    }

    private AgentManifestResponse.Wizards buildWizards() {
        Map<String, String> dialects = extensions.sqlDialects();
        return new AgentManifestResponse.Wizards(
                new WizardCapability(new ArrayList<>(dialects.keySet()), dialects),
                new WizardCapability(extensions.openApiCapableDeps(), Map.of()),
                new WizardCapability(extensions.soapCapableDeps(), Map.of())
        );
    }

    private Map<String, List<SubOption>> collectSubOptions() {
        var raw = configService.getAllSubOptions();
        var out = new java.util.LinkedHashMap<String, List<SubOption>>();
        for (var e : raw.entrySet()) {
            List<SubOption> opts = new ArrayList<>();
            for (var so : e.getValue()) {
                opts.add(new SubOption(so.getOptionId(), so.getLabel(), so.getDescription()));
            }
            out.put(e.getKey(), opts);
        }
        return out;
    }

    private List<DependencyDto> buildDependencies(InitializrMetadata md,
                                                  Map<String, List<SubOption>> subOptionsByDep) {
        List<DependencyDto> out = new ArrayList<>();
        for (DependencyGroup group : md.getDependencies().getContent()) {
            for (Dependency dep : group.getContent()) {
                out.add(new DependencyDto(
                        dep.getId(),
                        dep.getName(),
                        dep.getDescription(),
                        group.getName(),
                        dep.getCompatibilityRange(),
                        subOptionsByDep.getOrDefault(dep.getId(), List.of())
                ));
            }
        }
        return out;
    }

    private static List<String> ids(List<DefaultMetadataElement> elements) {
        return elements.stream().map(DefaultMetadataElement::getId).toList();
    }

    private static List<String> typeIds(List<Type> types) {
        return types.stream().map(Type::getId).toList();
    }

    private static String defaultId(io.spring.initializr.metadata.SingleSelectCapability cap) {
        DefaultMetadataElement def = cap.getDefault();
        return def == null ? null : def.getId();
    }

    private static String defaultString(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
