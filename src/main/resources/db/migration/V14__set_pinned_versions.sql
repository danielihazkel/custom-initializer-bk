-- V8: replace range-based compatibility with a single pinned default version.
--
-- The range model from V6 was the wrong shape: the user wanted to declare a
-- single version the set generates with (so the wizard can pre-fill its Boot/Java
-- dropdowns), not an interval the wizard's chosen version must fall inside.
-- No data migration needed — V6 columns were nullable, never seeded, and only
-- briefly shipped.

ALTER TABLE initializer_entity_template_set DROP COLUMN boot_version_range;
ALTER TABLE initializer_entity_template_set DROP COLUMN java_version_range;
ALTER TABLE initializer_entity_template_set ADD COLUMN boot_version VARCHAR(50);
ALTER TABLE initializer_entity_template_set ADD COLUMN java_version VARCHAR(10);
