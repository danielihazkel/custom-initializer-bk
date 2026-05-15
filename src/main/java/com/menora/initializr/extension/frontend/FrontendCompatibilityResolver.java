package com.menora.initializr.extension.frontend;

import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity.RelationType;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies REQUIRES and CONFLICTS rules from {@code dependency_compatibility}
 * (FRONTEND-scoped) to a selected dependency set.
 *
 * <p>Mirrors the silent-filter philosophy of {@link FrontendVersionRangeFilter}:
 * the generator never fails because the request carried an inconsistent
 * selection — the resolver fixes it and logs a warn. The UI uses the same
 * rules (via {@code /metadata/compatibility?projectKind=FRONTEND}) to surface
 * banners before the user hits Generate, so server-side resolution is the
 * safety net for direct API hits (curl, IntelliJ).
 *
 * <p>RECOMMENDS rules are advisory and never alter the selection here.
 */
@Component
public class FrontendCompatibilityResolver {

    private static final Logger log = LoggerFactory.getLogger(FrontendCompatibilityResolver.class);
    private static final int MAX_PASSES = 8;

    private final DependencyCompatibilityRepository repo;

    public FrontendCompatibilityResolver(DependencyCompatibilityRepository repo) {
        this.repo = repo;
    }

    /**
     * Resolves {@code depIds} against the FRONTEND compatibility rules.
     * Returns a new {@link LinkedHashSet} preserving relative insertion order
     * (so CONFLICTS deterministically drops the later-selected dep).
     *
     * @param depIds         current selection
     * @param catalogDepIds  ids of every dependency in the FRONTEND catalog — used
     *                       to decide whether a missing REQUIRES target can be added
     */
    public Set<String> resolve(Set<String> depIds, Set<String> catalogDepIds) {
        List<DependencyCompatibilityEntity> rules = repo.findAllByOrderBySortOrderAsc().stream()
                .filter(r -> r.getProjectKind() == ProjectKind.FRONTEND)
                .toList();
        if (rules.isEmpty()) return new LinkedHashSet<>(depIds);

        Set<String> current = new LinkedHashSet<>(depIds);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Set<String> next = applyOnce(current, rules, catalogDepIds);
            if (next.equals(current)) return next;
            current = next;
        }
        log.warn("Compatibility resolver did not stabilise after {} passes — returning best effort {}",
                MAX_PASSES, current);
        return current;
    }

    private Set<String> applyOnce(Set<String> selected,
                                  List<DependencyCompatibilityEntity> rules,
                                  Set<String> catalogDepIds) {
        Set<String> result = new LinkedHashSet<>(selected);

        // CONFLICTS first — drop the later-selected dep (iteration order of LinkedHashSet).
        for (DependencyCompatibilityEntity rule : rules) {
            if (rule.getRelationType() != RelationType.CONFLICTS) continue;
            String a = rule.getSourceDepId();
            String b = rule.getTargetDepId();
            if (!result.contains(a) || !result.contains(b)) continue;
            String drop = laterOf(result, a, b);
            log.warn("Dropping '{}' — conflicts with '{}' (rule: {})", drop,
                    drop.equals(a) ? b : a, rule.getDescription());
            result.remove(drop);
        }

        // REQUIRES second — add target if it exists in the catalog, else drop source.
        for (DependencyCompatibilityEntity rule : rules) {
            if (rule.getRelationType() != RelationType.REQUIRES) continue;
            String source = rule.getSourceDepId();
            String target = rule.getTargetDepId();
            if (!result.contains(source) || result.contains(target)) continue;
            if (catalogDepIds.contains(target)) {
                log.info("Auto-adding '{}' — required by '{}' (rule: {})", target, source, rule.getDescription());
                result.add(target);
            } else {
                log.warn("Dropping '{}' — requires missing dep '{}' (rule: {})", source, target, rule.getDescription());
                result.remove(source);
            }
        }

        return result;
    }

    /** Returns whichever of {@code a}/{@code b} appears later in {@code set}'s iteration order. */
    private static String laterOf(Set<String> set, String a, String b) {
        int idx = 0;
        int idxA = -1;
        int idxB = -1;
        for (String s : set) {
            if (s.equals(a)) idxA = idx;
            else if (s.equals(b)) idxB = idx;
            idx++;
        }
        return idxA > idxB ? a : b;
    }
}
