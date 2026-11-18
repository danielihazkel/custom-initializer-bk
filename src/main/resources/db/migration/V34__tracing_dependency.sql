-- V34: the tracing dependency (2026-11) and its rule, for an existing database.
--
-- Dependency entries and compatibility rules are seeded once, from catalog/*.json, on a fresh
-- database; an existing one would never offer a new entry (its file and build rows come with the
-- catalog sync). As in V29: this adds the entry to the Observability group beside Actuator and
-- Prometheus, and the rule that it requires Actuator. On a fresh database it runs before the
-- seeder, finds no Observability group and adds nothing, and the seeder adds both from the
-- manifests. An admin who has renamed the group, or already has an entry with this id, gets nothing.

INSERT INTO initializer_dependency_entry
       (group_id, dep_id, name, description, maven_group_id, maven_artifact_id, scope, sort_order, is_starter, project_kind)
SELECT g.id, 'tracing', 'Distributed Tracing (OpenTelemetry)',
       'Trace requests across services with Micrometer Tracing and OpenTelemetry; trace ids in every log line. Spans are sent over OTLP once MANAGEMENT_OTLP_TRACING_ENDPOINT is set',
       'io.micrometer', 'micrometer-tracing-bridge-otel', NULL, 2, TRUE, 'BACKEND'
  FROM initializer_dependency_group g
 WHERE g.id = (SELECT MIN(d.id) FROM initializer_dependency_group d
                 WHERE d.name = 'Observability' AND d.project_kind = 'BACKEND')
   AND NOT EXISTS (SELECT 1 FROM initializer_dependency_entry e WHERE e.dep_id = 'tracing');

INSERT INTO initializer_dependency_compatibility
       (source_dep_id, target_dep_id, relation_type, description, sort_order, project_kind)
SELECT 'tracing', 'actuator', 'REQUIRES',
       'Tracing is set up by Actuator''s observability auto-configuration — add Spring Boot Actuator', 13, 'BACKEND'
  FROM initializer_dependency_entry e
 WHERE e.dep_id = 'tracing'
   AND NOT EXISTS (SELECT 1 FROM initializer_dependency_compatibility c
                    WHERE c.source_dep_id = 'tracing' AND c.target_dep_id = 'actuator');
