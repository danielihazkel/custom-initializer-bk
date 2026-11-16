-- V30: What a generation event was, beyond its endpoint (2026-11, generation audit).
--
-- kind:   backend | wizard | multimodule | fullstack | frontend
-- action: download (a ZIP) | patch (an update) | preview | other (a detection call, recorded
--         before only ZIPs, previews and patches were)
-- department, wizards (csv: sql, openapi, soap, events), entity_count (a fullstack model's).

ALTER TABLE initializer_generation_event ADD COLUMN kind VARCHAR(16);
ALTER TABLE initializer_generation_event ADD COLUMN action VARCHAR(16);
ALTER TABLE initializer_generation_event ADD COLUMN department VARCHAR(64);
ALTER TABLE initializer_generation_event ADD COLUMN wizards VARCHAR(64);
ALTER TABLE initializer_generation_event ADD COLUMN entity_count INTEGER;

-- The rows already there, from their endpoint.
UPDATE initializer_generation_event
   SET action = CASE
           WHEN endpoint LIKE '%.zip' THEN 'download'
           WHEN endpoint LIKE '%.patch' THEN 'patch'
           WHEN endpoint LIKE '%.preview' THEN 'preview'
           ELSE 'other' END
 WHERE action IS NULL;

UPDATE initializer_generation_event
   SET kind = CASE
           WHEN endpoint LIKE 'frontend/%' THEN 'frontend'
           WHEN endpoint LIKE 'starter-fullstack%' THEN 'fullstack'
           WHEN endpoint LIKE 'starter-multimodule%' THEN 'multimodule'
           WHEN endpoint LIKE 'starter-wizard%' THEN 'wizard'
           ELSE 'backend' END
 WHERE kind IS NULL;

CREATE INDEX idx_generation_event_status ON initializer_generation_event (status);
