-- ===================================================================
-- Migration: V140__Migrate_signing_stores_to_shared_locations.sql
-- Description: Migrate template_signing_stores to use shared SharePoint locations
-- Author: Claude Code
-- Date: 2025-12-12
-- ===================================================================
--
-- Purpose:
-- Refactors template_signing_stores to reference the new sharepoint_locations
-- table instead of storing SharePoint paths directly. This enables reuse of
-- location configurations across multiple templates.
--
-- Migration Steps:
-- 1. Add new columns (location_uuid, display_name_override)
-- 2. Extract unique locations from existing data into sharepoint_locations
-- 3. Update template_signing_stores with location references
-- 4. Add foreign key constraint
-- 5. Drop redundant columns
-- 6. Make location_uuid NOT NULL
--
-- ===================================================================

-- Step 1: Add new columns to template_signing_stores
ALTER TABLE template_signing_stores
    ADD COLUMN location_uuid VARCHAR(36)
        COMMENT 'Reference to shared SharePoint location',
    ADD COLUMN display_name_override VARCHAR(255)
        COMMENT 'Optional override for location name in this context';

-- Step 2: Extract unique locations from existing signing stores into sharepoint_locations
-- Uses COALESCE to handle NULL folder_path values in the unique matching
INSERT INTO sharepoint_locations (uuid, name, site_url, drive_name, folder_path, is_active, display_order, created_at, updated_at)
SELECT
    UUID() as uuid,
    COALESCE(
        -- Use first non-null display_name for this location combination
        (SELECT tss2.display_name
         FROM template_signing_stores tss2
         WHERE tss2.site_url = tss.site_url
           AND tss2.drive_name = tss.drive_name
           AND COALESCE(tss2.folder_path, '') = COALESCE(tss.folder_path, '')
           AND tss2.display_name IS NOT NULL
         LIMIT 1),
        -- Fallback: generate name from path components
        CONCAT(tss.drive_name, COALESCE(CONCAT(' / ', tss.folder_path), ''))
    ) as name,
    tss.site_url,
    tss.drive_name,
    tss.folder_path,
    TRUE as is_active,
    ROW_NUMBER() OVER (ORDER BY tss.site_url, tss.drive_name, COALESCE(tss.folder_path, '')) as display_order,
    NOW() as created_at,
    NOW() as updated_at
FROM template_signing_stores tss
GROUP BY tss.site_url, tss.drive_name, COALESCE(tss.folder_path, ''), tss.folder_path;

-- Step 3: Update template_signing_stores.location_uuid based on matching site_url/drive_name/folder_path
UPDATE template_signing_stores tss
    JOIN sharepoint_locations sl
    ON tss.site_url = sl.site_url
        AND tss.drive_name = sl.drive_name
        AND COALESCE(tss.folder_path, '') = COALESCE(sl.folder_path, '')
SET tss.location_uuid = sl.uuid,
    tss.display_name_override = CASE
                                    WHEN tss.display_name IS NOT NULL
                                         AND tss.display_name != sl.name
                                        THEN tss.display_name
                                    ELSE NULL
        END;

-- Step 4: Add foreign key constraint
ALTER TABLE template_signing_stores
    ADD CONSTRAINT fk_template_signing_stores_location
        FOREIGN KEY (location_uuid)
            REFERENCES sharepoint_locations(uuid)
            ON DELETE RESTRICT
            ON UPDATE CASCADE;

-- Step 5: Drop old columns (site_url, drive_name, folder_path, display_name)
ALTER TABLE template_signing_stores
    DROP COLUMN site_url,
    DROP COLUMN drive_name,
    DROP COLUMN folder_path,
    DROP COLUMN display_name;

-- Step 6: Make location_uuid NOT NULL
ALTER TABLE template_signing_stores
    MODIFY COLUMN location_uuid VARCHAR(36) NOT NULL
        COMMENT 'Reference to shared SharePoint location';

-- Add index for location lookups
CREATE INDEX idx_template_signing_stores_location ON template_signing_stores(location_uuid);

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- Rollback Considerations:
-- This migration is destructive (drops columns). Before running in production:
-- 1. Backup template_signing_stores table
-- 2. Verify sharepoint_locations has all expected entries
-- 3. Verify location_uuid values are correctly set
--
-- Post-Migration Verification:
-- SELECT COUNT(*) FROM sharepoint_locations;
-- SELECT COUNT(*) FROM template_signing_stores WHERE location_uuid IS NULL;
-- SELECT tss.uuid, sl.name, sl.site_url, sl.drive_name, sl.folder_path
-- FROM template_signing_stores tss
-- JOIN sharepoint_locations sl ON tss.location_uuid = sl.uuid;
--
-- ===================================================================
