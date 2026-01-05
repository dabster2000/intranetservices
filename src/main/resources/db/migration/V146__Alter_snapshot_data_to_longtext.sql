-- ======================================================================================
-- V103: Alter immutable_snapshots.snapshot_data column from TEXT to LONGTEXT
-- ======================================================================================
-- Purpose: Support large snapshot payloads (> 64KB)
-- Issue: The snapshot_data column was created as TEXT which has a 65,535 byte limit
-- Fix: Change to LONGTEXT which supports up to 4GB
-- Impact: Allows storing large bonus pool data with 100+ consultants
-- ======================================================================================

START TRANSACTION;

-- Alter snapshot_data column from TEXT to LONGTEXT
ALTER TABLE immutable_snapshots
MODIFY COLUMN snapshot_data LONGTEXT NOT NULL COMMENT 'Complete JSON serialization of entity state';

-- Verify the change
SELECT
    COLUMN_NAME,
    COLUMN_TYPE,
    DATA_TYPE,
    CHARACTER_MAXIMUM_LENGTH
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'immutable_snapshots'
  AND COLUMN_NAME = 'snapshot_data';

COMMIT;

-- ======================================================================================
-- Migration Notes
-- ======================================================================================
--
-- ✅ TEXT limit: 65,535 bytes (~64KB)
-- ✅ LONGTEXT limit: 4,294,967,295 bytes (~4GB)
-- ✅ This change is backward compatible - all existing data remains valid
-- ✅ No data migration required - only schema change
-- ✅ Resolves: "Data too long for column 'snapshot_data' at row 1" error
--
-- Test Case:
-- - Bonus pool with 184 consultants ≈ 70KB JSON → Now supported
-- - Previous limit: ~150 consultants (61KB)
-- - New limit: Effectively unlimited for foreseeable business needs
--
-- ======================================================================================
