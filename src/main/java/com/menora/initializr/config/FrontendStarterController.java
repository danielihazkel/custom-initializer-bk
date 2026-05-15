package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.ColorPaletteEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.ColorPaletteRepository;
import com.menora.initializr.config.ProjectPreviewController.PreviewFile;
import com.menora.initializr.config.ProjectPreviewController.PreviewResponse;
import com.menora.initializr.extension.frontend.FrontendCompatibilityResolver;
import com.menora.initializr.extension.frontend.FrontendProjectDescription;
import com.menora.initializr.extension.frontend.FrontendProjectGenerator;
import com.menora.initializr.extension.frontend.FrontendVersionRangeFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Endpoints for the React/TS/Vite/FSD frontend generator.
 *
 * <p>The existing {@link InitializrWebConfiguration} servlet filter already
 * populates {@link ProjectOptionsContext} from {@code opts-<dep>} params for
 * every request, so sub-options reach {@link FrontendProjectGenerator} without
 * any extra wiring.
 */
@RestController
@RequestMapping("/frontend")
public class FrontendStarterController {

    private final FrontendProjectGenerator generator;
    private final FrontendProperties properties;
    private final DependencyConfigService configService;
    private final ColorPaletteRepository colorPaletteRepo;
    private final FrontendVersionRangeFilter versionFilter;
    private final FrontendCompatibilityResolver compatibilityResolver;

    public FrontendStarterController(FrontendProjectGenerator generator,
                                     FrontendProperties properties,
                                     DependencyConfigService configService,
                                     ColorPaletteRepository colorPaletteRepo,
                                     FrontendVersionRangeFilter versionFilter,
                                     FrontendCompatibilityResolver compatibilityResolver) {
        this.generator = generator;
        this.properties = properties;
        this.configService = configService;
        this.colorPaletteRepo = colorPaletteRepo;
        this.versionFilter = versionFilter;
        this.compatibilityResolver = compatibilityResolver;
    }

    @GetMapping("/starter.zip")
    public ResponseEntity<byte[]> generate(
            @RequestParam(defaultValue = "demo") String projectName,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "") String scope,
            @RequestParam(defaultValue = "") String appTitle,
            @RequestParam(required = false) String reactVersion,
            @RequestParam(required = false) String nodeVersion,
            @RequestParam(required = false) String packageManager,
            @RequestParam(defaultValue = "/") String basePath,
            @RequestParam(defaultValue = "") String dependencies,
            @RequestParam(defaultValue = "") String colorPalette
    ) throws IOException {

        FrontendProjectDescription desc = buildDescription(
                projectName, description, scope, appTitle,
                reactVersion, nodeVersion, packageManager, basePath, dependencies, colorPalette);

        byte[] zip = generator.generate(desc);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + projectName + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    /**
     * GET /frontend/starter.preview — returns the generated project as JSON
     * (file list + tree) instead of a ZIP. Accepts the same query parameters as
     * /frontend/starter.zip.
     *
     * <p>The {@link InitializrWebConfiguration} filter already populates
     * {@link ProjectOptionsContext} from {@code opts-*} params, so sub-option
     * gating works identically to the zip endpoint.
     */
    @GetMapping("/starter.preview")
    public PreviewResponse preview(
            @RequestParam(defaultValue = "demo") String projectName,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "") String scope,
            @RequestParam(defaultValue = "") String appTitle,
            @RequestParam(required = false) String reactVersion,
            @RequestParam(required = false) String nodeVersion,
            @RequestParam(required = false) String packageManager,
            @RequestParam(defaultValue = "/") String basePath,
            @RequestParam(defaultValue = "") String dependencies,
            @RequestParam(defaultValue = "") String colorPalette
    ) throws IOException {

        FrontendProjectDescription desc = buildDescription(
                projectName, description, scope, appTitle,
                reactVersion, nodeVersion, packageManager, basePath, dependencies, colorPalette);

        Map<String, String> fileMap = generator.generateFileMap(desc);
        List<PreviewFile> files = fileMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new PreviewFile(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        return new PreviewResponse(files, PreviewTreeBuilder.buildTree(
                files.stream().map(PreviewFile::path).toList()));
    }

    private FrontendProjectDescription buildDescription(String projectName,
                                                        String description,
                                                        String scope,
                                                        String appTitle,
                                                        String reactVersion,
                                                        String nodeVersion,
                                                        String packageManager,
                                                        String basePath,
                                                        String dependencies,
                                                        String colorPalette) {
        FrontendProjectDescription desc = new FrontendProjectDescription();
        desc.setProjectName(projectName);
        desc.setDescription(description);
        desc.setScope(scope);
        desc.setAppTitle(appTitle.isBlank() ? toTitleCase(projectName) : appTitle);
        desc.setReactVersion(reactVersion == null || reactVersion.isBlank()
                ? properties.defaultReactVersion() : reactVersion);
        desc.setNodeVersion(nodeVersion == null || nodeVersion.isBlank()
                ? properties.defaultNodeVersion() : nodeVersion);
        desc.setPackageManager(packageManager == null || packageManager.isBlank()
                ? properties.defaultPackageManager() : packageManager);
        desc.setBasePath(basePath);
        desc.setColorPaletteId(colorPalette);
        desc.setTypescriptVersion(properties.getPinned().getTypescript());
        desc.setViteVersion(properties.getPinned().getVite());

        if (!dependencies.isBlank()) {
            Arrays.stream(dependencies.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(desc.getDependencies()::add);
        }

        // Drop deps whose compatibilityRange excludes the requested React version.
        // Silently filtered (with a warn log) so the generator never fails because
        // the UI sent a stale selection after the user switched React versions.
        Map<String, DependencyEntryEntity> entriesById = entriesById();
        Set<String> filtered = versionFilter.filterCompatibleDepIds(
                desc.getDependencies(), entriesById, desc.getReactVersion());

        // Apply REQUIRES/CONFLICTS rules so direct API hits (curl, IntelliJ) get
        // the same enforcement the UI surfaces via banners.
        Set<String> resolved = compatibilityResolver.resolve(filtered, entriesById.keySet());

        desc.getDependencies().clear();
        desc.getDependencies().addAll(resolved);

        return desc;
    }

    private Map<String, DependencyEntryEntity> entriesById() {
        Map<String, DependencyEntryEntity> out = new LinkedHashMap<>();
        for (DependencyGroupEntity g : configService.getAllGroupsWithEntries(ProjectKind.FRONTEND)) {
            for (DependencyEntryEntity e : g.getEntries()) {
                out.put(e.getDepId(), e);
            }
        }
        return out;
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadata(@RequestParam(required = false) String reactVersion) {
        String resolvedReactVersion = (reactVersion == null || reactVersion.isBlank())
                ? properties.defaultReactVersion() : reactVersion;
        Map<String, Object> root = new LinkedHashMap<>();

        // Form defaults
        root.put("defaults", Map.of(
                "projectName", properties.getDefaults().getProjectName(),
                "description", properties.getDefaults().getDescription(),
                "appTitle", properties.getDefaults().getAppTitle(),
                "scope", properties.getDefaults().getScope(),
                "reactVersion", properties.defaultReactVersion(),
                "nodeVersion", properties.defaultNodeVersion(),
                "packageManager", properties.defaultPackageManager()
        ));

        // Version dropdowns + pkg mgr pill
        root.put("reactVersions", properties.getReactVersions());
        root.put("nodeVersions", properties.getNodeVersions());
        root.put("packageManagers", properties.getPackageManagers());
        root.put("pinned", properties.getPinned());

        // Dependency catalog: groups → entries → sub-options
        List<DependencyGroupEntity> groups = configService.getAllGroupsWithEntries(ProjectKind.FRONTEND);
        Map<String, List<DependencySubOptionEntity>> subOptsByDep =
                configService.getAllSubOptions(ProjectKind.FRONTEND);

        List<Map<String, Object>> groupsOut = new ArrayList<>();
        for (DependencyGroupEntity g : groups) {
            Map<String, Object> gOut = new LinkedHashMap<>();
            gOut.put("name", g.getName());
            gOut.put("sortOrder", g.getSortOrder());
            List<Map<String, Object>> entriesOut = new ArrayList<>();
            for (DependencyEntryEntity e : g.getEntries()) {
                if (!versionFilter.matches(e, resolvedReactVersion)) continue;
                Map<String, Object> eOut = new LinkedHashMap<>();
                eOut.put("id", e.getDepId());
                eOut.put("name", e.getName());
                eOut.put("description", e.getDescription());
                eOut.put("sortOrder", e.getSortOrder());
                if (e.getCompatibilityRange() != null && !e.getCompatibilityRange().isBlank()) {
                    eOut.put("versionRange", e.getCompatibilityRange());
                }
                List<Map<String, String>> subOpts = subOptsByDep.getOrDefault(e.getDepId(), List.of())
                        .stream()
                        .map(s -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("id", s.getOptionId());
                            m.put("label", s.getLabel());
                            m.put("description", s.getDescription());
                            return m;
                        })
                        .collect(Collectors.toList());
                if (!subOpts.isEmpty()) eOut.put("subOptions", subOpts);
                entriesOut.add(eOut);
            }
            gOut.put("entries", entriesOut);
            groupsOut.add(gOut);
        }
        root.put("dependencies", groupsOut);
        root.put("reactVersion", resolvedReactVersion);

        // Color palettes (admin-managed, frontend-only)
        List<Map<String, Object>> palettesOut = new ArrayList<>();
        for (ColorPaletteEntity p : colorPaletteRepo.findAllByOrderBySortOrderAsc()) {
            Map<String, Object> pOut = new LinkedHashMap<>();
            pOut.put("id", p.getPaletteId());
            pOut.put("name", p.getName());
            pOut.put("description", p.getDescription());
            pOut.put("primary", p.getPrimary());
            pOut.put("secondary", p.getSecondary());
            pOut.put("accent", p.getAccent());
            pOut.put("error", p.getError());
            pOut.put("isDefault", p.isDefault());
            pOut.put("sortOrder", p.getSortOrder());
            palettesOut.add(pOut);
        }
        root.put("colorPalettes", palettesOut);
        return root;
    }

    private static String toTitleCase(String s) {
        if (s == null || s.isBlank()) return "";
        String[] parts = s.split("[-_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
