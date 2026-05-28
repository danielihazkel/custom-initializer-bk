package com.menora.initializr.db;

import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DependencyConfigService {

    public static final String COMMON_ID = "__common__";

    private final DependencyGroupRepository groupRepo;
    private final DependencyEntryRepository entryRepo;
    private final FileContributionRepository fileContribRepo;
    private final BuildCustomizationRepository buildCustomRepo;
    private final DependencySubOptionRepository subOptionRepo;

    public DependencyConfigService(DependencyGroupRepository groupRepo,
                                   DependencyEntryRepository entryRepo,
                                   FileContributionRepository fileContribRepo,
                                   BuildCustomizationRepository buildCustomRepo,
                                   DependencySubOptionRepository subOptionRepo) {
        this.groupRepo = groupRepo;
        this.entryRepo = entryRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
    }

    /** Returns file contributions for the selected backend deps plus the common ones. */
    @Transactional(readOnly = true)
    public List<FileContributionEntity> getFileContributions(Set<String> selectedDepIds) {
        return getFileContributions(selectedDepIds, ProjectKind.BACKEND);
    }

    /** Returns file contributions for the selected deps (filtered by kind) plus the common ones. */
    @Transactional(readOnly = true)
    public List<FileContributionEntity> getFileContributions(Set<String> selectedDepIds, ProjectKind kind) {
        Set<String> ids = new HashSet<>(selectedDepIds);
        ids.add(COMMON_ID);
        return fileContribRepo.findByDependencyIdInAndProjectKindOrderBySortOrderAsc(ids, kind);
    }

    /** Returns build customizations for the selected backend deps plus the common ones. */
    @Transactional(readOnly = true)
    public List<BuildCustomizationEntity> getBuildCustomizations(Set<String> selectedDepIds) {
        return getBuildCustomizations(selectedDepIds, ProjectKind.BACKEND);
    }

    /** Returns build customizations for the selected deps (filtered by kind) plus the common ones. */
    @Transactional(readOnly = true)
    public List<BuildCustomizationEntity> getBuildCustomizations(Set<String> selectedDepIds, ProjectKind kind) {
        Set<String> ids = new HashSet<>(selectedDepIds);
        ids.add(COMMON_ID);
        return buildCustomRepo.findByDependencyIdInAndProjectKindOrderBySortOrderAsc(ids, kind);
    }

    /** Returns backend sub-options grouped by dependency ID. */
    @Transactional(readOnly = true)
    public Map<String, List<DependencySubOptionEntity>> getAllSubOptions() {
        return getAllSubOptions(ProjectKind.BACKEND);
    }

    /** Returns sub-options (filtered by kind) grouped by dependency ID. */
    @Transactional(readOnly = true)
    public Map<String, List<DependencySubOptionEntity>> getAllSubOptions(ProjectKind kind) {
        return subOptionRepo.findAllByProjectKindOrderByDependencyIdAscSortOrderAsc(kind).stream()
                .collect(Collectors.groupingBy(DependencySubOptionEntity::getDependencyId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /** Returns all backend dependency groups with their entries eagerly loaded, ordered for metadata building. */
    @Transactional(readOnly = true)
    public List<DependencyGroupEntity> getAllGroupsWithEntries() {
        return getAllGroupsWithEntries(ProjectKind.BACKEND);
    }

    /** Returns dependency groups (filtered by kind) with their entries eagerly loaded. */
    @Transactional(readOnly = true)
    public List<DependencyGroupEntity> getAllGroupsWithEntries(ProjectKind kind) {
        return groupRepo.findAllWithEntriesSortedByKind(kind).stream()
                // entries on a group can include cross-kind rows when seeded carelessly; filter here too.
                .peek(g -> g.getEntries().removeIf(e -> e.getProjectKind() != kind))
                .collect(Collectors.toList());
    }

    /**
     * Returns the subset of {@code candidateIds} that belong to file-only entries
     * (starter=false). These should be removed from the Maven build so they don't
     * produce a pom.xml dependency entry. Backend-only path.
     */
    @Transactional(readOnly = true)
    public Set<String> getFileOnlyDepIds(Set<String> candidateIds) {
        return entryRepo.findByDepIdInAndProjectKind(candidateIds, ProjectKind.BACKEND).stream()
                .filter(e -> !e.isStarter())
                .map(DependencyEntryEntity::getDepId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
