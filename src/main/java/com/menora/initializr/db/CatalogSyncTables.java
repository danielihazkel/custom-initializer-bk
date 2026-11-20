package com.menora.initializr.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity.RowTable;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.BuildCustomizationRepository;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.DependencyGroupRepository;
import com.menora.initializr.db.repository.DependencySubOptionRepository;
import com.menora.initializr.db.repository.EntityTemplateSetDefaultDepRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import com.menora.initializr.db.repository.ModuleDependencyMappingRepository;
import com.menora.initializr.db.repository.StarterTemplateDepRepository;
import com.menora.initializr.db.seed.CatalogManifests.BuildCustomDef;
import com.menora.initializr.db.seed.CatalogManifests.CompatibilityDef;
import com.menora.initializr.db.seed.CatalogManifests.DependenciesManifest;
import com.menora.initializr.db.seed.CatalogManifests.EntryDef;
import com.menora.initializr.db.seed.CatalogManifests.FileContribDef;
import com.menora.initializr.db.seed.CatalogManifests.GroupDef;
import com.menora.initializr.db.seed.CatalogManifests.SubOptionDef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The six catalog tables {@link CatalogSync} keeps in step with a release, each as a {@link Table}:
 * how its shipped rows are read from the manifests under {@code catalog/}, what makes a row the
 * same row from one release to the next (its key), what about it may change (its fingerprint),
 * what it cannot be without (its parent), and how it is written. The rules of the sync — what is
 * updated, kept, added, removed — are {@link CatalogSync}'s and the same for every table.
 *
 * <p>{@link #all()} is in the order rows are written: a group before its entries, a dependency
 * before the options, rules, files and build rows that name it. Removals go the other way.
 */
@Component
class CatalogSyncTables {

    /** The pseudo-dependency every project gets: its rows need no catalog entry. */
    static final String COMMON = "__common__";

    /** What a table's rows need to know about the rest of the catalog while a pass runs. */
    interface Catalog {

        /** The key of the shipped group a stored group stands for — or of its own name, when it stands for none. */
        String groupKeyOf(DependencyGroupEntity stored);

        /** The stored group that stands for the shipped group with this key; null when there is none. */
        DependencyGroupEntity groupFor(String groupKey);

        /** Whether the catalog has a dependency with this id ({@link #COMMON} always). */
        boolean hasDependency(String dependencyId);
    }

    /**
     * One table. {@code E} is its entity; a shipped row is an unsaved one read from a manifest.
     */
    abstract static class Table<E> {

        final RowTable id;

        Table(RowTable id) {
            this.id = id;
        }

        /** The rows of one kind's manifest, unsaved, in the manifest's order. */
        abstract List<E> shipped(ProjectKind kind) throws IOException;

        abstract List<E> stored();

        /** What makes a row the same row from one release to the next. */
        abstract String key(E row, Catalog catalog);

        /** Everything about a row that is not its key. */
        abstract String fingerprint(E row, Catalog catalog);

        abstract String label(E row);

        abstract Long rowId(E row);

        abstract ProjectKind kind(E row);

        /** The dependency a row is about; null for a group. */
        abstract String dependencyId(E row);

        /** What a shipped row cannot be written without and the catalog lacks, in words; null when nothing. */
        abstract String missingParent(E shipped, Catalog catalog);

        /** Writes a shipped row the database does not have; answers the stored row. */
        abstract E add(E shipped, Catalog catalog);

        /** Makes a stored row say what the shipped one says, and saves it. */
        abstract void update(E shipped, E stored, Catalog catalog);

        abstract void delete(E stored);

        /** Whether a row the release no longer ships may go: nothing in the catalog still needs it. */
        boolean removable(E stored) {
            return true;
        }

        /** The row as the admin API shows it beside the other version. */
        Object view(E row, Catalog catalog) {
            return row;
        }
    }

    private final DependencyGroupRepository groupRepo;
    private final DependencyEntryRepository entryRepo;
    private final DependencySubOptionRepository subOptionRepo;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final FileContributionRepository fileRepo;
    private final BuildCustomizationRepository buildRepo;
    private final StarterTemplateDepRepository starterDepRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;
    private final EntityTemplateSetDefaultDepRepository defaultDepRepo;

    @PersistenceContext
    private EntityManager entityManager;

    final Table<DependencyGroupEntity> groups = new Groups();
    final Table<DependencyEntryEntity> entries = new Entries();
    final Table<DependencySubOptionEntity> subOptions = new SubOptions();
    final Table<DependencyCompatibilityEntity> rules = new Rules();
    final Table<FileContributionEntity> files = new Files();
    final Table<BuildCustomizationEntity> builds = new Builds();

    CatalogSyncTables(DependencyGroupRepository groupRepo,
                      DependencyEntryRepository entryRepo,
                      DependencySubOptionRepository subOptionRepo,
                      DependencyCompatibilityRepository compatibilityRepo,
                      FileContributionRepository fileRepo,
                      BuildCustomizationRepository buildRepo,
                      StarterTemplateDepRepository starterDepRepo,
                      ModuleDependencyMappingRepository moduleMappingRepo,
                      EntityTemplateSetDefaultDepRepository defaultDepRepo) {
        this.groupRepo = groupRepo;
        this.entryRepo = entryRepo;
        this.subOptionRepo = subOptionRepo;
        this.compatibilityRepo = compatibilityRepo;
        this.fileRepo = fileRepo;
        this.buildRepo = buildRepo;
        this.starterDepRepo = starterDepRepo;
        this.moduleMappingRepo = moduleMappingRepo;
        this.defaultDepRepo = defaultDepRepo;
    }

    /** Every table, in the order their rows are written. */
    List<Table<?>> all() {
        return List.of(groups, entries, subOptions, rules, files, builds);
    }

    /** The ids of the dependencies the catalog has now. */
    List<String> dependencyIds() {
        return entryRepo.findAll().stream().map(DependencyEntryEntity::getDepId).toList();
    }

    List<DependencyGroupEntity> storedGroups() {
        return groupRepo.findAll();
    }

    // ── Dependency groups ────────────────────────────────────────────────────

    /** A group's key when nothing but its name says which group it is. */
    static String groupKey(ProjectKind kind, String name) {
        return hash(String.join("\u0001", "group", kind.name(), text(name)));
    }

    /**
     * The key of a group, shipped or stored: a shipped one (it has no id) is known by its name; a
     * stored one by the shipped group it stands for, which survives a rename.
     */
    private static String groupKey(DependencyGroupEntity group, Catalog catalog) {
        return group.getId() == null ? groupKey(group.getProjectKind(), group.getName()) : catalog.groupKeyOf(group);
    }

    private final class Groups extends Table<DependencyGroupEntity> {

        Groups() {
            super(RowTable.DEPENDENCY_GROUP);
        }

        /** The groups alone: {@link #shippedCatalog} has them with their entries. */
        @Override
        List<DependencyGroupEntity> shipped(ProjectKind kind) throws IOException {
            return shippedCatalog(kind);
        }

        @Override
        List<DependencyGroupEntity> stored() {
            return groupRepo.findAll();
        }

        @Override
        String key(DependencyGroupEntity row, Catalog catalog) {
            return groupKey(row, catalog);
        }

        @Override
        String fingerprint(DependencyGroupEntity row, Catalog catalog) {
            return hash(String.join("\u0001", text(row.getName()), String.valueOf(row.getSortOrder())));
        }

        @Override
        String label(DependencyGroupEntity row) {
            return kindWord(row.getProjectKind()) + " group " + row.getName();
        }

        @Override
        Long rowId(DependencyGroupEntity row) {
            return row.getId();
        }

        @Override
        ProjectKind kind(DependencyGroupEntity row) {
            return row.getProjectKind();
        }

        @Override
        String dependencyId(DependencyGroupEntity row) {
            return null;
        }

        @Override
        String missingParent(DependencyGroupEntity shipped, Catalog catalog) {
            return null;
        }

        /** A new row with the group's own columns: the shipped object carries its entries, which are not this row's to write. */
        @Override
        DependencyGroupEntity add(DependencyGroupEntity shipped, Catalog catalog) {
            DependencyGroupEntity row = new DependencyGroupEntity();
            row.setName(shipped.getName());
            row.setSortOrder(shipped.getSortOrder());
            row.setProjectKind(shipped.getProjectKind());
            return groupRepo.save(row);
        }

        @Override
        void update(DependencyGroupEntity shipped, DependencyGroupEntity stored, Catalog catalog) {
            stored.setName(shipped.getName());
            stored.setSortOrder(shipped.getSortOrder());
            groupRepo.save(stored);
        }

        /**
         * Flushed and re-read first: deleting a group deletes the entries in its collection, and
         * an entry this pass has just moved to another group must not still be in it.
         */
        @Override
        void delete(DependencyGroupEntity stored) {
            entityManager.flush();
            entityManager.refresh(stored);
            groupRepo.delete(stored);
        }

        /** Only an empty group goes: its entries are somebody's. */
        @Override
        boolean removable(DependencyGroupEntity stored) {
            entityManager.flush();
            return entryRepo.findByGroupId(stored.getId()).isEmpty();
        }

        @Override
        Object view(DependencyGroupEntity row, Catalog catalog) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("name", row.getName());
            view.put("sortOrder", row.getSortOrder());
            view.put("projectKind", row.getProjectKind());
            return view;
        }
    }

    // ── Dependency entries ───────────────────────────────────────────────────

    private final class Entries extends Table<DependencyEntryEntity> {

        Entries() {
            super(RowTable.DEPENDENCY_ENTRY);
        }

        @Override
        List<DependencyEntryEntity> shipped(ProjectKind kind) throws IOException {
            List<DependencyEntryEntity> out = new ArrayList<>();
            for (DependencyGroupEntity group : shippedCatalog(kind)) out.addAll(group.getEntries());
            return out;
        }

        @Override
        List<DependencyEntryEntity> stored() {
            return entryRepo.findAll();
        }

        /** The id alone: it is unique across both kinds, so the kind is something an entry has, not what it is. */
        @Override
        String key(DependencyEntryEntity row, Catalog catalog) {
            return hash(String.join("\u0001", "entry", text(row.getDepId())));
        }

        /** The group is part of it: an entry moved to another group — by a release or by an admin — has changed. */
        @Override
        String fingerprint(DependencyEntryEntity row, Catalog catalog) {
            return hash(String.join("\u0001", groupKey(row.getGroup(), catalog), row.getProjectKind().name(),
                    text(row.getName()), text(row.getDescription()), text(row.getMavenGroupId()),
                    text(row.getMavenArtifactId()), text(row.getVersion()), text(row.getScope()),
                    text(row.getRepository()), String.valueOf(row.getSortOrder()),
                    text(row.getCompatibilityRange()), String.valueOf(row.isStarter())));
        }

        @Override
        String label(DependencyEntryEntity row) {
            return kindWord(row.getProjectKind()) + " dependency " + row.getDepId();
        }

        @Override
        Long rowId(DependencyEntryEntity row) {
            return row.getId();
        }

        @Override
        ProjectKind kind(DependencyEntryEntity row) {
            return row.getProjectKind();
        }

        @Override
        String dependencyId(DependencyEntryEntity row) {
            return row.getDepId();
        }

        @Override
        String missingParent(DependencyEntryEntity shipped, Catalog catalog) {
            return catalog.groupFor(groupKey(shipped.getGroup(), catalog)) != null ? null
                    : "its group '" + shipped.getGroup().getName() + "' is not in the catalog";
        }

        @Override
        DependencyEntryEntity add(DependencyEntryEntity shipped, Catalog catalog) {
            DependencyEntryEntity row = new DependencyEntryEntity();
            row.setDepId(shipped.getDepId());
            copy(shipped, row, catalog);
            return entryRepo.save(row);
        }

        @Override
        void update(DependencyEntryEntity shipped, DependencyEntryEntity stored, Catalog catalog) {
            copy(shipped, stored, catalog);
            entryRepo.save(stored);
        }

        private void copy(DependencyEntryEntity shipped, DependencyEntryEntity row, Catalog catalog) {
            row.setGroup(catalog.groupFor(groupKey(shipped.getGroup(), catalog)));
            row.setProjectKind(shipped.getProjectKind());
            row.setName(shipped.getName());
            row.setDescription(shipped.getDescription());
            row.setMavenGroupId(shipped.getMavenGroupId());
            row.setMavenArtifactId(shipped.getMavenArtifactId());
            row.setVersion(shipped.getVersion());
            row.setScope(shipped.getScope());
            row.setRepository(shipped.getRepository());
            row.setSortOrder(shipped.getSortOrder());
            row.setCompatibilityRange(shipped.getCompatibilityRange());
            row.setStarter(shipped.isStarter());
        }

        @Override
        void delete(DependencyEntryEntity stored) {
            entryRepo.delete(stored);
        }

        /** Not while anything names it: an option, a rule, a file, a build row, a starter, a module, a template set. */
        @Override
        boolean removable(DependencyEntryEntity stored) {
            entityManager.flush();
            String id = stored.getDepId();
            return subOptionRepo.countByDependencyId(id) + compatibilityRepo.countBySourceDepIdOrTargetDepId(id, id)
                    + fileRepo.countByDependencyId(id) + buildRepo.countByDependencyId(id)
                    + starterDepRepo.countByDepId(id) + moduleMappingRepo.countByDependencyId(id)
                    + defaultDepRepo.countByDepId(id) == 0;
        }

        /**
         * The group by the name the database has for it — on both sides when the shipped group
         * and the stored one are the same group, so a renamed group is not shown as a move.
         */
        @Override
        Object view(DependencyEntryEntity row, Catalog catalog) {
            DependencyGroupEntity group = row.getGroup();
            DependencyGroupEntity stands = group == null ? null : catalog.groupFor(groupKey(group, catalog));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("depId", row.getDepId());
            view.put("group", stands != null ? stands.getName() : group == null ? null : group.getName());
            view.put("name", row.getName());
            view.put("description", row.getDescription());
            view.put("mavenGroupId", row.getMavenGroupId());
            view.put("mavenArtifactId", row.getMavenArtifactId());
            view.put("version", row.getVersion());
            view.put("scope", row.getScope());
            view.put("repository", row.getRepository());
            view.put("compatibilityRange", row.getCompatibilityRange());
            view.put("starter", row.isStarter());
            view.put("sortOrder", row.getSortOrder());
            view.put("projectKind", row.getProjectKind());
            return view;
        }
    }

    // ── Sub-options ──────────────────────────────────────────────────────────

    private final class SubOptions extends Table<DependencySubOptionEntity> {

        SubOptions() {
            super(RowTable.SUB_OPTION);
        }

        @Override
        List<DependencySubOptionEntity> shipped(ProjectKind kind) throws IOException {
            List<DependencySubOptionEntity> out = new ArrayList<>();
            for (SubOptionDef d : readManifest(manifestDir(kind) + "sub-options.json", SubOptionDef.class)) {
                DependencySubOptionEntity e = new DependencySubOptionEntity();
                e.setDependencyId(d.depId());
                e.setOptionId(d.optionId());
                e.setLabel(d.label());
                e.setDescription(d.description());
                e.setSortOrder(d.sortOrder());
                e.setProjectKind(kind);
                out.add(e);
            }
            return out;
        }

        @Override
        List<DependencySubOptionEntity> stored() {
            return subOptionRepo.findAll();
        }

        @Override
        String key(DependencySubOptionEntity row, Catalog catalog) {
            return hash(String.join("\u0001", "option", row.getProjectKind().name(), text(row.getDependencyId()),
                    text(row.getOptionId())));
        }

        @Override
        String fingerprint(DependencySubOptionEntity row, Catalog catalog) {
            return hash(String.join("\u0001", text(row.getLabel()), text(row.getDescription()),
                    String.valueOf(row.getSortOrder())));
        }

        @Override
        String label(DependencySubOptionEntity row) {
            return kindWord(row.getProjectKind()) + " option " + row.getDependencyId() + " / " + row.getOptionId();
        }

        @Override
        Long rowId(DependencySubOptionEntity row) {
            return row.getId();
        }

        @Override
        ProjectKind kind(DependencySubOptionEntity row) {
            return row.getProjectKind();
        }

        @Override
        String dependencyId(DependencySubOptionEntity row) {
            return row.getDependencyId();
        }

        @Override
        String missingParent(DependencySubOptionEntity shipped, Catalog catalog) {
            return missingDependency(shipped.getDependencyId(), catalog);
        }

        @Override
        DependencySubOptionEntity add(DependencySubOptionEntity shipped, Catalog catalog) {
            return subOptionRepo.save(shipped);
        }

        @Override
        void update(DependencySubOptionEntity shipped, DependencySubOptionEntity stored, Catalog catalog) {
            stored.setLabel(shipped.getLabel());
            stored.setDescription(shipped.getDescription());
            stored.setSortOrder(shipped.getSortOrder());
            subOptionRepo.save(stored);
        }

        @Override
        void delete(DependencySubOptionEntity stored) {
            subOptionRepo.delete(stored);
        }
    }

    // ── Compatibility rules ──────────────────────────────────────────────────

    private final class Rules extends Table<DependencyCompatibilityEntity> {

        Rules() {
            super(RowTable.COMPATIBILITY_RULE);
        }

        @Override
        List<DependencyCompatibilityEntity> shipped(ProjectKind kind) throws IOException {
            List<DependencyCompatibilityEntity> out = new ArrayList<>();
            for (CompatibilityDef d : readManifest(manifestDir(kind) + "compatibility.json", CompatibilityDef.class)) {
                DependencyCompatibilityEntity e = new DependencyCompatibilityEntity();
                e.setSourceDepId(d.sourceDepId());
                e.setTargetDepId(d.targetDepId());
                e.setRelationType(DependencyCompatibilityEntity.RelationType.valueOf(d.relationType()));
                e.setDescription(d.description());
                e.setSortOrder(d.sortOrder());
                e.setProjectKind(kind);
                out.add(e);
            }
            return out;
        }

        @Override
        List<DependencyCompatibilityEntity> stored() {
            return compatibilityRepo.findAll();
        }

        @Override
        String key(DependencyCompatibilityEntity row, Catalog catalog) {
            return hash(String.join("\u0001", "rule", row.getProjectKind().name(), text(row.getSourceDepId()),
                    text(row.getTargetDepId()), String.valueOf(row.getRelationType())));
        }

        @Override
        String fingerprint(DependencyCompatibilityEntity row, Catalog catalog) {
            return hash(String.join("\u0001", text(row.getDescription()), String.valueOf(row.getSortOrder())));
        }

        @Override
        String label(DependencyCompatibilityEntity row) {
            return kindWord(row.getProjectKind()) + " rule " + row.getSourceDepId() + " " + row.getRelationType()
                    + " " + row.getTargetDepId();
        }

        @Override
        Long rowId(DependencyCompatibilityEntity row) {
            return row.getId();
        }

        @Override
        ProjectKind kind(DependencyCompatibilityEntity row) {
            return row.getProjectKind();
        }

        @Override
        String dependencyId(DependencyCompatibilityEntity row) {
            return row.getSourceDepId();
        }

        @Override
        String missingParent(DependencyCompatibilityEntity shipped, Catalog catalog) {
            String source = missingDependency(shipped.getSourceDepId(), catalog);
            return source != null ? source : missingDependency(shipped.getTargetDepId(), catalog);
        }

        @Override
        DependencyCompatibilityEntity add(DependencyCompatibilityEntity shipped, Catalog catalog) {
            return compatibilityRepo.save(shipped);
        }

        @Override
        void update(DependencyCompatibilityEntity shipped, DependencyCompatibilityEntity stored, Catalog catalog) {
            stored.setDescription(shipped.getDescription());
            stored.setSortOrder(shipped.getSortOrder());
            compatibilityRepo.save(stored);
        }

        @Override
        void delete(DependencyCompatibilityEntity stored) {
            compatibilityRepo.delete(stored);
        }
    }

    // ── File contributions ───────────────────────────────────────────────────

    /** What makes a file contribution the same row from one release to the next. */
    static String keyOf(FileContributionEntity f) {
        return hash(String.join("\u0001", "file", f.getProjectKind().name(), text(f.getDependencyId()),
                text(f.getTargetPath()), text(f.getSubOptionId()), text(f.getJavaVersion()), text(f.getNodeVersion())));
    }

    /**
     * Everything about a file contribution that is not its identity. The content is hashed with
     * its line endings normalized ({@link EntityTemplateSetSync#hash}): a Windows checkout ships
     * CRLF and the admin's textarea hands an untouched row back with LF — neither is an edit.
     * A missing substitution type is {@code NONE}, which is what the admin form saves for it.
     */
    static String fingerprintOf(FileContributionEntity f) {
        FileContributionEntity.SubstitutionType substitution = f.getSubstitutionType() == null
                ? FileContributionEntity.SubstitutionType.NONE : f.getSubstitutionType();
        return hash(String.join("\u0001", String.valueOf(f.getFileType()), substitution.name(),
                String.valueOf(f.getSortOrder()), EntityTemplateSetSync.hash(f.getContent())));
    }

    private final class Files extends Table<FileContributionEntity> {

        Files() {
            super(RowTable.FILE_CONTRIBUTION);
        }

        @Override
        List<FileContributionEntity> shipped(ProjectKind kind) throws IOException {
            List<FileContributionEntity> out = new ArrayList<>();
            for (FileContribDef d : readManifest(manifestDir(kind) + "file-contributions.json", FileContribDef.class)) {
                FileContributionEntity e = new FileContributionEntity();
                e.setDependencyId(d.depId());
                e.setFileType(FileContributionEntity.FileType.valueOf(d.fileType()));
                e.setContent(d.contentResource() != null ? readClasspath(d.contentResource()) : d.content());
                e.setTargetPath(d.targetPath());
                e.setSubstitutionType(d.substitutionType() != null
                        ? FileContributionEntity.SubstitutionType.valueOf(d.substitutionType()) : null);
                e.setJavaVersion(d.javaVersion());
                e.setNodeVersion(d.nodeVersion());
                e.setSubOptionId(d.subOptionId());
                e.setSortOrder(d.sortOrder());
                e.setProjectKind(kind);
                out.add(e);
            }
            return out;
        }

        @Override
        List<FileContributionEntity> stored() {
            return fileRepo.findAll();
        }

        @Override
        String key(FileContributionEntity row, Catalog catalog) {
            return keyOf(row);
        }

        @Override
        String fingerprint(FileContributionEntity row, Catalog catalog) {
            return fingerprintOf(row);
        }

        @Override
        String label(FileContributionEntity f) {
            StringBuilder label = new StringBuilder(kindWord(f.getProjectKind()))
                    .append(" file ").append(f.getDependencyId()).append(" → ").append(f.getTargetPath());
            if (f.getSubOptionId() != null) label.append(" (option ").append(f.getSubOptionId()).append(')');
            if (f.getJavaVersion() != null) label.append(" (Java ").append(f.getJavaVersion()).append(')');
            if (f.getNodeVersion() != null) label.append(" (Node ").append(f.getNodeVersion()).append(')');
            return label.toString();
        }

        @Override
        Long rowId(FileContributionEntity row) {
            return row.getId();
        }

        @Override
        ProjectKind kind(FileContributionEntity row) {
            return row.getProjectKind();
        }

        @Override
        String dependencyId(FileContributionEntity row) {
            return row.getDependencyId();
        }

        @Override
        String missingParent(FileContributionEntity shipped, Catalog catalog) {
            return missingDependency(shipped.getDependencyId(), catalog);
        }

        @Override
        FileContributionEntity add(FileContributionEntity shipped, Catalog catalog) {
            return fileRepo.save(shipped);
        }

        @Override
        void update(FileContributionEntity shipped, FileContributionEntity stored, Catalog catalog) {
            stored.setContent(shipped.getContent());
            stored.setFileType(shipped.getFileType());
            stored.setSubstitutionType(shipped.getSubstitutionType());
            stored.setSortOrder(shipped.getSortOrder());
            fileRepo.save(stored);
        }

        @Override
        void delete(FileContributionEntity stored) {
            fileRepo.delete(stored);
        }
    }

    // ── Build customizations ─────────────────────────────────────────────────

    static String keyOf(BuildCustomizationEntity b) {
        return hash(String.join("\u0001", "build", b.getProjectKind().name(), text(b.getDependencyId()),
                String.valueOf(b.getCustomizationType()), text(b.getMavenGroupId()), text(b.getMavenArtifactId()),
                text(b.getRepoId()), text(b.getSubOptionId()), text(b.getCompatibilityRange())));
    }

    static String fingerprintOf(BuildCustomizationEntity b) {
        return hash(String.join("\u0001", text(b.getVersion()), text(b.getExcludeFromGroupId()),
                text(b.getExcludeFromArtifactId()), text(b.getRepoName()), text(b.getRepoUrl()),
                String.valueOf(b.isSnapshotsEnabled()), text(b.getScope()), String.valueOf(b.getSortOrder())));
    }

    private final class Builds extends Table<BuildCustomizationEntity> {

        Builds() {
            super(RowTable.BUILD_CUSTOMIZATION);
        }

        @Override
        List<BuildCustomizationEntity> shipped(ProjectKind kind) throws IOException {
            List<BuildCustomizationEntity> out = new ArrayList<>();
            for (BuildCustomDef d : readManifest(manifestDir(kind) + "build-customizations.json", BuildCustomDef.class)) {
                BuildCustomizationEntity e = new BuildCustomizationEntity();
                e.setDependencyId(d.depId());
                e.setCustomizationType(BuildCustomizationEntity.CustomizationType.valueOf(d.type()));
                e.setMavenGroupId(d.mavenGroupId());
                e.setMavenArtifactId(d.mavenArtifactId());
                e.setVersion(d.version());
                e.setExcludeFromGroupId(d.excludeFromGroupId());
                e.setExcludeFromArtifactId(d.excludeFromArtifactId());
                e.setRepoId(d.repoId());
                e.setRepoName(d.repoName());
                e.setRepoUrl(d.repoUrl());
                if (d.snapshotsEnabled() != null) e.setSnapshotsEnabled(d.snapshotsEnabled());
                e.setScope(d.scope());
                e.setSubOptionId(d.subOptionId());
                e.setCompatibilityRange(d.compatibilityRange());
                e.setSortOrder(d.sortOrder());
                e.setProjectKind(kind);
                out.add(e);
            }
            return out;
        }

        @Override
        List<BuildCustomizationEntity> stored() {
            return buildRepo.findAll();
        }

        @Override
        String key(BuildCustomizationEntity row, Catalog catalog) {
            return keyOf(row);
        }

        @Override
        String fingerprint(BuildCustomizationEntity row, Catalog catalog) {
            return fingerprintOf(row);
        }

        @Override
        String label(BuildCustomizationEntity b) {
            StringBuilder label = new StringBuilder(kindWord(b.getProjectKind()))
                    .append(" build ").append(b.getDependencyId()).append(' ').append(b.getCustomizationType());
            if (!text(b.getMavenGroupId()).isEmpty() || !text(b.getMavenArtifactId()).isEmpty()) {
                label.append(' ').append(text(b.getMavenGroupId()));
                if (!text(b.getMavenGroupId()).isEmpty() && !text(b.getMavenArtifactId()).isEmpty()) label.append(':');
                label.append(text(b.getMavenArtifactId()));
            }
            if (!text(b.getRepoId()).isEmpty()) label.append(' ').append(b.getRepoId());
            if (b.getSubOptionId() != null) label.append(" (option ").append(b.getSubOptionId()).append(')');
            if (b.getCompatibilityRange() != null) label.append(" (Boot ").append(b.getCompatibilityRange()).append(')');
            return label.toString();
        }

        @Override
        Long rowId(BuildCustomizationEntity row) {
            return row.getId();
        }

        @Override
        ProjectKind kind(BuildCustomizationEntity row) {
            return row.getProjectKind();
        }

        @Override
        String dependencyId(BuildCustomizationEntity row) {
            return row.getDependencyId();
        }

        @Override
        String missingParent(BuildCustomizationEntity shipped, Catalog catalog) {
            return missingDependency(shipped.getDependencyId(), catalog);
        }

        @Override
        BuildCustomizationEntity add(BuildCustomizationEntity shipped, Catalog catalog) {
            return buildRepo.save(shipped);
        }

        @Override
        void update(BuildCustomizationEntity shipped, BuildCustomizationEntity stored, Catalog catalog) {
            stored.setVersion(shipped.getVersion());
            stored.setExcludeFromGroupId(shipped.getExcludeFromGroupId());
            stored.setExcludeFromArtifactId(shipped.getExcludeFromArtifactId());
            stored.setRepoName(shipped.getRepoName());
            stored.setRepoUrl(shipped.getRepoUrl());
            stored.setSnapshotsEnabled(shipped.isSnapshotsEnabled());
            stored.setScope(shipped.getScope());
            stored.setSortOrder(shipped.getSortOrder());
            buildRepo.save(stored);
        }

        @Override
        void delete(BuildCustomizationEntity stored) {
            buildRepo.delete(stored);
        }
    }

    // ── Reading the manifests ────────────────────────────────────────────────

    /**
     * The groups of one kind's {@code dependencies.json}, unsaved, each with its entries in its
     * collection and every entry pointing back at it — which is how a fresh database is seeded
     * (saving a group saves its entries) and how the sync knows a shipped entry's group.
     */
    List<DependencyGroupEntity> shippedCatalog(ProjectKind kind) throws IOException {
        DependenciesManifest manifest = new ObjectMapper()
                .readValue(readClasspath(manifestDir(kind) + "dependencies.json"), DependenciesManifest.class);
        List<DependencyGroupEntity> out = new ArrayList<>();
        for (GroupDef gd : manifest.groups()) {
            DependencyGroupEntity g = new DependencyGroupEntity();
            g.setName(gd.name());
            g.setSortOrder(gd.sortOrder());
            g.setProjectKind(kind);
            for (EntryDef ed : gd.entries()) {
                DependencyEntryEntity e = new DependencyEntryEntity();
                e.setGroup(g);
                e.setDepId(ed.depId());
                e.setName(ed.name());
                e.setDescription(ed.description());
                e.setMavenGroupId(ed.mavenGroupId());
                e.setMavenArtifactId(ed.mavenArtifactId());
                e.setVersion(ed.version());
                e.setScope(ed.scope());
                e.setRepository(ed.repository());
                e.setSortOrder(ed.sortOrder());
                e.setCompatibilityRange(ed.compatibilityRange());
                if (ed.starter() != null) e.setStarter(ed.starter());
                e.setProjectKind(kind);
                g.getEntries().add(e);
            }
            out.add(g);
        }
        return out;
    }

    private static String manifestDir(ProjectKind kind) {
        return kind == ProjectKind.FRONTEND ? "catalog/frontend/" : "catalog/";
    }

    private static <T> List<T> readManifest(String path, Class<T> elementType) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(readClasspath(path),
                mapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    private static String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) throw new IOException("Classpath resource not found: " + path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    // ── Small things ─────────────────────────────────────────────────────────

    private static String missingDependency(String dependencyId, Catalog catalog) {
        return catalog.hasDependency(dependencyId) ? null
                : "the dependency '" + dependencyId + "' is not in the catalog";
    }

    private static String kindWord(ProjectKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Null and blank are one value: the entities' setters turn one into the other on some columns only. */
    static String text(String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    static String hash(String s) {
        return EntityTemplateSetSync.hash(s);
    }
}
