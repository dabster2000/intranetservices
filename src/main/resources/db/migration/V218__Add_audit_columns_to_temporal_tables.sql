-- =============================================================================
-- Migration V218: Add audit columns to temporal tables
--
-- Purpose:
--   Adds the standard 4-column audit trail (created_at, updated_at, created_by,
--   modified_by) to 8 temporal tables that currently lack them.
--   Follows the exact same pattern established by V88.
--
-- Pattern (from V88):
--   1. Add all 4 columns as NULL (no constraints yet)
--   2. Populate existing rows with default values
--   3. Make created_at, updated_at, created_by NOT NULL
--   4. modified_by stays NULL (not all records have been modified yet)
--
-- Tables updated (8 total):
--   1. userstatus
--   2. salary
--   3. user_bank_info
--   4. user_career_level
--   5. user_pension
--   6. salary_supplement
--   7. salary_lump_sum
--   8. user_contactinfo  (V216 added temporal columns; audit columns added here)
--
-- Audit column semantics:
--   created_at   DATETIME(6)   — microsecond precision, UTC storage
--   updated_at   DATETIME(6)   — microsecond precision, UTC storage
--   created_by   VARCHAR(255)  — user UUID from X-Requested-By header (or 'system')
--   modified_by  VARCHAR(255)  — nullable; NULL on records never modified
--
-- Rollback strategy:
--   For each table, run:
--     ALTER TABLE <table> DROP COLUMN created_at, DROP COLUMN updated_at,
--                         DROP COLUMN created_by, DROP COLUMN modified_by;
--   No data is lost (audit columns contain only derived/defaulted values).
--
-- Impact assessment:
--   Quarkus entities for all 8 tables must add the 4 audit fields.
--   Panache repositories should set created_at/updated_at/created_by on INSERT,
--   and updated_at/modified_by on UPDATE.
-- =============================================================================


-- =============================================================================
-- 1. userstatus
-- =============================================================================

ALTER TABLE userstatus
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE userstatus
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE userstatus
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;


-- =============================================================================
-- 2. salary
-- =============================================================================

ALTER TABLE salary
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE salary
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE salary
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;


-- =============================================================================
-- 3. user_bank_info
-- =============================================================================

ALTER TABLE user_bank_info
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE user_bank_info
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE user_bank_info
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;


-- =============================================================================
-- 4. user_career_level
-- =============================================================================

ALTER TABLE user_career_level
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE user_career_level
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE user_career_level
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;


-- =============================================================================
-- 5. user_pension
-- =============================================================================

ALTER TABLE user_pension
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE user_pension
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE user_pension
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;


-- =============================================================================
-- 6. salary_supplement
-- =============================================================================

ALTER TABLE salary_supplement
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE salary_supplement
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE salary_supplement
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;


-- =============================================================================
-- 7. salary_lump_sum
-- =============================================================================

ALTER TABLE salary_lump_sum
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE salary_lump_sum
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE salary_lump_sum
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;


-- =============================================================================
-- 8. user_contactinfo
--    (V216 added active_date and slackusername; audit columns added here)
-- =============================================================================

ALTER TABLE user_contactinfo
    ADD COLUMN created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE user_contactinfo
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE user_contactinfo
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;
