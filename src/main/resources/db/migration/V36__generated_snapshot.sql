-- V36: what each download generated (Round 13, 2026-11).
--
-- An update used to generate both of its sides with the generator of the day, so its patch carried
-- the model's change only. Each download's files are now kept here, named by their content (the
-- generator.snapshot of the project's menora-model.json), and an update starts from them: its patch
-- carries the generator's fixes since, too. Rows unused for menora.snapshots.retention-days go.
CREATE TABLE initializer_generated_snapshot (
    id            VARCHAR(64)  NOT NULL PRIMARY KEY,
    kind          VARCHAR(16),
    artifact_id   VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL,
    last_used_at  TIMESTAMP    NOT NULL,
    file_count    INT          NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    content       BLOB         NOT NULL
);

CREATE INDEX ix_generated_snapshot_last_used ON initializer_generated_snapshot (last_used_at);
