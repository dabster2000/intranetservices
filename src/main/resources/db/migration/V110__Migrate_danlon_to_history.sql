-- Migration: V110 - Populate user_danlon_history from existing data
-- Purpose: Migrate existing Danløn numbers from user_ext_account to history table
-- Strategy: Use user hire date as active_date (backdated per requirement)
-- Date: 2025-11-17

-- Migrate existing Danløn numbers to history table
-- Uses user's earliest ACTIVE status date (hire date) as active_date
-- Falls back to 2020-01-01 if no hire date found (pre-system users)

INSERT INTO user_danlon_history (uuid, useruuid, active_date, danlon, created_date, created_by)
SELECT
    UUID() AS uuid,
    ua.useruuid,
    -- Use hire date normalized to first of month
    -- Fallback to 2020-01-01 for legacy users without hire date
    COALESCE(
        DATE_FORMAT(
            (SELECT MIN(us.statusdate)
             FROM userstatus us
             WHERE us.useruuid = ua.useruuid
               AND us.status = 'ACTIVE'
            ),
            '%Y-%m-01'
        ),
        '2020-01-01'
    ) AS active_date,
    ua.danlon,
    NOW() AS created_date,
    'system-migration' AS created_by
FROM user_ext_account ua
WHERE ua.danlon IS NOT NULL
  AND ua.danlon != ''
  AND TRIM(ua.danlon) != '';

-- Verification query (comment out in production, for testing only):
-- SELECT COUNT(*) as migrated_records FROM user_danlon_history WHERE created_by = 'system-migration';

-- IMPORTANT: We keep the denormalized 'danlon' column in user_ext_account for backward compatibility
-- This ensures zero downtime during deployment and allows gradual migration
-- The denormalized field will be removed in V111 migration after 3-6 months of testing
