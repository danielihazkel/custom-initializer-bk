-- V13: default-palette pointer on entity_template_set.
--
-- The color_palette table itself was already created by V5__color_palette.sql
-- (the master branch's column-naming wins this merge — see ColorPaletteEntity).
-- This migration only adds the FRONTEND_REACT template set's optional pointer
-- to a default palette, applied when the chosen design system is palette-aware
-- (MUI / Chakra / Mantine). Tailwind / shadcn / none ignore the palette entirely;
-- the field is still settable to support future design systems.

ALTER TABLE entity_template_set ADD COLUMN default_palette_id VARCHAR(80);
