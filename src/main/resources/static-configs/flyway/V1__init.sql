-- Initial schema migration.
-- Replace this file with the first version of your real schema.
-- Subsequent migrations should be named V2__describe_change.sql, V3__..., etc.

CREATE TABLE IF NOT EXISTS app_metadata (
    id          BIGINT       NOT NULL PRIMARY KEY,
    schema_key  VARCHAR(64)  NOT NULL UNIQUE,
    schema_val  VARCHAR(256)
);

INSERT INTO app_metadata (id, schema_key, schema_val) VALUES (1, 'bootstrap', 'ok');
