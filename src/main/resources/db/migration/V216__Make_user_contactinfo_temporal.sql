-- =============================================================================
-- Migration V216: Make user_contactinfo temporal
--
-- Purpose:
--   Extends user_contactinfo to support temporal (point-in-time) records,
--   enabling the storage of historical contact information per user.
--   Also migrates slackusername from the user table to user_contactinfo so
--   that all contact channel data lives in one temporal table.
--
-- Changes:
--   1. ADD COLUMN active_date DATE NOT NULL DEFAULT '2020-01-01'
--      The date from which this contact info record becomes the active version.
--      Default '2020-01-01' covers all existing records (treating them as
--      valid from a common baseline date).
--
--   2. ADD COLUMN slackusername VARCHAR(100)
--      Migrates from user.slackusername (which remains on the user table for
--      now; user.slackusername is deprecated and will be dropped in a future
--      migration once all consumers are updated).
--
--   3. CREATE INDEX idx_uci_user_date ON user_contactinfo(useruuid, active_date DESC)
--      Supports efficient point-in-time lookup pattern:
--        SELECT * FROM user_contactinfo
--        WHERE useruuid = ?
--          AND active_date <= ?
--        ORDER BY active_date DESC
--        LIMIT 1
--
--   4. Populate slackusername from user table for all existing records
--
-- Grain after migration:
--   (useruuid, active_date) — one record per user per effective date.
--   The most recent record where active_date <= query_date is the active one.
--
-- Rollback strategy:
--   DROP INDEX idx_uci_user_date ON user_contactinfo;
--   ALTER TABLE user_contactinfo DROP COLUMN slackusername;
--   ALTER TABLE user_contactinfo DROP COLUMN active_date;
--   (No data is lost — user.slackusername is not dropped in this migration)
--
-- Impact assessment:
--   Quarkus entities: UserContactInfo.java — add activeDate field, slackUsername field
--   Repositories: Add point-in-time lookup method (findLatestByUserAndDate)
-- =============================================================================

-- Step 1: Add active_date column with default covering all existing records
ALTER TABLE user_contactinfo
    ADD COLUMN active_date DATE NOT NULL DEFAULT '2020-01-01'
        COMMENT 'Effective date of this contact info record (temporal versioning)';

-- Step 2: Add slackusername column
ALTER TABLE user_contactinfo
    ADD COLUMN slackusername VARCHAR(100) NULL
        COMMENT 'Slack username (migrated from user.slackusername)';

-- Step 3: Populate slackusername from user table
--   Each existing user_contactinfo row represents the current state for that user.
--   We copy the user's current slackusername into the baseline record.
UPDATE user_contactinfo uc
JOIN user u ON uc.useruuid = u.uuid
SET uc.slackusername = u.slackusername
WHERE u.slackusername IS NOT NULL
  AND u.slackusername != '';

-- Step 4: Create temporal lookup index
--   DESC on active_date enables MariaDB to efficiently find the most recent
--   record for a given user on or before a target date.
CREATE INDEX idx_uci_user_date ON user_contactinfo(useruuid, active_date DESC);
