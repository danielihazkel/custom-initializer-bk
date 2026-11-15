-- V27: A build customization may apply to one Spring Boot line only.
--
-- compatibility_range (the catalog's interval notation, e.g. [3.4.0,3.5.0)) lets one dependency
-- carry a different version per Boot line as several rows, of which only the matching one is
-- applied. NULL: every version, as before.

ALTER TABLE initializer_build_customization ADD COLUMN compatibility_range VARCHAR(50);

-- Existing databases (a fresh one is seeded from the manifests after this runs, so every
-- statement below finds nothing there).
--
-- The spring-cloud BOM imported into every backend was one row, right for Boot 3.2/3.3. It
-- becomes the row for those lines, beside one for 3.4 and one for 3.5.
UPDATE initializer_build_customization
   SET compatibility_range = '[3.2.0,3.4.0)'
 WHERE customization_type = 'ADD_BOM'
   AND maven_artifact_id = 'spring-cloud-dependencies'
   AND version LIKE '2023.0.%'
   AND compatibility_range IS NULL;

INSERT INTO initializer_build_customization
       (dependency_id, customization_type, maven_group_id, maven_artifact_id, version,
        snapshots_enabled, sort_order, project_kind, compatibility_range)
SELECT b.dependency_id, 'ADD_BOM', b.maven_group_id, b.maven_artifact_id, '2024.0.3',
       FALSE, b.sort_order, b.project_kind, '[3.4.0,3.5.0)'
  FROM initializer_build_customization b
 WHERE b.customization_type = 'ADD_BOM'
   AND b.maven_artifact_id = 'spring-cloud-dependencies'
   AND b.compatibility_range = '[3.2.0,3.4.0)'
   AND NOT EXISTS (SELECT 1 FROM initializer_build_customization x
                    WHERE x.maven_artifact_id = 'spring-cloud-dependencies'
                      AND x.compatibility_range = '[3.4.0,3.5.0)');

INSERT INTO initializer_build_customization
       (dependency_id, customization_type, maven_group_id, maven_artifact_id, version,
        snapshots_enabled, sort_order, project_kind, compatibility_range)
SELECT b.dependency_id, 'ADD_BOM', b.maven_group_id, b.maven_artifact_id, '2025.0.3',
       FALSE, b.sort_order, b.project_kind, '[3.5.0,4.0.0)'
  FROM initializer_build_customization b
 WHERE b.customization_type = 'ADD_BOM'
   AND b.maven_artifact_id = 'spring-cloud-dependencies'
   AND b.compatibility_range = '[3.2.0,3.4.0)'
   AND NOT EXISTS (SELECT 1 FROM initializer_build_customization x
                    WHERE x.maven_artifact_id = 'spring-cloud-dependencies'
                      AND x.compatibility_range = '[3.5.0,4.0.0)');

-- springdoc 2.6 (the openapi entry's own version) does not start on Spring Framework 6.2, which
-- Boot 3.4 brings; from there the dependency takes 2.8.
INSERT INTO initializer_build_customization
       (dependency_id, customization_type, maven_group_id, maven_artifact_id, version,
        snapshots_enabled, sort_order, project_kind, compatibility_range)
SELECT 'openapi', 'ADD_DEPENDENCY', 'org.springdoc', 'springdoc-openapi-starter-webmvc-ui', '2.8.17',
       FALSE, 0, 'BACKEND', '[3.4.0,4.0.0)'
  FROM initializer_dependency_entry e
 WHERE e.dep_id = 'openapi'
   AND e.project_kind = 'BACKEND'
   AND NOT EXISTS (SELECT 1 FROM initializer_build_customization x
                    WHERE x.dependency_id = 'openapi'
                      AND x.maven_artifact_id = 'springdoc-openapi-starter-webmvc-ui');
