-- V38: the catalog sync covers dependency groups, entries, sub-options and compatibility rules.
--
-- Those four were seeded once, so a release that added a dependency or reworded a rule needed a
-- hand-written data migration (V29, V34, V35). They now follow the release like the file
-- contributions and build customizations do (V28, db/CatalogSync): row_table takes four more
-- values — DEPENDENCY_GROUP, DEPENDENCY_ENTRY, SUB_OPTION, COMPATIBILITY_RULE.
--
-- row_id  the database row a shipped row stands for, kept for a dependency group only. A group
--         has nothing but its name to be known by, and an admin may rename it: with the row's id
--         remembered, the renamed group is still the shipped one (reported as differing, kept),
--         and the entries a later release ships for it land in it rather than in a second group.
--         NULL for every other table, whose rows are found by what identifies them.
--
-- Nothing is recorded here for the four new tables until the first start on this release, which
-- changes none of their rows (see CatalogSync, "baseline" — now per table).

ALTER TABLE initializer_catalog_seed ADD COLUMN row_id BIGINT;
