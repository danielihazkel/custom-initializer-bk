package com.menora.initializr.config;

import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity.RelationType;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import io.spring.initializr.generator.version.Version;
import io.spring.initializr.metadata.Dependency;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The backend catalog's compatibility rules (the BACKEND rows of {@code dependency_compatibility}),
 * applied to what a request asks for. The UI shows the same rules as banners while dependencies
 * are picked; this is what holds for a request that did not come through it (curl, IntelliJ, a
 * share link edited by hand).
 *
 * <ul>
 *   <li><b>REQUIRES</b> — the required dependency is added when the catalog offers it for the
 *       request's Spring Boot version (it is what the UI's banner offers to do).</li>
 *   <li><b>CONFLICTS</b> — a 400 naming both dependencies and the rule's reason. Dropping one of
 *       them would generate a project other than the one asked for, without saying so.</li>
 *   <li><b>RECOMMENDS</b> — advice; never changes a request.</li>
 * </ul>
 *
 * <p>The frontend catalog's rules are applied by {@code FrontendCompatibilityResolver}, which
 * fixes a selection silently instead.
 */
@Component
public class BackendCompatibility {

    /** Requirements can chain (a required dependency requiring another); this bounds the passes. */
    private static final int MAX_PASSES = 8;

    private final DependencyCompatibilityRepository rules;
    private final InitializrMetadataProvider metadataProvider;

    public BackendCompatibility(DependencyCompatibilityRepository rules, InitializrMetadataProvider metadataProvider) {
        this.rules = rules;
        this.metadataProvider = metadataProvider;
    }

    /** What applying the rules to a request's dependencies came to. */
    public record Outcome(List<String> dependencies, String conflict) {
    }

    /**
     * {@code dependencies} with every dependency they require added, in the order asked for and
     * then the added ones; or, when two of them conflict, the reason — never both. Ids the catalog
     * does not know are passed through for the framework to refuse.
     *
     * @param bootVersion the request's Spring Boot version (a required dependency outside its
     *                    range is not added); null means the catalog's default, one that does
     *                    not parse checks no range
     */
    public Outcome resolve(Collection<String> dependencies, String bootVersion) {
        List<DependencyCompatibilityEntity> backend = rules.findAllByOrderBySortOrderAsc().stream()
                .filter(r -> r.getProjectKind() == ProjectKind.BACKEND)
                .toList();
        Set<String> selected = new LinkedHashSet<>(dependencies);
        if (backend.isEmpty() || selected.isEmpty()) return new Outcome(new ArrayList<>(selected), null);

        InitializrMetadata metadata = metadataProvider.get();
        Version boot = parse(bootVersion != null && !bootVersion.isBlank() ? bootVersion
                : metadata.getBootVersions().getDefault() == null ? null : metadata.getBootVersions().getDefault().getId());
        // Which dependency added each required one, so a conflict it causes can say so.
        Map<String, String> addedFor = new LinkedHashMap<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (DependencyCompatibilityEntity rule : backend) {
                if (rule.getRelationType() != RelationType.REQUIRES) continue;
                String target = rule.getTargetDepId();
                if (!selected.contains(rule.getSourceDepId()) || selected.contains(target)) continue;
                if (!offered(metadata, target, boot)) continue;
                selected.add(target);
                addedFor.put(target, rule.getSourceDepId());
                changed = true;
            }
            if (!changed) break;
        }

        for (DependencyCompatibilityEntity rule : backend) {
            if (rule.getRelationType() != RelationType.CONFLICTS) continue;
            String a = rule.getSourceDepId();
            String b = rule.getTargetDepId();
            if (selected.contains(a) && selected.contains(b)) {
                return new Outcome(null, conflictMessage(metadata, a, b, rule.getDescription(), addedFor));
            }
        }
        return new Outcome(new ArrayList<>(selected), null);
    }

    /**
     * {@link #resolve} for an endpoint that answers {@link WizardArgumentException} as a 400: the
     * dependencies with what they require, or the conflict thrown.
     */
    public List<String> apply(Collection<String> dependencies, String bootVersion) {
        Outcome outcome = resolve(dependencies, bootVersion);
        if (outcome.conflict() != null) throw new WizardArgumentException(outcome.conflict(), "dependencies");
        return outcome.dependencies();
    }

    private static boolean offered(InitializrMetadata metadata, String id, Version boot) {
        Dependency dependency = metadata.getDependencies().get(id);
        return dependency != null && (boot == null || dependency.match(boot));
    }

    private static String conflictMessage(InitializrMetadata metadata, String a, String b, String reason,
                                          Map<String, String> addedFor) {
        StringBuilder message = new StringBuilder("dependencies: ")
                .append(named(metadata, a)).append(" and ").append(named(metadata, b))
                .append(" cannot be used together");
        if (reason != null && !reason.isBlank()) message.append(" — ").append(reason.trim());
        for (String id : List.of(a, b)) {
            String by = addedFor.get(id);
            if (by != null) {
                message.append(" (").append(named(metadata, id)).append(" is required by ")
                        .append(named(metadata, by)).append(')');
            }
        }
        return message.toString();
    }

    /** "Spring Web ('web')", or the bare id for one the catalog does not know. */
    private static String named(InitializrMetadata metadata, String id) {
        Dependency dependency = metadata.getDependencies().get(id);
        return dependency == null || dependency.getName() == null ? "'" + id + "'"
                : dependency.getName() + " ('" + id + "')";
    }

    private static Version parse(String bootVersion) {
        if (bootVersion == null || bootVersion.isBlank()) return null;
        try {
            return Version.parse(bootVersion.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
