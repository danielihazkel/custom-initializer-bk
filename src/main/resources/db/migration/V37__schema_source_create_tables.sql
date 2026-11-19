-- V37: a schema source may let the Fullstack tab create a model's tables on it (2026-11).
--
-- Off for every source until an admin turns it on: with it, POST /starter-fullstack.create-tables
-- opens a connection that writes, as the source's user. Connections typed in by hand never write.

ALTER TABLE initializer_schema_source ADD COLUMN allow_create_tables BOOLEAN DEFAULT FALSE NOT NULL;
