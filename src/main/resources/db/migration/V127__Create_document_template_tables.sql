-- ======================================================================================
-- V127: Create Document Template Management Tables
-- ======================================================================================
-- Purpose: Enable dynamic document template creation with configurable placeholders
-- Feature: Document Template Management System
-- Context: Supports AI-powered contract generation with customizable placeholders
-- ======================================================================================

-- ======================================================================================
-- Table 1: Document Templates
-- ======================================================================================
-- Stores reusable document templates with placeholders for AI-powered document generation
-- Supports contract templates, proposals, letters, and other business documents
START TRANSACTION;

CREATE TABLE document_templates (
    uuid VARCHAR(36) PRIMARY KEY COMMENT 'Unique identifier for the template',
    name VARCHAR(255) NOT NULL COMMENT 'Display name of the template (e.g., "Standard Consulting Contract")',
    description TEXT COMMENT 'Detailed description of the template purpose and use cases',
    category VARCHAR(50) NOT NULL COMMENT 'Template category: CONTRACT, PROPOSAL, LETTER, REPORT, OTHER',
    template_content LONGTEXT NOT NULL COMMENT 'Template content with {{placeholder}} syntax for dynamic values',
    active BOOLEAN DEFAULT TRUE NOT NULL COMMENT 'Soft delete flag - FALSE = template is archived',

    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL COMMENT 'Last update timestamp',
    created_by VARCHAR(36) COMMENT 'User UUID who created this template',
    modified_by VARCHAR(36) COMMENT 'User UUID who last modified this template',

    -- Data integrity constraints
    CONSTRAINT chk_dt_template_content_not_empty
        CHECK (CHAR_LENGTH(TRIM(template_content)) > 0)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Document templates with dynamic placeholders for AI-powered generation';

-- Indexes for performance
CREATE INDEX idx_dt_category ON document_templates(category);
CREATE INDEX idx_dt_active ON document_templates(active);
CREATE INDEX idx_dt_created_at ON document_templates(created_at);

-- ======================================================================================
-- Table 2: Template Placeholders
-- ======================================================================================
-- Defines placeholders used in document templates with metadata for UI rendering
-- Supports various field types (text, date, number, select) and validation rules

CREATE TABLE template_placeholders (
    uuid VARCHAR(36) PRIMARY KEY COMMENT 'Unique identifier for the placeholder',
    template_uuid VARCHAR(36) NOT NULL COMMENT 'FK to document_templates.uuid',
    placeholder_key VARCHAR(100) NOT NULL COMMENT 'Placeholder key used in template (e.g., "client_name", "contract_start_date")',
    label VARCHAR(255) NOT NULL COMMENT 'Human-readable label for UI display (e.g., "Client Name", "Contract Start Date")',
    field_type VARCHAR(20) NOT NULL COMMENT 'Input field type: TEXT, NUMBER, DATE, SELECT, TEXTAREA, BOOLEAN',
    required BOOLEAN DEFAULT FALSE NOT NULL COMMENT 'Whether this field is mandatory for template processing',
    display_order INT NOT NULL COMMENT 'Order in which fields appear in UI (ascending, 1-based)',
    default_value TEXT COMMENT 'Default value pre-filled in the UI',
    help_text TEXT COMMENT 'Contextual help text explaining the field purpose',
    source VARCHAR(50) COMMENT 'Data source: MANUAL, DATABASE, COMPUTED, SYSTEM',
    field_group VARCHAR(100) COMMENT 'Logical grouping for UI organization (e.g., "Client Info", "Contract Terms")',
    validation_rules JSON COMMENT 'JSON validation rules: {"minLength": 3, "maxLength": 100, "pattern": "regex"}',
    select_options JSON COMMENT 'Options for SELECT fields: [{"value": "DKK", "label": "Danish Krone"}, ...]',

    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL COMMENT 'Last update timestamp',
    created_by VARCHAR(36) COMMENT 'User UUID who created this placeholder',
    modified_by VARCHAR(36) COMMENT 'User UUID who last modified this placeholder',

    -- Foreign key constraint
    CONSTRAINT fk_tp_template
        FOREIGN KEY (template_uuid)
        REFERENCES document_templates(uuid)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Unique constraint: one placeholder key per template
    CONSTRAINT uq_template_placeholder_key
        UNIQUE (template_uuid, placeholder_key),

    -- Data integrity constraints
    CONSTRAINT chk_tp_positive_display_order
        CHECK (display_order > 0),

    CONSTRAINT chk_tp_select_options_required
        CHECK (
            field_type != 'SELECT' OR
            (field_type = 'SELECT' AND select_options IS NOT NULL)
        )

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Placeholder definitions for document templates with UI metadata';

-- Indexes for performance
CREATE INDEX idx_tp_template_uuid ON template_placeholders(template_uuid);
CREATE INDEX idx_tp_display_order ON template_placeholders(template_uuid, display_order);
CREATE INDEX idx_tp_created_at ON template_placeholders(created_at);

COMMIT;

-- ======================================================================================
-- Migration Notes
-- ======================================================================================
--
-- ✅ Two new tables created for document template management
-- ✅ Foreign key constraint ensures referential integrity with CASCADE delete
-- ✅ Unique constraint prevents duplicate placeholder keys per template
-- ✅ Soft delete pattern (active flag) preserves template history
-- ✅ Comprehensive indexing for query performance
-- ✅ Check constraints enforce data quality:
--    - Template content cannot be empty
--    - Display order must be positive
--    - SELECT fields must have options defined
-- ✅ JSON columns support flexible validation rules and select options
-- ✅ All tables use utf8mb4 for full Unicode support
-- ✅ Timestamps track creation and modification times
-- ✅ Audit fields (created_by, modified_by) enable user accountability
--
-- Field Types Supported:
-- - TEXT: Single-line text input
-- - TEXTAREA: Multi-line text input
-- - NUMBER: Numeric input with optional validation
-- - DATE: Date picker
-- - SELECT: Dropdown with predefined options
-- - BOOLEAN: Checkbox/toggle
--
-- Data Sources:
-- - MANUAL: User manually enters value
-- - DATABASE: Value fetched from database (e.g., client name from contracts)
-- - COMPUTED: Value calculated from other fields
-- - SYSTEM: System-generated value (e.g., current date, username)
--
-- Example validation_rules JSON:
-- {"minLength": 3, "maxLength": 100, "pattern": "^[A-Z]{2,3}$", "min": 0, "max": 100}
--
-- Example select_options JSON:
-- [{"value": "DKK", "label": "Danish Krone"}, {"value": "EUR", "label": "Euro"}]
--
-- Next Steps:
-- 1. JPA entities implementation (@Entity classes)
-- 2. Panache repositories (TemplateRepository, PlaceholderRepository)
-- 3. REST endpoints (TemplateResource)
-- 4. Service layer with validation logic
-- 5. Vaadin UI for template management
-- 6. Seed data migration for default templates
--
-- ======================================================================================
