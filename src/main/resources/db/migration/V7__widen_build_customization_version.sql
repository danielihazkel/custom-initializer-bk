-- build_customization.version was originally a Maven artifact version, capped
-- at VARCHAR(50). When ADD_NPM_SCRIPT / ADD_VITE_PLUGIN customizations were
-- introduced, this column got reinterpreted as the script command / plugin call
-- expression, where 50 chars is too restrictive. Widen it.
ALTER TABLE build_customization ALTER COLUMN version VARCHAR(2000);
