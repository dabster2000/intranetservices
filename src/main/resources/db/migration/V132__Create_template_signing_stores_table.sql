-- ===================================================================
-- Migration: V132__Create_template_signing_stores_table.sql
-- Description: Create table to store SharePoint folder configurations
--              for auto-uploading signed documents
-- Author: Claude Code
-- Date: 2025-12-08
-- ===================================================================
--
-- Purpose:
-- Enables templates to have a SharePoint folder configured where signed
-- documents are automatically saved after signing completion.
--
-- Business Logic:
--   - Each document template can have ONE signing store configured (1:1 relationship)
--   - Signing store defines SharePoint location (site, drive, folder path)
--   - display_name provides user-friendly identification in dropdowns
--   - is_active allows soft-disable without deletion
--   - display_order supports future multi-store scenarios
--
-- SharePoint Integration:
--   - site_url: Full SharePoint site URL (e.g., https://trustworks.sharepoint.com/sites/Documents)
--   - drive_name: Document library name (e.g., "Documents", "Contracts")
--   - folder_path: Optional path within the library (e.g., "Signed/Contracts/2025")
--
-- Related Tables:
--   - document_templates (V127): Parent table via foreign key
--   - signing_cases (V130): Will reference this table for auto-upload
--
-- ===================================================================

CREATE TABLE template_signing_stores (
    -- Primary key
    uuid VARCHAR(36) PRIMARY KEY
        COMMENT 'Unique identifier for the signing store configuration',

    -- Foreign key to document templates
    template_uuid VARCHAR(36) NOT NULL
        COMMENT 'Reference to the document template this store is configured for',

    -- SharePoint location configuration
    site_url VARCHAR(500) NOT NULL
        COMMENT 'SharePoint site URL (e.g., https://trustworks.sharepoint.com/sites/Documents)',
    drive_name VARCHAR(255) NOT NULL
        COMMENT 'Document library name within the SharePoint site',
    folder_path VARCHAR(500)
        COMMENT 'Optional folder path within the document library',

    -- Display configuration
    display_name VARCHAR(255)
        COMMENT 'User-friendly display name for UI selection',

    -- Status and ordering
    is_active BOOLEAN DEFAULT TRUE
        COMMENT 'Whether this signing store is active and available for use',
    display_order INT NOT NULL DEFAULT 1
        COMMENT 'Display order for UI (ascending, supports future multi-store)',

    -- Audit timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
        COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
        COMMENT 'Last modification timestamp',

    -- Foreign key constraint (CASCADE on delete: if template is deleted, remove signing store)
    CONSTRAINT fk_template_signing_stores_template_uuid
        FOREIGN KEY (template_uuid)
        REFERENCES document_templates(uuid)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Unique constraint: one signing store per template (1:1 relationship)
    CONSTRAINT uk_template_signing_stores_template_uuid
        UNIQUE (template_uuid)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='SharePoint folder configurations for auto-uploading signed documents';

-- Index for template lookup performance
CREATE INDEX idx_template_signing_stores_template ON template_signing_stores(template_uuid);

-- Index for active store queries
CREATE INDEX idx_template_signing_stores_active ON template_signing_stores(is_active);

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- Usage Pattern:
-- 1. Admin configures signing store for a document template
-- 2. When document is sent for signing, the store config is copied to signing_cases
-- 3. Upon signing completion, document is auto-uploaded to configured SharePoint folder
-- 4. Upload status tracked in signing_cases (see V133 migration)
--
-- Future Enhancements:
-- - Multiple stores per template (remove uk_tss_template, add selection UI)
-- - Store-level access permissions
-- - Naming pattern configuration for uploaded files
--
-- ===================================================================
