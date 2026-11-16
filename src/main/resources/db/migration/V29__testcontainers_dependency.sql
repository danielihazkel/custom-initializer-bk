-- V29: the testcontainers dependency (2026-11, local run).
--
-- Dependency entries are seeded once, from catalog/dependencies.json, on a fresh database; an
-- existing one would never offer a new entry. This adds it to such a database's Data group
-- beside the drivers it tests. A fresh database is seeded after this runs, so the statement finds
-- no Data group there and the seeder adds the entry from the manifest instead. An admin who has
-- renamed the group, or already added an entry with this id, gets nothing from it.

INSERT INTO initializer_dependency_entry
       (group_id, dep_id, name, description, maven_group_id, maven_artifact_id, scope, sort_order, is_starter, project_kind)
SELECT g.id, 'testcontainers', 'Testcontainers',
       'Test on a real PostgreSQL, SQL Server or Oracle in Docker — a sample test that starts the application on its databases (skipped where Docker is not running)',
       'org.testcontainers', 'junit-jupiter', 'test', 10, TRUE, 'BACKEND'
  FROM initializer_dependency_group g
 WHERE g.id = (SELECT MIN(d.id) FROM initializer_dependency_group d
                 WHERE d.name = 'Data' AND d.project_kind = 'BACKEND')
   AND NOT EXISTS (SELECT 1 FROM initializer_dependency_entry e WHERE e.dep_id = 'testcontainers');
