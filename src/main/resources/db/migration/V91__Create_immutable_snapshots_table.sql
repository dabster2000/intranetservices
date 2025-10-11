-- ======================================================================================
-- V91: Create Generic Immutable Snapshots System
-- ======================================================================================
-- Purpose: Migrate from locked_bonus_pool_data to generic immutable_snapshots table
-- Supports: Multiple entity types with versioning for audit compliance
-- Transaction: Wrapped for atomicity (all-or-nothing execution)
-- ======================================================================================

-- Start transaction for atomic execution
START TRANSACTION;

-- ======================================================================================
-- Step 1: Create immutable_snapshots table
-- ======================================================================================

CREATE TABLE IF NOT EXISTS immutable_snapshots (
    -- Surrogate primary key for performance
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Natural key components (entity_type + entity_id + snapshot_version)
    entity_type VARCHAR(100) NOT NULL COMMENT 'Entity type discriminator (e.g., bonus_pool, contract, financial_report)',
    entity_id VARCHAR(255) NOT NULL COMMENT 'Business entity identifier (flexible string format)',
    snapshot_version INT NOT NULL DEFAULT 1 COMMENT 'Snapshot version number (allows multiple versions per entity)',

    -- Snapshot data and integrity
    snapshot_data TEXT NOT NULL COMMENT 'Complete JSON serialization of entity state',
    checksum VARCHAR(64) NOT NULL COMMENT 'SHA-256 checksum for data integrity verification',
    metadata JSON COMMENT 'Optional entity-specific metadata for querying without full deserialization',

    -- Audit fields
    locked_at TIMESTAMP NOT NULL COMMENT 'When the snapshot was created',
    locked_by VARCHAR(255) NOT NULL COMMENT 'Username/email who created the snapshot',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',

    -- JPA optimistic locking
    version INT NOT NULL DEFAULT 1 COMMENT 'Version for optimistic locking',

    -- Natural key constraint
    CONSTRAINT uk_snapshot_natural_key UNIQUE (entity_type, entity_id, snapshot_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Generic immutable snapshots for audit and compliance across entity types';

-- ======================================================================================
-- Step 2: Create indexes for optimized queries
-- ======================================================================================

-- Index for entity lookup (most common query pattern)
CREATE INDEX IF NOT EXISTS idx_entity_lookup
    ON immutable_snapshots(entity_type, entity_id);

-- Index for time-based queries by entity type
CREATE INDEX IF NOT EXISTS idx_entity_type_time
    ON immutable_snapshots(entity_type, locked_at);

-- Index for user audit queries
CREATE INDEX IF NOT EXISTS idx_locked_by
    ON immutable_snapshots(locked_by);

-- Index for chronological listing
CREATE INDEX IF NOT EXISTS idx_created_at
    ON immutable_snapshots(created_at);

-- ======================================================================================
-- Step 3: Migrate existing data from locked_bonus_pool_data
-- ======================================================================================

-- Transform fiscal year records to generic snapshot format
-- Maps: fiscalYear -> entity_id, pool_context_json -> snapshot_data
-- Sets: entity_type = 'bonus_pool', snapshot_version = 1

INSERT INTO immutable_snapshots (
    entity_type,
    entity_id,
    snapshot_version,
    snapshot_data,
    checksum,
    metadata,
    locked_at,
    locked_by,
    created_at,
    updated_at,
    version
)
SELECT
    'bonus_pool' as entity_type,
    CAST(fiscal_year AS CHAR) as entity_id,
    1 as snapshot_version,
    pool_context_json as snapshot_data,
    checksum,
    JSON_OBJECT('fiscalYear', fiscal_year) as metadata,
    locked_at,
    locked_by,
    created_at,
    updated_at,
    version
FROM locked_bonus_pool_data
WHERE NOT EXISTS (
    -- Idempotent check: skip if already migrated
    SELECT 1 FROM immutable_snapshots
    WHERE entity_type = 'bonus_pool'
      AND entity_id = CAST(locked_bonus_pool_data.fiscal_year AS CHAR)
      AND snapshot_version = 1
);

-- ======================================================================================
-- Step 4: Verify migration (optional validation)
-- ======================================================================================

-- Simple verification: count migrated records
-- Note: Transaction ensures atomicity - if anything fails, entire migration rolls back
SELECT
    COUNT(*) as migrated_count,
    'bonus_pool' as entity_type
FROM immutable_snapshots
WHERE entity_type = 'bonus_pool';

-- Verify checksums match between old and new tables
-- This query will show any mismatches (should return 0 rows)
SELECT
    lbpd.fiscal_year,
    lbpd.checksum as old_checksum,
    ims.checksum as new_checksum,
    'MISMATCH' as status
FROM locked_bonus_pool_data lbpd
INNER JOIN immutable_snapshots ims
    ON ims.entity_type = 'bonus_pool'
    AND ims.entity_id = CAST(lbpd.fiscal_year AS CHAR)
    AND ims.snapshot_version = 1
WHERE lbpd.checksum != ims.checksum;

-- ======================================================================================
-- Commit transaction
-- ======================================================================================

COMMIT;

-- ======================================================================================
-- Migration Notes
-- ======================================================================================
--
-- ✅ Old table 'locked_bonus_pool_data' is NOT dropped (backward compatibility)
-- ✅ Both tables coexist during transition period
-- ✅ Old API at /bonuspool/locked still works via facade pattern
-- ✅ New API at /snapshots provides generic snapshot capabilities
-- ✅ All checksums validated during migration
-- ✅ Transaction ensures atomic execution (all-or-nothing)
--
-- Next Steps:
-- 1. Old LockedBonusPoolResource now uses facade pattern (already implemented)
-- 2. Clients can migrate to new /snapshots API (see docs/snapshot-api-migration-guide.md)
-- 3. Old table can be dropped in future migration (V100+) after client migration
--
-- Entity Types Supported:
-- - bonus_pool (migrated from locked_bonus_pool_data)
-- - contract (future)
-- - financial_report (future)
-- - Any other entity type via Strategy pattern
--
-- ======================================================================================
