-- ===================================================================
-- Migration: V138__Remove_document_content_column.sql
-- Description: Remove deprecated document_content column from template_documents
-- Author: Claude Code
-- Date: 2025-12-11
-- ===================================================================
--
-- Purpose:
-- Complete the migration from Thymeleaf HTML templates to Word documents
-- by removing the now-unused document_content column.
--
-- Prerequisites:
--   - V137 has been successfully applied
--   - All template documents use file_uuid for Word templates
--   - No code references document_content anymore
--
-- Related Changes:
--   - TemplateDocumentEntity.java: documentContent field removed
--   - TemplateDocumentDTO: documentContent field removed
--   - DocumentEditorDialog: Changed from text editor to file upload
--
-- Rollback Instructions:
--   1. ALTER TABLE template_documents
--      ADD COLUMN document_content LONGTEXT NULL;
--
-- ===================================================================

START TRANSACTION;

-- Remove the deprecated document_content column
-- All templates now use Word files stored in S3 (file_uuid reference)
ALTER TABLE template_documents
    DROP COLUMN document_content;

-- Also remove the content_type column as it's no longer needed
-- Word templates always output PDF via NextSign conversion
ALTER TABLE template_documents
    DROP COLUMN content_type;

COMMIT;

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- After this migration, template_documents table has these columns:
-- - id (BIGINT, auto-increment primary key)
-- - uuid (VARCHAR(36), unique identifier)
-- - template_uuid (VARCHAR(36), foreign key to document_templates)
-- - document_name (VARCHAR(255), display name)
-- - display_order (INT, ordering for multi-document templates)
-- - file_uuid (VARCHAR(36), reference to S3-stored Word file)
-- - original_filename (VARCHAR(255), original upload filename)
-- - created_at (TIMESTAMP)
-- - updated_at (TIMESTAMP)
--
-- ===================================================================
