-- V35: two catalog entries that named no Maven artifact (2026-11).
--
-- An entry without coordinates that is not file-only is a Spring Boot starter to the Initializr
-- framework, which writes org.springframework.boot:spring-boot-starter-<depId> into the pom. For
-- mongodb that is spring-boot-starter-mongodb and for mail-sampler spring-boot-starter-mail-sampler;
-- neither exists, so a project with either did not build. MongoDB's starter is
-- spring-boot-starter-data-mongodb; mail-sampler brings spring-boot-starter-mail through its build
-- customization and adds nothing of its own, so it is file-only.
--
-- Dependency entries are seeded once, so an existing database needs this; catalog/dependencies.json
-- carries the same for a fresh one (where these statements find no rows). An entry an admin has
-- already given coordinates, or made file-only, is left alone.

UPDATE initializer_dependency_entry
   SET maven_group_id = 'org.springframework.boot',
       maven_artifact_id = 'spring-boot-starter-data-mongodb'
 WHERE dep_id = 'mongodb'
   AND project_kind = 'BACKEND'
   AND is_starter = TRUE
   AND (maven_artifact_id IS NULL OR maven_artifact_id = '');

UPDATE initializer_dependency_entry
   SET is_starter = FALSE
 WHERE dep_id = 'mail-sampler'
   AND project_kind = 'BACKEND'
   AND is_starter = TRUE
   AND (maven_artifact_id IS NULL OR maven_artifact_id = '');
