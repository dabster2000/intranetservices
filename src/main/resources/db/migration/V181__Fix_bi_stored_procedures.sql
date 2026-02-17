-- =============================================================================
-- V181: Fix BI stored procedures and add data quality safeguards
--
-- Fixes discovered during investigation of utilization chart July 2025 dip:
--
-- 1. work_full view: CAST(cti.value AS UNSIGNED) fails on empty strings
--    Fix: Use NULLIF to handle empty strings before CAST
--
-- 2. sp_recalculate_budgets: Referenced non-existent column 'cti.item_type'
--    Fix: Use correct column name 'cti.name', add INSERT IGNORE, add NULLIF
--
-- 3. sp_recalculate_availability: Duplicate userstatus entries on same date
--    (e.g., company transfers) cause PRIMARY KEY violation in temp table
--    Fix: Use tiebreaker subquery (prefer ACTIVE status, then uuid DESC)
--    Also: Use ENGINE=InnoDB for temp table (MEMORY overflows on large ranges)
--
-- 4. sp_aggregate_monthly: company_work_per_month is now a VIEW, not a table
--    Fix: Convert to no-op since the view computes data live
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Fix 1: Clean up empty strings in contract_type_items.value
-- ---------------------------------------------------------------------------
UPDATE contract_type_items SET value = NULL WHERE value = '';

-- ---------------------------------------------------------------------------
-- Fix 2: Recreate work_full view with NULLIF to handle empty strings
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW work_full AS
SELECT
    w.uuid AS uuid,
    w.useruuid AS useruuid,
    w.workas AS workas,
    w.taskuuid AS taskuuid,
    w.workduration AS workduration,
    w.registered AS registered,
    w.billable AS billable,
    w.paid_out AS paid_out,
    IFNULL(ccc.rate, 0) AS rate,
    IFNULL(ccc.discount, 0) AS discount,
    ccc.name AS name,
    t.projectuuid AS projectuuid,
    p.clientuuid AS clientuuid,
    ccc.contractuuid AS contractuuid,
    ccc.companyuuid AS contract_company_uuid,
    (SELECT us_inner.companyuuid FROM userstatus us_inner
     WHERE us_inner.useruuid = w.useruuid AND us_inner.statusdate <= w.registered
     ORDER BY us_inner.statusdate DESC LIMIT 1) AS consultant_company_uuid,
    (SELECT us_inner.type FROM userstatus us_inner
     WHERE us_inner.useruuid = w.useruuid AND us_inner.statusdate <= w.registered
     ORDER BY us_inner.statusdate DESC LIMIT 1) AS type,
    w.comments AS comments,
    w.updated_at AS updated_at
FROM work w
LEFT JOIN task t ON w.taskuuid = t.uuid
LEFT JOIN project p ON t.projectuuid = p.uuid
LEFT JOIN (
    SELECT
        cc.rate AS rate,
        c.uuid AS contractuuid,
        cc.activefrom AS activefrom,
        cc.activeto AS activeto,
        c.companyuuid AS companyuuid,
        cp.projectuuid AS projectuuid,
        cc.useruuid AS useruuid,
        cc.name AS name,
        CAST(NULLIF(cti.value, '') AS UNSIGNED) AS discount
    FROM contract_project cp
    LEFT JOIN contract_consultants cc ON cp.contractuuid = cc.contractuuid
    LEFT JOIN contracts c ON cc.contractuuid = c.uuid
    LEFT JOIN contract_type_items cti ON c.uuid = cti.contractuuid
) ccc ON ccc.useruuid = IF(w.workas IS NOT NULL, w.workas, w.useruuid)
    AND p.uuid = ccc.projectuuid
    AND ccc.activefrom <= w.registered
    AND ccc.activeto >= w.registered
WHERE w.registered >= '2021-07-01';

-- ---------------------------------------------------------------------------
-- Fix 3: sp_recalculate_availability — handle duplicate statuses + InnoDB temp
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_recalculate_availability;

DELIMITER //
CREATE PROCEDURE sp_recalculate_availability(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    DROP TEMPORARY TABLE IF EXISTS tmp_user_status;

    CREATE TEMPORARY TABLE tmp_user_status (
        useruuid      VARCHAR(36) NOT NULL,
        date_key      DATE        NOT NULL,
        companyuuid   VARCHAR(36),
        consultant_type VARCHAR(20),
        status_type   VARCHAR(20),
        allocation    INT         NOT NULL DEFAULT 37,
        is_tw_bonus_eligible TINYINT NOT NULL DEFAULT 0,
        PRIMARY KEY (useruuid, date_key)
    ) ENGINE=InnoDB;

    -- Resolve to exactly ONE status per user per date.
    -- When multiple statuses exist on the same date (e.g., company transfer),
    -- prefer ACTIVE over other statuses, then use uuid DESC as final tiebreaker.
    INSERT INTO tmp_user_status (useruuid, date_key, companyuuid, consultant_type, status_type, allocation, is_tw_bonus_eligible)
    SELECT
        us.useruuid,
        dd.date_key,
        us.companyuuid,
        us.type AS consultant_type,
        us.status AS status_type,
        us.allocation,
        us.is_tw_bonus_eligible
    FROM dim_date dd
    CROSS JOIN (SELECT DISTINCT useruuid FROM userstatus) all_users
    JOIN userstatus us
        ON us.useruuid = all_users.useruuid
        AND us.uuid = (
            SELECT us2.uuid
            FROM userstatus us2
            WHERE us2.useruuid = all_users.useruuid
              AND us2.statusdate = (
                  SELECT MAX(us3.statusdate)
                  FROM userstatus us3
                  WHERE us3.useruuid = all_users.useruuid
                    AND us3.statusdate <= dd.date_key
              )
            ORDER BY
                CASE us2.status
                    WHEN 'ACTIVE' THEN 0
                    WHEN 'PREBOARDING' THEN 1
                    WHEN 'MATERNITY_LEAVE' THEN 2
                    WHEN 'PAID_LEAVE' THEN 3
                    WHEN 'NON_PAY_LEAVE' THEN 4
                    ELSE 5
                END,
                us2.uuid DESC
            LIMIT 1
        )
    WHERE dd.date_key >= p_start_date
      AND dd.date_key < p_end_date
      AND (p_user_uuid IS NULL OR all_users.useruuid = p_user_uuid);

    INSERT INTO fact_user_day (
        useruuid, document_date, year, month, day,
        companyuuid, consultant_type, status_type,
        gross_available_hours, unavailable_hours,
        vacation_hours, sick_hours, maternity_leave_hours,
        non_payd_leave_hours, paid_leave_hours,
        net_available_hours,
        is_tw_bonus_eligible, last_update
    )
    SELECT
        tus.useruuid,
        dd.date_key,
        dd.year,
        dd.month,
        dd.day,
        tus.companyuuid,
        tus.consultant_type,
        tus.status_type,
        CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END AS gross_available_hours,
        CASE
            WHEN dd.is_company_shutdown = 1 THEN
                CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
            WHEN dd.day_of_week = 5 THEN LEAST(2.0, tus.allocation / 5.0)
            ELSE 0.0
        END AS unavailable_hours,
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            COALESCE(vac.hours, 0.0)
        ) AS vacation_hours,
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            COALESCE(sick.hours, 0.0)
        ) AS sick_hours,
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            CASE WHEN tus.status_type = 'MATERNITY_LEAVE'
                 THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                 ELSE 0.0
            END + COALESCE(mat.hours, 0.0)
        ) AS maternity_leave_hours,
        CASE WHEN tus.status_type = 'NON_PAY_LEAVE'
             THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
             ELSE 0.0
        END AS non_payd_leave_hours,
        CASE WHEN tus.status_type = 'PAID_LEAVE'
             THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
             ELSE 0.0
        END AS paid_leave_hours,
        GREATEST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
            - CASE
                WHEN dd.is_company_shutdown = 1 THEN
                    CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                WHEN dd.day_of_week = 5 THEN LEAST(2.0, tus.allocation / 5.0)
                ELSE 0.0
              END
            - LEAST(CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END, COALESCE(vac.hours, 0.0))
            - LEAST(CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END, COALESCE(sick.hours, 0.0))
            - LEAST(CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
                CASE WHEN tus.status_type = 'MATERNITY_LEAVE'
                     THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                     ELSE 0.0 END + COALESCE(mat.hours, 0.0))
            - CASE WHEN tus.status_type = 'NON_PAY_LEAVE'
                   THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END ELSE 0.0 END
            - CASE WHEN tus.status_type = 'PAID_LEAVE'
                   THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END ELSE 0.0 END
        , 0) AS net_available_hours,
        tus.is_tw_bonus_eligible,
        NOW()
    FROM tmp_user_status tus
    JOIN dim_date dd ON dd.date_key = tus.date_key
    LEFT JOIN (
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w WHERE w.taskuuid = 'f585f46f-19c1-4a3a-9ebd-1a4f21007282'
          AND w.registered >= p_start_date AND w.registered < p_end_date
        GROUP BY w.useruuid, w.registered
    ) vac ON vac.useruuid = tus.useruuid AND vac.registered = dd.date_key
    LEFT JOIN (
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w WHERE w.taskuuid = '02bf71c5-f588-46cf-9695-5864020eb1c4'
          AND w.registered >= p_start_date AND w.registered < p_end_date
        GROUP BY w.useruuid, w.registered
    ) sick ON sick.useruuid = tus.useruuid AND sick.registered = dd.date_key
    LEFT JOIN (
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w WHERE w.taskuuid = 'da2f89fc-9aef-4029-8ac2-7486be60e9b9'
          AND w.registered >= p_start_date AND w.registered < p_end_date
        GROUP BY w.useruuid, w.registered
    ) mat ON mat.useruuid = tus.useruuid AND mat.registered = dd.date_key
    ON DUPLICATE KEY UPDATE
        companyuuid            = VALUES(companyuuid),
        consultant_type        = VALUES(consultant_type),
        status_type            = VALUES(status_type),
        gross_available_hours  = VALUES(gross_available_hours),
        unavailable_hours      = VALUES(unavailable_hours),
        vacation_hours         = VALUES(vacation_hours),
        sick_hours             = VALUES(sick_hours),
        maternity_leave_hours  = VALUES(maternity_leave_hours),
        non_payd_leave_hours   = VALUES(non_payd_leave_hours),
        paid_leave_hours       = VALUES(paid_leave_hours),
        net_available_hours    = VALUES(net_available_hours),
        is_tw_bonus_eligible   = VALUES(is_tw_bonus_eligible),
        last_update            = NOW();

    DROP TEMPORARY TABLE IF EXISTS tmp_user_status;
END //
DELIMITER ;

-- ---------------------------------------------------------------------------
-- Fix 4: sp_recalculate_budgets — correct column name + INSERT IGNORE + NULLIF
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_recalculate_budgets;

DELIMITER //
CREATE PROCEDURE sp_recalculate_budgets(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    DELETE FROM fact_budget_day
    WHERE document_date >= p_start_date
      AND document_date < p_end_date
      AND (p_user_uuid IS NULL OR useruuid = p_user_uuid);

    INSERT IGNORE INTO fact_budget_day (
        document_date, year, month, day,
        clientuuid, useruuid, companyuuid, contractuuid,
        budgetHours, budgetHoursWithNoAvailabilityAdjustment, rate
    )
    SELECT
        dd.date_key, dd.year, dd.month, dd.day,
        c.clientuuid, cc.useruuid, NULL, c.uuid AS contractuuid,
        cc.hours / 5.0 AS budgetHours,
        cc.hours / 5.0 AS budgetHoursRaw,
        cc.rate * (1 - COALESCE(CAST(NULLIF(cti.value, '') AS DECIMAL(10,4)), 0)) AS rate
    FROM contracts c
    JOIN contract_consultants cc ON cc.contractuuid = c.uuid
    JOIN dim_date dd ON dd.date_key >= cc.activefrom
        AND dd.date_key < COALESCE(cc.activeto, '2035-01-01')
        AND dd.date_key >= c.activefrom
        AND dd.date_key < COALESCE(c.activeto, '2035-01-01')
        AND dd.is_weekend = 0
    LEFT JOIN contract_type_items cti
        ON cti.contractuuid = c.uuid
        AND cti.name = 'DISCOUNT'
    WHERE dd.date_key >= p_start_date
      AND dd.date_key < p_end_date
      AND c.status IN ('BUDGET', 'TIME_AND_MATERIAL', 'FIXED_PRICE')
      AND (p_user_uuid IS NULL OR cc.useruuid = p_user_uuid);

    UPDATE fact_budget_day fbd
    JOIN (
        SELECT fbd2.useruuid, fbd2.document_date,
            SUM(fbd2.budgetHours) AS total_budget,
            COALESCE(fud.net_available_hours, 0) AS net_available
        FROM fact_budget_day fbd2
        LEFT JOIN fact_user_day fud
            ON fud.useruuid = fbd2.useruuid AND fud.document_date = fbd2.document_date
        WHERE fbd2.document_date >= p_start_date AND fbd2.document_date < p_end_date
          AND (p_user_uuid IS NULL OR fbd2.useruuid = p_user_uuid)
        GROUP BY fbd2.useruuid, fbd2.document_date
        HAVING total_budget > net_available AND net_available > 0
    ) overalloc ON fbd.useruuid = overalloc.useruuid AND fbd.document_date = overalloc.document_date
    SET fbd.budgetHours = fbd.budgetHours * (overalloc.net_available / overalloc.total_budget)
    WHERE fbd.document_date >= p_start_date AND fbd.document_date < p_end_date;

    UPDATE fact_budget_day fbd
    JOIN fact_user_day fud ON fud.useruuid = fbd.useruuid AND fud.document_date = fbd.document_date
    SET fbd.companyuuid = fud.companyuuid
    WHERE fbd.document_date >= p_start_date AND fbd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR fbd.useruuid = p_user_uuid);
END //
DELIMITER ;

-- ---------------------------------------------------------------------------
-- Fix 5: sp_aggregate_monthly — company_work_per_month is now a live VIEW
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_aggregate_monthly;

DELIMITER //
CREATE PROCEDURE sp_aggregate_monthly(
    IN p_start_date DATE,
    IN p_end_date   DATE
)
BEGIN
    -- No-op: company_work_per_month is now a live view computed from work_full.
    -- This procedure is kept for API compatibility with sp_nightly_bi_refresh
    -- and sp_incremental_bi_refresh.
    DO 0;
END //
DELIMITER ;
