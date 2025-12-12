-- ===================================================================
-- Migration: V139__Create_sharepoint_locations_table.sql
-- Description: Create shared SharePoint location library
-- Author: Claude Code
-- Date: 2025-12-12
-- ===================================================================
--
-- Purpose:
-- Creates a central library of reusable SharePoint locations that can be
-- referenced by multiple template signing stores. This eliminates duplicate
-- configuration and provides a single source of truth for SharePoint paths.
--
-- Business Logic:
--   - Each location represents a unique SharePoint folder
--   - Locations can be reused across multiple templates
--   - is_active allows soft-disable without deletion
--   - display_order supports UI ordering
--
-- SharePoint Integration:
--   - site_url: Full SharePoint site URL (e.g., https://trustworks.sharepoint.com/sites/Documents)
--   - drive_name: Document library name (e.g., "Documents", "Contracts")
--   - folder_path: Optional path within the library (e.g., "Signed/Contracts/2025")
--
-- Related Tables:
--   - template_signing_stores: Will reference this table via location_uuid (V140)
--
-- ===================================================================

CREATE TABLE sharepoint_locations (
    -- Primary key
    uuid VARCHAR(36) PRIMARY KEY
        COMMENT 'Unique identifier for the SharePoint location',

    -- User-friendly identification
    name VARCHAR(255) NOT NULL
        COMMENT 'User-friendly name for this location',

    -- SharePoint location configuration
    site_url VARCHAR(500) NOT NULL
        COMMENT 'SharePoint site URL (e.g., https://trustworks.sharepoint.com/sites/Documents)',
    drive_name VARCHAR(255) NOT NULL
        COMMENT 'Document library name within the SharePoint site',
    folder_path VARCHAR(500)
        COMMENT 'Optional folder path within the document library',

    -- Status and ordering
    is_active BOOLEAN DEFAULT TRUE NOT NULL
        COMMENT 'Whether this location is active and available for use',
    display_order INT NOT NULL DEFAULT 1
        COMMENT 'Display order for UI (ascending)',

    -- Audit timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
        COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
        COMMENT 'Last modification timestamp',

    -- Unique constraint: prevent duplicate locations
    CONSTRAINT uk_sharepoint_locations_path
        UNIQUE (site_url, drive_name, folder_path)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Shared library of reusable SharePoint folder locations';

-- Index for active location queries
CREATE INDEX idx_sharepoint_locations_active ON sharepoint_locations(is_active);

-- Index for display ordering
CREATE INDEX idx_sharepoint_locations_order ON sharepoint_locations(display_order);

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- Usage Pattern:
-- 1. Admin creates shared SharePoint locations in the library
-- 2. When configuring a template signing store, admin selects from existing locations
-- 3. Multiple templates can reference the same location
-- 4. Changing a location updates all templates using it
--
-- Future Enhancements:
-- - Location-level access permissions
-- - Location validation (verify SharePoint path exists)
-- - Location usage statistics
--
-- ===================================================================
