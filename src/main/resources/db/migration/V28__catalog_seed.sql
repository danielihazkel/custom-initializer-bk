-- V28: Shipped catalog rows follow the release.
--
-- The file contributions and build customizations of the dependency catalog were seeded once:
-- a release that fixed a Dockerfile, a datasource config or a BOM version reached a new database
-- and no existing one. This table is what the application remembers about each row a release
-- ships, so that on start it can tell a row nobody touched (brought up to the release) from one
-- an admin edited or deleted (left alone). See db/CatalogSync.
--
-- It is a table of its own rather than a column on the two catalog tables because a deleted row
-- has to be remembered too — otherwise every start would put it back.
--
-- seed_key      sha256 of the shipped row's identity (kind, dependency, target path, ...)
-- row_table     FILE_CONTRIBUTION or BUILD_CUSTOMIZATION
-- label         the identity in words, for the log and the admin
-- applied_hash  fingerprint of the shipped row as it was last written to the database;
--               NULL: this database's row was never the shipped one (it differed, or was
--               missing, when the release first saw it)
-- kept          an admin chose to keep their own row: no longer reported
--
-- Empty on an existing database until its first start on this release, which records every
-- shipped row without changing any (see CatalogSync, "baseline").

CREATE TABLE initializer_catalog_seed (
    seed_key     VARCHAR(64)   NOT NULL PRIMARY KEY,
    row_table    VARCHAR(30)   NOT NULL,
    label        VARCHAR(1000) NOT NULL,
    applied_hash VARCHAR(64),
    kept         BOOLEAN       NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMP     NOT NULL
);
