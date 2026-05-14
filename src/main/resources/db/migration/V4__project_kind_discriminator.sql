-- V4: Add project_kind discriminator to the catalog tables so a single DB can
-- carry both the Spring Boot backend catalog (BACKEND, default) and the new
-- React/Vite frontend catalog (FRONTEND). Also adds a `scope` column on
-- build_customization for the FE ADD_NPM_DEPENDENCY field reinterpretation
-- (scope='dev' → devDependencies, otherwise → dependencies).

ALTER TABLE dependency_group       ADD COLUMN project_kind VARCHAR(20) NOT NULL DEFAULT 'BACKEND';
ALTER TABLE dependency_entry       ADD COLUMN project_kind VARCHAR(20) NOT NULL DEFAULT 'BACKEND';
ALTER TABLE file_contribution      ADD COLUMN project_kind VARCHAR(20) NOT NULL DEFAULT 'BACKEND';
ALTER TABLE build_customization    ADD COLUMN project_kind VARCHAR(20) NOT NULL DEFAULT 'BACKEND';
ALTER TABLE dependency_sub_option  ADD COLUMN project_kind VARCHAR(20) NOT NULL DEFAULT 'BACKEND';
ALTER TABLE dependency_compatibility ADD COLUMN project_kind VARCHAR(20) NOT NULL DEFAULT 'BACKEND';

ALTER TABLE build_customization    ADD COLUMN scope VARCHAR(20);

CREATE INDEX idx_dep_group_kind          ON dependency_group(project_kind);
CREATE INDEX idx_dep_entry_kind          ON dependency_entry(project_kind);
CREATE INDEX idx_file_contrib_kind       ON file_contribution(project_kind);
CREATE INDEX idx_build_custom_kind       ON build_customization(project_kind);
CREATE INDEX idx_sub_option_kind         ON dependency_sub_option(project_kind);
CREATE INDEX idx_compat_kind             ON dependency_compatibility(project_kind);
