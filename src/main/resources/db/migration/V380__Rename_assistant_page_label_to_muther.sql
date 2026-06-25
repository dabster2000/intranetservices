-- ====================================================================
-- V380: Rename the /assistant nav menu label to 'MU/TH/ER'.
-- V379 (already applied) inserted the page_registry row as 'Assistent';
-- this only updates the visible sidebar label. Idempotent UPDATE by key.
-- ====================================================================

UPDATE page_registry
SET page_label = 'MU/TH/ER'
WHERE page_key = 'assistant';
