-- ============================================================================
-- V267: Rename page_migration to page_registry and clean up Vaadin columns
-- ============================================================================
-- Removes Vaadin-specific columns (vaadin_route, vaadin_view_class, migrated_at)
-- Renames is_migrated to is_visible (same semantics: TRUE = show in menu)
-- Renames table from page_migration to page_registry
-- ============================================================================

-- Step 1: Drop Vaadin-specific columns
ALTER TABLE page_migration
    DROP COLUMN vaadin_route,
    DROP COLUMN vaadin_view_class,
    DROP COLUMN migrated_at;

-- Step 2: Rename is_migrated to is_visible
ALTER TABLE page_migration
    CHANGE COLUMN is_migrated is_visible BOOLEAN NOT NULL DEFAULT FALSE;

-- Step 3: Drop old index and create new one
DROP INDEX idx_is_migrated ON page_migration;
CREATE INDEX idx_is_visible ON page_migration (is_visible);

-- Step 4: Rename table
RENAME TABLE page_migration TO page_registry;
