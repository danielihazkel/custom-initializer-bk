-- V16: Optional gating for fullstack template files.
--
-- When set, the file is only rendered if the named boolean flag is truthy in the
-- generation context (e.g. "optScaffoldTests" set from opts: { "scaffold": ["tests"] }).
-- A null/blank value means the file is always rendered (the existing behavior).

ALTER TABLE initializer_entity_template_file ADD COLUMN gated_by VARCHAR(80);
