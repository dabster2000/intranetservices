-- V136: Remove deprecated template_content column and related constraint
-- 
-- This migration completes the deprecation cycle started in V135.
-- All data was successfully migrated to the template_documents table in V135.
-- 
-- BREAKING CHANGE: Single-document templates via templateContent are no longer supported.
-- All templates must use the multi-document pattern (documents list) going forward.
--
-- Timeline:
-- - V135 (2025-12-01): Multi-document support added, data migrated, templateContent marked DEPRECATED
-- - V136 (2025-12-09): Complete removal of templateContent column and constraint

START TRANSACTION;

-- Step 1: Drop the check constraint that was preventing NULL values
ALTER TABLE document_templates 
    DROP CONSTRAINT chk_dt_template_content_not_empty;

-- Step 2: Drop the deprecated template_content column
-- All data has been safely migrated to template_documents table
ALTER TABLE document_templates 
    DROP COLUMN template_content;

COMMIT;
