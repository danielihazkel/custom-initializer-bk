-- Allow FRONTEND build customizations (ADD_NPM_DEPENDENCY / ADD_NPM_SCRIPT /
-- ADD_VITE_PLUGIN) to be gated on a parent dependency's sub-option, matching
-- the gating that file_contribution.sub_option_id already provides on the file
-- side. NULL means "always apply" — the existing default behaviour for every
-- pre-V8 row.
ALTER TABLE initializer_build_customization ADD COLUMN sub_option_id VARCHAR(50);
