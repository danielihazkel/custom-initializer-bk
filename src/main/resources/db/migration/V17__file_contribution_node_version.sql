-- V17: Node-version gating for frontend file contributions.
--
-- The frontend mirror of java_version (V1 baseline). When set, the contribution
-- is only written if it matches the project's selected Node version
-- (FrontendProjectDescription.nodeVersion). A null/blank value means the file
-- applies to every Node version (the existing behavior). Lets a single target
-- path (e.g. Dockerfile) resolve to an entirely different file per Node version,
-- exactly as java_version does for the backend Dockerfile.

ALTER TABLE initializer_file_contribution ADD COLUMN node_version VARCHAR(10);
