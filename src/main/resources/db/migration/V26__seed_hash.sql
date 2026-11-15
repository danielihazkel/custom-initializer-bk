-- V26: Shipped fullstack templates and examples follow the release.
--
-- seed_hash is the sha256 of the content a row was last seeded with. On startup a row whose
-- content still hashes to it is replaced by the shipped version; an edited row is kept.
-- (V24 and V25 belong to the design-kit work on its own branch, hence the gap.)
--
-- NULL marks a row from before this migration (replaced once, then stamped); 'custom' marks a
-- row an admin created or imported.

ALTER TABLE initializer_entity_template_file ADD COLUMN seed_hash VARCHAR(64);
ALTER TABLE initializer_fullstack_example ADD COLUMN seed_hash VARCHAR(64);
