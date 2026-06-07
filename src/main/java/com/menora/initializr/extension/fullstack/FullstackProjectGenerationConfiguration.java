package com.menora.initializr.extension.fullstack;

import com.menora.initializr.config.EntityDefinitionContext;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.EntityScaffoldContext;
import com.menora.initializr.fullstack.FullstackRenderer;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

/**
 * Spring Initializr per-request project-generation config that runs entity scaffolding
 * for the backend half of the fullstack flow. Sibling to
 * {@code DynamicProjectGenerationConfiguration}. Short-circuits when no entities are
 * populated, so the existing {@code /starter.zip} flow is unaffected.
 *
 * <p>Wired via {@code META-INF/spring.factories}.
 */
@ProjectGenerationConfiguration
public class FullstackProjectGenerationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FullstackProjectGenerationConfiguration.class);

    @Bean
    @Order(50)
    ProjectContributor entityScaffoldContributor(
            ProjectDescription description,
            EntityDefinitionContext entityContext,
            EntityTemplateSetRepository setRepo,
            EntityTemplateFileRepository fileRepo,
            ProjectOptionsContext optionsContext) {
        return projectRoot -> {
            if (entityContext.isEmpty()) {
                return;
            }
            String setKey = entityContext.getBackendSetKey();
            if (setKey == null || setKey.isBlank()) {
                log.warn("Entity context populated but no backendTemplateSet selected — skipping CRUD scaffolding");
                return;
            }
            EntityTemplateSetEntity set = setRepo.findBySetKey(setKey).orElse(null);
            if (set == null) {
                log.warn("backendTemplateSet '{}' not found — skipping CRUD scaffolding", setKey);
                return;
            }
            if (set.getKind() != EntityTemplateSetEntity.Kind.BACKEND_JAVA) {
                log.warn("Template set '{}' is not BACKEND_JAVA (kind={}) — skipping", setKey, set.getKind());
                return;
            }
            List<EntityTemplateFileEntity> files = fileRepo.findBySetIdOrderBySortOrderAsc(set.getId());
            if (files.isEmpty()) {
                log.warn("backendTemplateSet '{}' has no files", setKey);
                return;
            }
            List<EntityDefinition> entities = entityContext.all();
            String domainPackage = entityContext.getDomainPackage() != null
                    ? entityContext.getDomainPackage() : description.getPackageName();
            Map<String, Object> projectCtx = EntityScaffoldContext.buildProjectContext(
                    description.getArtifactId(),
                    description.getGroupId(),
                    description.getVersion(),
                    description.getPackageName(),
                    domainPackage,
                    description.getLanguage().jvmVersion(),
                    description.getPackaging() != null ? description.getPackaging().id() : null,
                    entities);
            // Gate generated Bean Validation (@Valid, @NotNull, @Size, the 400 handler) on the
            // validation starter actually being selected — otherwise the imports won't resolve.
            projectCtx.put("hasValidation",
                    description.getRequestedDependencies().containsKey("validation"));
            // Opt-in scaffolding extras (via opts: { "scaffold": ["tests", ...] }). The matching
            // gatedBy flag on a template file toggles whether it is rendered. Uses spring-boot-
            // starter-test, which the Initializr framework adds to every generated project.
            projectCtx.put("optScaffoldTests", optionsContext.hasOption("scaffold", "tests"));
            log.info("Rendering backend CRUD scaffolding: set='{}', {} files, {} entities",
                    setKey, files.size(), entities.size());
            FullstackRenderer.render(files, projectCtx, entities, projectRoot);
        };
    }
}
