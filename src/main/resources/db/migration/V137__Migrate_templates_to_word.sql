-- ===================================================================
-- Migration: V137__Migrate_templates_to_word.sql
-- Description: Migrate template system from Thymeleaf HTML to Word documents
-- Author: Claude Code
-- Date: 2025-12-11
-- ===================================================================
--
-- Purpose:
-- This migration transitions the template system from inline Thymeleaf HTML
-- content to Word documents (.docx) stored in S3. Instead of storing template
-- content in the database, we now store a reference (file_uuid) to the Word
-- file in S3 storage.
--
-- Key Changes:
--   1. Add file_uuid column to reference Word templates in S3
--   2. Add original_filename column to preserve upload filename
--   3. Drop the document_content constraint (no longer required)
--   4. Make document_content nullable (will be removed in V138)
--   5. Delete all existing Thymeleaf templates (per migration decision)
--
-- NOTE: This migration is idempotent and safe to re-run after partial failure.
--
-- ===================================================================

-- ===================================================================
-- Step 1: Delete all existing Thymeleaf templates
-- ===================================================================
-- Per migration decision, we remove all existing templates since they use
-- Thymeleaf HTML format which is incompatible with the new Word template system.
-- These DELETE statements are naturally idempotent.

DELETE FROM template_documents;
DELETE FROM template_placeholders;
DELETE FROM template_default_signers;
DELETE FROM template_signing_schemas;
DELETE FROM template_signing_stores;
DELETE FROM document_templates;

-- ===================================================================
-- Step 2: Drop the document_content constraint (if exists)
-- ===================================================================
-- In MariaDB, we can use DROP CONSTRAINT IF EXISTS syntax (10.2.1+)

ALTER TABLE template_documents
    DROP CONSTRAINT IF EXISTS chk_td_document_content_not_empty;

-- ===================================================================
-- Step 3: Add file_uuid column for S3 reference (if not exists)
-- ===================================================================

ALTER TABLE template_documents
    ADD COLUMN IF NOT EXISTS file_uuid VARCHAR(36) NULL
    COMMENT 'UUID reference to files table for S3-stored Word template (.docx)';

-- ===================================================================
-- Step 4: Add original_filename column (if not exists)
-- ===================================================================

ALTER TABLE template_documents
    ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255) NULL
    COMMENT 'Original filename of uploaded Word document (e.g., "contract-template.docx")';

-- ===================================================================
-- Step 5: Make document_content nullable
-- ===================================================================
-- This is idempotent - running MODIFY multiple times is safe.

ALTER TABLE template_documents
    MODIFY COLUMN document_content LONGTEXT NULL
    COMMENT 'DEPRECATED: Will be removed in V138. Word templates use file_uuid instead.';

-- ===================================================================
-- Step 6: Create index for file lookups (if not exists)
-- ===================================================================

CREATE INDEX IF NOT EXISTS idx_td_file_uuid ON template_documents(file_uuid);

-- ===================================================================
-- Step 7: Update content_type column size and default
-- ===================================================================
-- Increase VARCHAR to 100 to accommodate the longer Word MIME type.
-- This is idempotent - running MODIFY multiple times is safe.

ALTER TABLE template_documents
    MODIFY COLUMN content_type VARCHAR(100) NOT NULL
    DEFAULT 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    COMMENT 'MIME type of the template file (Word document)';

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- Placeholder Syntax Change:
-- - Old (Thymeleaf): $${PLACEHOLDER_KEY} (escaped for Flyway)
-- - New (Word/poi-tl): {{PLACEHOLDER_KEY}}
--
-- File Storage:
-- - Word templates stored in S3 bucket 'trustworksfiles'
-- - file_uuid references files.uuid for metadata
-- - Actual binary content retrieved via S3FileService
--
-- ===================================================================
