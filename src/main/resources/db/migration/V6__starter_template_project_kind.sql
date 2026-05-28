-- V6: Extend the project_kind discriminator to starter_template so the admin
-- panel's Backend/Frontend pill can filter starter bundles. Existing rows
-- default to BACKEND, matching the convention established in V4.

ALTER TABLE starter_template ADD COLUMN project_kind VARCHAR(20) NOT NULL DEFAULT 'BACKEND';

CREATE INDEX idx_starter_template_kind ON starter_template(project_kind);
