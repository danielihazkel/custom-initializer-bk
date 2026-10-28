-- V25: the house design kit (what a request naming no kit generates with; at most one, kept by
-- DesignKitService) and deprecated versions (still generate for the models pinned to them, but
-- are no longer offered). See DESIGN_STUDIO_PLAN.md at the monorepo root, P4.

ALTER TABLE initializer_design_kit ADD COLUMN default_kit BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE initializer_design_kit_version ADD COLUMN deprecated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE initializer_design_kit_version ADD COLUMN deprecation_note VARCHAR(500);
