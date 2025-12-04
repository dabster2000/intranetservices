-- =====================================================
-- V128: Create template_default_signers table
-- =====================================================
-- Purpose: Store default signers for document templates.
--          These are pre-populated when a template is selected
--          for document signing.
--
-- Features:
--   - signer_group: 1-based signing order (same group = parallel signing)
--   - name/email: Support placeholder syntax for dynamic resolution
--   - role: Optional role description (e.g., Employee, Manager, CEO)
--   - display_order: Order within same signer_group
--
-- Author: Claude Code
-- Date: 2025-12-04
-- =====================================================

CREATE TABLE template_default_signers (
    uuid VARCHAR(36) PRIMARY KEY,
    template_uuid VARCHAR(36) NOT NULL,
    signer_group INT NOT NULL DEFAULT 1 COMMENT '1-based signing order, same group = parallel',
    name VARCHAR(255) NOT NULL COMMENT 'Signer name, supports placeholder syntax',
    email VARCHAR(255) NOT NULL COMMENT 'Signer email, supports placeholder syntax',
    role VARCHAR(100) COMMENT 'Role like Employee, Manager, CEO',
    display_order INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT fk_tds_template FOREIGN KEY (template_uuid)
        REFERENCES document_templates(uuid) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_tds_positive_group CHECK (signer_group > 0),
    CONSTRAINT chk_tds_positive_order CHECK (display_order > 0),

    INDEX idx_tds_template_uuid (template_uuid),
    INDEX idx_tds_display_order (template_uuid, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
