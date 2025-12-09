-- ===================================================================
-- Migration: V135__Create_template_documents_table.sql
-- Description: Create table to support multiple documents per template
--              and migrate existing template_content data
-- Author: Claude Code
-- Date: 2025-12-09
-- ===================================================================
--
-- Purpose:
-- Enables document templates to contain multiple documents, supporting
-- complex contract packages (e.g., main contract + appendices + schedules).
-- Each document maintains its own content while sharing the parent template's
-- placeholders and signing configuration.
--
-- Business Logic:
--   - One template can have MANY documents (1:N relationship)
--   - Each document has display_order for consistent rendering sequence
--   - Documents maintain individual content_type for mixed document formats
--   - display_order determines merging/presentation order
--
-- Data Migration:
--   - Existing template_content is migrated to template_documents
--   - Original column marked deprecated but preserved for backward compatibility
--   - Applications should read from template_documents going forward
--
-- Related Tables:
--   - document_templates (V127): Parent table via foreign key
--   - template_placeholders (V127): Placeholders apply to all documents
--   - template_signing_schemas (V129): Signing config applies to merged result
--
-- Rollback Instructions:
--   1. Ensure no new templates created with multiple documents
--   2. DROP TABLE template_documents;
--   3. ALTER TABLE document_templates
--      MODIFY COLUMN template_content LONGTEXT NOT NULL
--      COMMENT 'Template content with {{placeholder}} syntax for dynamic values';
--
-- ===================================================================

START TRANSACTION;

-- ===================================================================
-- Step 1: Create template_documents table
-- ===================================================================
-- Stores individual documents belonging to a template
-- Supports multiple documents per template with ordering

CREATE TABLE template_documents (
    -- Primary keys
    id BIGINT AUTO_INCREMENT PRIMARY KEY
        COMMENT 'Auto-increment primary key for internal operations',
    uuid VARCHAR(36) NOT NULL UNIQUE
        COMMENT 'Unique identifier for API and external references',

    -- Foreign key to document templates
    template_uuid VARCHAR(36) NOT NULL
        COMMENT 'Reference to the parent document template',

    -- Document metadata
    document_name VARCHAR(255) NOT NULL
        COMMENT 'Display name for this document (e.g., "Main Contract", "Appendix A - Terms")',
    document_content LONGTEXT NOT NULL
        COMMENT 'Document content with {{placeholder}} syntax for dynamic values',
    display_order INT NOT NULL DEFAULT 1
        COMMENT 'Order in which documents appear/merge (ascending, 1-based)',
    content_type VARCHAR(50) NOT NULL DEFAULT 'application/pdf'
        COMMENT 'MIME type of the document content (default: application/pdf)',

    -- Audit timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        COMMENT 'Last modification timestamp',

    -- Foreign key constraint (CASCADE: if template deleted, remove all documents)
    CONSTRAINT fk_td_template_uuid
        FOREIGN KEY (template_uuid)
        REFERENCES document_templates(uuid)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Data integrity constraints
    CONSTRAINT chk_td_positive_display_order
        CHECK (display_order > 0),

    CONSTRAINT chk_td_document_content_not_empty
        CHECK (CHAR_LENGTH(TRIM(document_content)) > 0)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Individual documents belonging to a template, supporting multi-document packages';

-- ===================================================================
-- Step 2: Create indexes for performance
-- ===================================================================

-- Index for template lookup (foreign key queries)
CREATE INDEX idx_td_template_uuid ON template_documents(template_uuid);

-- Composite index for ordered document retrieval
CREATE INDEX idx_td_display_order ON template_documents(template_uuid, display_order);

-- ===================================================================
-- Step 3: Migrate existing template_content to template_documents
-- ===================================================================
-- Migrates all non-empty template_content as the primary document
-- Generates UUID using UUID() function for each migrated record

INSERT INTO template_documents (uuid, template_uuid, document_name, document_content, display_order)
SELECT
    UUID(),
    uuid,
    CONCAT(name, ' - Main Document'),
    template_content,
    1
FROM document_templates
WHERE template_content IS NOT NULL
  AND CHAR_LENGTH(TRIM(template_content)) > 0;

-- ===================================================================
-- Step 4: Mark original column as deprecated
-- ===================================================================
-- Keep for backward compatibility but indicate deprecation in comment
-- Applications should transition to reading from template_documents

ALTER TABLE document_templates
    MODIFY COLUMN template_content LONGTEXT NULL
    COMMENT 'DEPRECATED: Use template_documents table instead';

COMMIT;

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- Usage Pattern:
-- 1. When creating a new template, add documents to template_documents
-- 2. display_order determines sequence when merging/presenting documents
-- 3. Placeholders from template_placeholders apply to ALL documents
-- 4. Document generation merges all documents in display_order sequence
--
-- Backward Compatibility:
-- - Existing templates have their content migrated automatically
-- - template_content column kept for gradual transition
-- - New code should READ from template_documents
-- - New code should WRITE to template_documents only
--
-- Example Multi-Document Template:
-- Template: "Standard Consulting Agreement"
--   - Document 1: "Main Contract" (display_order=1)
--   - Document 2: "Appendix A - Rate Card" (display_order=2)
--   - Document 3: "Appendix B - Terms and Conditions" (display_order=3)
--   - Document 4: "Appendix C - Data Processing Agreement" (display_order=4)
--
-- Content Types Supported:
-- - application/pdf (default): PDF documents
-- - text/html: HTML templates
-- - text/plain: Plain text documents
-- - application/vnd.openxmlformats-officedocument.wordprocessingml.document: DOCX
--
-- Future Enhancements:
-- - Per-document placeholder subset selection
-- - Conditional document inclusion based on placeholder values
-- - Version history per document
-- - Document-level signing configuration
--
-- ===================================================================
