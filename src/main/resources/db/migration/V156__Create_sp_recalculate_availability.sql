-- =============================================================================
-- Migration V156: Create sp_recalculate_availability
--
-- Purpose:
--   Set-based availability calculation that replaces the Java
--   UserAvailabilityCalculatorService row-by-row logic.
--   Populates bi_data_per_day with availability columns for all users
--   across a date range using a single INSERT...ON DUPLICATE KEY UPDATE.
--
-- Parameters:
--   p_start_date  - Start of recalculation window (inclusive)
--   p_end_date    - End of recalculation window (exclusive)
--   p_user_uuid   - Specific user UUID, or NULL for all users
--
-- Business rules replicated:
--   - Gross available = allocation / 5 (0 on weekends)
--   - Friday deduction: 2 hours (capped at gross)
--   - First Thu/Fri of October: full day company shutdown
--   - Vacation, sick, maternity from work entries (task UUIDs)
--   - Non-paid/paid leave from userstatus
--   - Leave hours capped at gross_available
--   - Temporal userstatus: most recent statusdate <= date
--
-- Task UUIDs (from WorkService constants):
--   VACATION:        f585f46f-19c1-4a3a-9ebd-1a4f21007282
--   SICKNESS:        02bf71c5-f588-46cf-9695-5864020eb1c4
--   MATERNITY_LEAVE: da2f89fc-9aef-4029-8ac2-7486be60e9b9
-- =============================================================================

DELIMITER //

CREATE PROCEDURE sp_recalculate_availability(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    -- Use a temporary table for the resolved userstatus per user per date.
    -- This avoids the correlated subquery problem in MariaDB.
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
    ) ENGINE=MEMORY;

    -- Resolve the active userstatus for each user on each date in the range.
    -- For each (user, date), we want the userstatus row with the maximum
    -- statusdate that is <= date_key.
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
        AND us.statusdate = (
            SELECT MAX(us2.statusdate)
            FROM userstatus us2
            WHERE us2.useruuid = all_users.useruuid
              AND us2.statusdate <= dd.date_key
        )
    WHERE dd.date_key >= p_start_date
      AND dd.date_key < p_end_date
      AND (p_user_uuid IS NULL OR all_users.useruuid = p_user_uuid);

    -- Now compute availability and upsert into bi_data_per_day.
    INSERT INTO bi_data_per_day (
        useruuid, document_date, year, month, day,
        companyuuid, consultant_type, status_type,
        gross_available_hours, unavailable_hours,
        vacation_hours, sick_hours, maternity_leave_hours,
        non_payd_leave_hours, paid_leave_hours,
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

        -- Gross available hours
        CASE WHEN dd.is_weekend = 1 THEN 0.0
             ELSE tus.allocation / 5.0
        END AS gross_available_hours,

        -- Unavailable hours (Friday deduction + company shutdown)
        CASE
            WHEN dd.is_company_shutdown = 1 THEN
                CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE LEAST(tus.allocation / 5.0, tus.allocation / 5.0) END
            WHEN dd.day_of_week = 5 THEN LEAST(2.0, tus.allocation / 5.0)
            ELSE 0.0
        END AS unavailable_hours,

        -- Vacation hours (from work entries with VACATION task)
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            COALESCE(vac.hours, 0.0)
        ) AS vacation_hours,

        -- Sick hours (from work entries with SICKNESS task)
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            COALESCE(sick.hours, 0.0)
        ) AS sick_hours,

        -- Maternity leave hours (from status OR work entries)
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            CASE WHEN tus.status_type = 'MATERNITY_LEAVE'
                 THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                 ELSE 0.0
            END + COALESCE(mat.hours, 0.0)
        ) AS maternity_leave_hours,

        -- Non-paid leave (from status)
        CASE WHEN tus.status_type = 'NON_PAY_LEAVE'
             THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
             ELSE 0.0
        END AS non_payd_leave_hours,

        -- Paid leave (from status)
        CASE WHEN tus.status_type = 'PAID_LEAVE'
             THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
             ELSE 0.0
        END AS paid_leave_hours,

        -- Bonus eligible
        tus.is_tw_bonus_eligible,
        NOW()

    FROM tmp_user_status tus
    JOIN dim_date dd ON dd.date_key = tus.date_key
    LEFT JOIN (
        -- Vacation hours per user per day
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w
        WHERE w.taskuuid = 'f585f46f-19c1-4a3a-9ebd-1a4f21007282'
          AND w.registered >= p_start_date AND w.registered < p_end_date
        GROUP BY w.useruuid, w.registered
    ) vac ON vac.useruuid = tus.useruuid AND vac.registered = dd.date_key
    LEFT JOIN (
        -- Sick hours per user per day
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w
        WHERE w.taskuuid = '02bf71c5-f588-46cf-9695-5864020eb1c4'
          AND w.registered >= p_start_date AND w.registered < p_end_date
        GROUP BY w.useruuid, w.registered
    ) sick ON sick.useruuid = tus.useruuid AND sick.registered = dd.date_key
    LEFT JOIN (
        -- Maternity leave hours from work entries per user per day
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w
        WHERE w.taskuuid = 'da2f89fc-9aef-4029-8ac2-7486be60e9b9'
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
        is_tw_bonus_eligible   = VALUES(is_tw_bonus_eligible),
        last_update            = NOW();

    DROP TEMPORARY TABLE IF EXISTS tmp_user_status;
END //

DELIMITER ;
