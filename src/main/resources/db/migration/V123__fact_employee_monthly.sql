-- =============================================================================
-- Migration V123: Create fact_employee_monthly view
--
-- Purpose:
-- - Monthly headcount and movement snapshot by company and practice
-- - Enables voluntary attrition calculation: voluntary_leavers_12m / avg_headcount_12m
-- - Tracks joiners, leavers, and headcount movements
--
-- Grain: company_id × practice_id × month_key
--
-- Data Sources:
-- - userstatus: Employment status transitions (ACTIVE, TERMINATED, etc.)
-- - user: Practice/service line (primaryskilltype)
--
-- Limitations:
-- - TODO: Voluntary vs involuntary classification requires termination_reason field
--   Currently all leavers are counted as "unclassified" (voluntary_leavers_count = total)
--   Enhancement needed when HR system captures termination reason
--
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_employee_monthly` AS

WITH
    -- 1) Generate month calendar for historical analysis (36 months back + current)
    --    This provides sufficient history for 12-month rolling calculations
    month_calendar AS (
        SELECT
            DATE_FORMAT(
                    DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                             INTERVAL n MONTH),
                    '%Y%m'
            ) AS month_key,
            YEAR(
                    DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                             INTERVAL n MONTH)
            ) AS year_val,
            MONTH(
                    DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                             INTERVAL n MONTH)
            ) AS month_val,
            DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                     INTERVAL n MONTH) AS first_day,
            LAST_DAY(
                    DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                             INTERVAL n MONTH)
            ) AS last_day
        FROM (
                 SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
                 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
                 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
                 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
                 UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23
                 UNION ALL SELECT 24 UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27
                 UNION ALL SELECT 28 UNION ALL SELECT 29 UNION ALL SELECT 30 UNION ALL SELECT 31
                 UNION ALL SELECT 32 UNION ALL SELECT 33 UNION ALL SELECT 34 UNION ALL SELECT 35
             ) months
    ),

    -- 2) Get all users with their practice (service line)
    --    Filter to real employees (not SYSTEM accounts)
    user_practice AS (
        SELECT
            u.uuid AS user_id,
            COALESCE(u.primaryskilltype, 'UD') AS practice_id
        FROM user u
        WHERE u.type = 'USER'
    ),

    -- 3) Determine user status as of first day of each month
    --    Uses temporal pattern: latest status record with statusdate <= target_date
    status_at_month_start AS (
        SELECT
            mc.month_key,
            mc.first_day,
            us.useruuid AS user_id,
            us.status,
            us.type AS consultant_type,
            COALESCE(us.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id
        FROM month_calendar mc
                 CROSS JOIN (SELECT DISTINCT useruuid FROM userstatus) users
                 INNER JOIN userstatus us ON us.useruuid = users.useruuid
        WHERE us.statusdate = (
            SELECT MAX(us2.statusdate)
            FROM userstatus us2
            WHERE us2.useruuid = users.useruuid
              AND us2.statusdate <= mc.first_day
        )
    ),

    -- 4) Determine user status as of last day of each month
    status_at_month_end AS (
        SELECT
            mc.month_key,
            mc.last_day,
            us.useruuid AS user_id,
            us.status,
            us.type AS consultant_type,
            COALESCE(us.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id
        FROM month_calendar mc
                 CROSS JOIN (SELECT DISTINCT useruuid FROM userstatus) users
                 INNER JOIN userstatus us ON us.useruuid = users.useruuid
        WHERE us.statusdate = (
            SELECT MAX(us2.statusdate)
            FROM userstatus us2
            WHERE us2.useruuid = users.useruuid
              AND us2.statusdate <= mc.last_day
        )
    ),

    -- 5) Calculate headcount at START of each month by company and practice
    --    ACTIVE status only (excludes PREBOARDING, TERMINATED, leaves)
    headcount_start_by_company_practice AS (
        SELECT
            sms.month_key,
            sms.company_id,
            up.practice_id,
            COUNT(DISTINCT sms.user_id) AS headcount_start,
            COUNT(DISTINCT CASE
                               WHEN sms.consultant_type = 'CONSULTANT' THEN sms.user_id
                END) AS billable_headcount_start
        FROM status_at_month_start sms
                 INNER JOIN user_practice up ON sms.user_id = up.user_id
        WHERE sms.status = 'ACTIVE'
        GROUP BY sms.month_key, sms.company_id, up.practice_id
    ),

    -- 6) Calculate headcount at END of each month by company and practice
    headcount_end_by_company_practice AS (
        SELECT
            sme.month_key,
            sme.company_id,
            up.practice_id,
            COUNT(DISTINCT sme.user_id) AS headcount_end,
            COUNT(DISTINCT CASE
                               WHEN sme.consultant_type = 'CONSULTANT' THEN sme.user_id
                END) AS billable_headcount_end
        FROM status_at_month_end sme
                 INNER JOIN user_practice up ON sme.user_id = up.user_id
        WHERE sme.status = 'ACTIVE'
        GROUP BY sme.month_key, sme.company_id, up.practice_id
    ),

    -- 7) Count JOINERS: employees who became ACTIVE in each month
    --    A joiner is someone whose first ACTIVE status has statusdate in this month
    --    OR whose ACTIVE status follows a TERMINATED status (re-hire)
    joiners_by_company_practice AS (
        SELECT
            mc.month_key,
            COALESCE(us.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
            up.practice_id,
            COUNT(DISTINCT us.useruuid) AS joiners_count
        FROM month_calendar mc
                 INNER JOIN userstatus us
                            ON us.status = 'ACTIVE'
                                AND us.statusdate >= mc.first_day
                                AND us.statusdate <= mc.last_day
                 INNER JOIN user_practice up ON us.useruuid = up.user_id
        -- Only count as joiner if previous status was NOT ACTIVE
        -- (i.e., this is a new hire or re-hire, not return from leave)
        WHERE NOT EXISTS (
            SELECT 1 FROM userstatus us_prev
            WHERE us_prev.useruuid = us.useruuid
              AND us_prev.statusdate < us.statusdate
              AND us_prev.status = 'ACTIVE'
              -- Allow return from TERMINATED (re-hire)
              AND us_prev.statusdate = (
                SELECT MAX(us_prev2.statusdate)
                FROM userstatus us_prev2
                WHERE us_prev2.useruuid = us.useruuid
                  AND us_prev2.statusdate < us.statusdate
            )
        )
        GROUP BY mc.month_key, us.companyuuid, up.practice_id
    ),

    -- 8) Count LEAVERS: employees who became TERMINATED in each month
    --    NOTE: Currently no voluntary vs involuntary distinction available
    --    TODO: Enhance when termination_reason field is added to userstatus
    leavers_by_company_practice AS (
        SELECT
            mc.month_key,
            COALESCE(us.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
            up.practice_id,
            COUNT(DISTINCT us.useruuid) AS total_leavers_count,
            -- TODO: Replace with actual classification when termination_reason available
            -- For now, assume all departures are voluntary (conservative for attrition metric)
            COUNT(DISTINCT us.useruuid) AS voluntary_leavers_count,
            0 AS involuntary_leavers_count
        FROM month_calendar mc
                 INNER JOIN userstatus us
                            ON us.status = 'TERMINATED'
                                AND us.statusdate >= mc.first_day
                                AND us.statusdate <= mc.last_day
                 INNER JOIN user_practice up ON us.useruuid = up.user_id
        -- Only count terminations that follow an ACTIVE status
        -- (excludes preboarding cancellations)
        WHERE EXISTS (
            SELECT 1 FROM userstatus us_prev
            WHERE us_prev.useruuid = us.useruuid
              AND us_prev.statusdate < us.statusdate
              AND us_prev.status = 'ACTIVE'
        )
        GROUP BY mc.month_key, us.companyuuid, up.practice_id
    ),

    -- 9) Build company-practice-month key set (UNION of all dimensions)
    all_company_practice_months AS (
        SELECT DISTINCT month_key, company_id, practice_id FROM headcount_start_by_company_practice
        UNION
        SELECT DISTINCT month_key, company_id, practice_id FROM headcount_end_by_company_practice
        UNION
        SELECT DISTINCT month_key, company_id, practice_id FROM joiners_by_company_practice
        UNION
        SELECT DISTINCT month_key, company_id, practice_id FROM leavers_by_company_practice
    )

-- 10) Final SELECT: Combine all metrics
SELECT
    -- Surrogate key
    CONCAT(
            acpm.company_id, '-',
            acpm.practice_id, '-',
            acpm.month_key
    ) AS employee_month_id,

    -- Dimension keys
    acpm.company_id,
    acpm.practice_id,

    -- Time dimensions
    acpm.month_key,
    mc.year_val AS year,
    mc.month_val AS month_number,
    mc.first_day AS month_start_date,
    mc.last_day AS month_end_date,

    -- Headcount metrics
    COALESCE(hs.headcount_start, 0) AS headcount_start,
    COALESCE(he.headcount_end, 0) AS headcount_end,
    ROUND((COALESCE(hs.headcount_start, 0) + COALESCE(he.headcount_end, 0)) / 2.0, 2) AS average_headcount,

    -- Movement metrics
    COALESCE(j.joiners_count, 0) AS joiners_count,
    COALESCE(l.total_leavers_count, 0) AS total_leavers_count,
    COALESCE(l.voluntary_leavers_count, 0) AS voluntary_leavers_count,
    COALESCE(l.involuntary_leavers_count, 0) AS involuntary_leavers_count,

    -- Billable subset (consultants only)
    COALESCE(he.billable_headcount_end, 0) AS billable_headcount_end,

    -- Net change
    COALESCE(j.joiners_count, 0) - COALESCE(l.total_leavers_count, 0) AS net_headcount_change,

    -- Pre-calculated monthly attrition (for convenience, though 12m rolling is main use)
    CASE
        WHEN COALESCE(hs.headcount_start, 0) + COALESCE(he.headcount_end, 0) > 0
            THEN ROUND(
                COALESCE(l.voluntary_leavers_count, 0) * 100.0 /
                ((COALESCE(hs.headcount_start, 0) + COALESCE(he.headcount_end, 0)) / 2.0),
                2
                 )
        ELSE NULL
        END AS monthly_attrition_pct,

    -- Data source
    'USERSTATUS' AS data_source

FROM all_company_practice_months acpm
         INNER JOIN month_calendar mc ON acpm.month_key = mc.month_key
         LEFT JOIN headcount_start_by_company_practice hs
                   ON acpm.month_key = hs.month_key
                       AND acpm.company_id = hs.company_id
                       AND acpm.practice_id = hs.practice_id
         LEFT JOIN headcount_end_by_company_practice he
                   ON acpm.month_key = he.month_key
                       AND acpm.company_id = he.company_id
                       AND acpm.practice_id = he.practice_id
         LEFT JOIN joiners_by_company_practice j
                   ON acpm.month_key = j.month_key
                       AND acpm.company_id = j.company_id
                       AND acpm.practice_id = j.practice_id
         LEFT JOIN leavers_by_company_practice l
                   ON acpm.month_key = l.month_key
                       AND acpm.company_id = l.company_id
                       AND acpm.practice_id = l.practice_id

ORDER BY acpm.month_key DESC, acpm.company_id, acpm.practice_id;