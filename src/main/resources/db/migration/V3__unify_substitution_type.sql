-- V3: collapse PROJECT/PACKAGE substitution types into a single MUSTACHE value.
-- All existing templates use only simple {{var}} tokens, which Mustache handles
-- identically to the legacy String.replace() path.
UPDATE file_contribution
   SET substitution_type = 'MUSTACHE'
 WHERE substitution_type IN ('PROJECT', 'PACKAGE');
