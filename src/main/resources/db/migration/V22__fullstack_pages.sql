-- V22: Frontend page layouts for fullstack generation.
--
-- per_page: a template file rendered once per page of the request's `pages` layout (the
-- src/app/screens/* files). Never rendered for the classic layout (no pages).
-- pages/settings: an example can now carry a page layout and the editor settings it applies
-- (template sets, scaffold options, locale, dashboard copy, palette), both JSON text.

ALTER TABLE initializer_entity_template_file ADD COLUMN per_page BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE initializer_fullstack_example ADD COLUMN pages CLOB;
ALTER TABLE initializer_fullstack_example ADD COLUMN settings CLOB;
