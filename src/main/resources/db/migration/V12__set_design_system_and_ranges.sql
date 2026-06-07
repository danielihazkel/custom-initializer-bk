-- V6: descriptive metadata on entity template sets.
--
-- design_system: which design system a FRONTEND_REACT set targets (null on
-- BACKEND_JAVA sets). Tagging only — the templates' actual rendering is still
-- whatever the set's files contain.
--
-- boot_version_range / java_version_range: optional interval-notation ranges
-- (e.g. "[3.2.0,4.0.0)") that constrain when a set is selectable. Null means
-- "compatible with all versions". Same syntax as dependency_entry.compatibility_range.

ALTER TABLE initializer_entity_template_set ADD COLUMN design_system VARCHAR(20);
ALTER TABLE initializer_entity_template_set ADD COLUMN boot_version_range VARCHAR(100);
ALTER TABLE initializer_entity_template_set ADD COLUMN java_version_range VARCHAR(100);

-- Back-fill the seeded FRONTEND_REACT set so existing prod DBs reflect what
-- the manifest now declares. New installs hit the same value via the seeder.
UPDATE initializer_entity_template_set SET design_system = 'TAILWIND' WHERE set_key = 'react-tailwind-crud';
