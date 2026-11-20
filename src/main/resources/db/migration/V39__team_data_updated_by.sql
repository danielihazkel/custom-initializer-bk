-- V39: who last changed a team model, a team preset and a team template.
--
-- The three were open to every caller: anyone could overwrite or delete what a colleague had
-- saved, and nothing said who had. A row is now changed or deleted only by whoever created it
-- (created_by, from the single sign-on gateway's user header) or by a signed-in admin — a row
-- with no recorded creator stays open — and every change records who made it. See
-- config/TeamDataAccess.

ALTER TABLE initializer_fullstack_model ADD COLUMN updated_by VARCHAR(255);
ALTER TABLE initializer_backend_preset ADD COLUMN updated_by VARCHAR(255);
ALTER TABLE initializer_fullstack_template ADD COLUMN updated_by VARCHAR(255);
