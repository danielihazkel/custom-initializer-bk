package com.menora.initializr.db;

import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DependencyConfigService {

    public static final String COMMON_ID = "__common__";

    private final DependencyGroupRepository groupRepo;
    private final FileContributionRepository fileContribRepo;
    private final BuildCustomizationRepository buildCustomRepo;
    private final DependencySubOptionRepository subOptionRepo;

    public DependencyConfigService(DependencyGroupRepository groupRepo,
                                   FileContributionRepository fileContribRepo,
                                   BuildCustomizationRepository buildCustomRepo,
                                   DependencySubOptionRepository subOptionRepo) {
        this.groupRepo = groupRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
    }

    /** Returns file contributions for the selected deps plus the common ones. */
    @Transactional(readOnly = true)
    public List<FileContributionEntity> getFileContributions(Set<String> selectedDepIds) {
        Set<String> ids = new HashSet<>(selectedDepIds);
        ids.add(COMMON_ID);
        return fileContribRepo.findByDependencyIdInOrderBySortOrderAsc(ids);
    }

    /** Returns build customizations for the selected deps plus the common ones. */
    @Transactional(readOnly = true)
    public List<BuildCustomizationEntity> getBuildCustomizations(Set<String> selectedDepIds) {
        Set<String> ids = new HashSet<>(selectedDepIds);
        ids.add(COMMON_ID);
        return buildCustomRepo.findByDependencyIdInOrderBySortOrderAsc(ids);
    }

    /** Returns sub-options grouped by dependency ID. */
    @Transactional(readOnly = true)
    public Map<String, List<DependencySubOptionEntity>> getAllSubOptions() {
        return subOptionRepo.findAllByOrderByDependencyIdAscSortOrderAsc().stream()
                .collect(Collectors.groupingBy(DependencySubOptionEntity::getDependencyId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /** Returns all dependency groups with their entries eagerly loaded, ordered for metadata building. */
    @Transactional(readOnly = true)
    public List<DependencyGroupEntity> getAllGroupsWithEntries() {
        return groupRepo.findAllWithEntriesSorted();
    }
}
