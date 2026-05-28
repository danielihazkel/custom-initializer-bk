package com.menora.initializr.extension.frontend;

import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity.RelationType;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontendCompatibilityResolverTest {

    private DependencyCompatibilityRepository repo;
    private FrontendCompatibilityResolver resolver;
    private final List<DependencyCompatibilityEntity> rules = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(DependencyCompatibilityRepository.class);
        when(repo.findAllByOrderBySortOrderAsc()).thenReturn(rules);
        resolver = new FrontendCompatibilityResolver(repo);
    }

    @Test
    void requiresAutoAddsTargetWhenInCatalog() {
        rules.add(rule("design-shadcn", "style-tailwind", RelationType.REQUIRES, ProjectKind.FRONTEND));

        Set<String> resolved = resolver.resolve(
                ordered("design-shadcn"),
                Set.of("design-shadcn", "style-tailwind", "router-react-router"));

        assertThat(resolved).containsExactly("design-shadcn", "style-tailwind");
    }

    @Test
    void requiresDropsSourceWhenTargetMissingFromCatalog() {
        rules.add(rule("foo", "missing-target", RelationType.REQUIRES, ProjectKind.FRONTEND));

        Set<String> resolved = resolver.resolve(
                ordered("foo", "bar"),
                Set.of("foo", "bar"));

        assertThat(resolved).containsExactly("bar");
    }

    @Test
    void conflictsDropsLaterSelectedDep() {
        rules.add(rule("design-mui", "design-chakra", RelationType.CONFLICTS, ProjectKind.FRONTEND));

        Set<String> resolved = resolver.resolve(
                ordered("design-mui", "design-chakra"),
                Set.of("design-mui", "design-chakra"));

        assertThat(resolved).containsExactly("design-mui");
    }

    @Test
    void conflictsRespectsInsertionOrderEvenIfRuleOrderReversed() {
        rules.add(rule("design-mui", "design-chakra", RelationType.CONFLICTS, ProjectKind.FRONTEND));

        Set<String> resolved = resolver.resolve(
                ordered("design-chakra", "design-mui"),
                Set.of("design-mui", "design-chakra"));

        assertThat(resolved).containsExactly("design-chakra");
    }

    @Test
    void backendRulesAreIgnoredByFrontendResolver() {
        rules.add(rule("design-shadcn", "style-tailwind", RelationType.REQUIRES, ProjectKind.BACKEND));

        Set<String> resolved = resolver.resolve(
                ordered("design-shadcn"),
                Set.of("design-shadcn", "style-tailwind"));

        assertThat(resolved).containsExactly("design-shadcn");
    }

    @Test
    void recommendsRulesNeverAlterSelection() {
        rules.add(rule("form-react-hook-form", "form-zod", RelationType.RECOMMENDS, ProjectKind.FRONTEND));

        Set<String> resolved = resolver.resolve(
                ordered("form-react-hook-form"),
                Set.of("form-react-hook-form", "form-zod"));

        assertThat(resolved).containsExactly("form-react-hook-form");
    }

    @Test
    void conflictsAndRequiresStabiliseInOnePass() {
        // Selecting shadcn + chakra: chakra wins-or-loses CONFLICTS with shadcn, then shadcn pulls tailwind.
        rules.add(rule("design-shadcn", "design-chakra", RelationType.CONFLICTS, ProjectKind.FRONTEND));
        rules.add(rule("design-shadcn", "style-tailwind", RelationType.REQUIRES, ProjectKind.FRONTEND));

        Set<String> resolved = resolver.resolve(
                ordered("design-shadcn", "design-chakra"),
                Set.of("design-shadcn", "design-chakra", "style-tailwind"));

        assertThat(resolved).containsExactly("design-shadcn", "style-tailwind");
    }

    @Test
    void emptySelectionReturnsEmpty() {
        rules.add(rule("design-shadcn", "style-tailwind", RelationType.REQUIRES, ProjectKind.FRONTEND));

        Set<String> resolved = resolver.resolve(ordered(), Set.of("style-tailwind"));

        assertThat(resolved).isEmpty();
    }

    private static DependencyCompatibilityEntity rule(String source, String target,
                                                      RelationType type, ProjectKind kind) {
        DependencyCompatibilityEntity e = new DependencyCompatibilityEntity();
        e.setSourceDepId(source);
        e.setTargetDepId(target);
        e.setRelationType(type);
        e.setProjectKind(kind);
        e.setDescription(source + " " + type.name() + " " + target);
        return e;
    }

    private static Set<String> ordered(String... ids) {
        return new LinkedHashSet<>(List.of(ids));
    }
}
