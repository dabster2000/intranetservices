-- ============================================================================
-- V129__Create_template_signing_schemas_table.sql
-- ============================================================================
-- Purpose: Create table for storing signing schemas associated with document templates.
--          Allows templates to specify which MitID authentication methods are allowed
--          when signing documents via NextSign.
--
-- Signing schemas:
--   - MITID_SUBSTANTIAL: MitID with CPR validation (most secure)
--   - MITID_LOW: MitID without CPR validation
--   - MITID_BUSINESS: MitID Business authentication
--
-- Author: Claude Code
-- Date: 2025-12-04
-- ============================================================================

CREATE TABLE template_signing_schemas (
    uuid VARCHAR(36) PRIMARY KEY,
    template_uuid VARCHAR(36) NOT NULL,
    schema_type ENUM('MITID_SUBSTANTIAL', 'MITID_LOW', 'MITID_BUSINESS') NOT NULL,
    display_order INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT fk_tss_template FOREIGN KEY (template_uuid)
        REFERENCES document_templates(uuid) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT uk_tss_template_schema UNIQUE (template_uuid, schema_type),

    INDEX idx_tss_template_uuid (template_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
