-- Migration: V111 - Remove denormalized danlon column from user_ext_account
-- Purpose: Complete migration to user_danlon_history temporal pattern
-- IMPORTANT: FUTURE deployment - only deploy after 3-6 months of production testing
-- Prerequisites: V109 and V110 must be stable in production, all code migrated to UserDanlonHistoryService
-- Date: 2025-11-17

-- **CRITICAL: This migration is NOT for immediate deployment**
-- Deploy this migration ONLY after:
-- 1. V109 and V110 have been stable in production for 3-6 months
-- 2. All code has been verified to use UserDanlonHistoryService instead of UserAccount.getDanlon()
-- 3. Backup verification confirms user_danlon_history has complete data
-- 4. Manual testing confirms UI and reports work correctly with history-based queries

-- Pre-migration verification query (run manually before deploying):
-- Verify all danlon numbers exist in history table with correct active dates
/*
SELECT
    COUNT(DISTINCT ua.useruuid) AS users_with_danlon,
    COUNT(DISTINCT h.useruuid) AS users_in_history,
    COUNT(DISTINCT ua.useruuid) - COUNT(DISTINCT h.useruuid) AS missing_users
FROM user_ext_account ua
LEFT JOIN user_danlon_history h ON ua.useruuid = h.useruuid
WHERE ua.danlon IS NOT NULL
  AND ua.danlon != ''
  AND TRIM(ua.danlon) != '';

-- Expected result: missing_users should be 0
-- If missing_users > 0, DO NOT deploy this migration!
*/

-- Drop the denormalized danlon column
-- This completes the migration to temporal history pattern
ALTER TABLE user_ext_account
    DROP COLUMN danlon;

-- Post-migration verification (comment out in production):
-- Verify column no longer exists
-- SHOW COLUMNS FROM user_ext_account LIKE 'danlon';
-- Expected: Empty result set

-- ROLLBACK PLAN (if needed):
-- If issues are discovered after deployment, rollback requires:
-- 1. ALTER TABLE user_ext_account ADD COLUMN danlon VARCHAR(36) NULL AFTER economics;
-- 2. UPDATE user_ext_account ua
--    SET ua.danlon = (
--        SELECT h.danlon
--        FROM user_danlon_history h
--        WHERE h.useruuid = ua.useruuid
--        ORDER BY h.active_date DESC
--        LIMIT 1
--    );
-- 3. Revert code changes to restore UserAccount.getDanlon() usage
