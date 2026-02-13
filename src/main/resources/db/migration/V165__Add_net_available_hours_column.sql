-- =============================================================================
-- Migration V165: Add net_available_hours column to bi_data_per_day
--
-- Purpose:
--   Phase 5.5 — Pre-compute net available hours to eliminate the repeated
--   8-column formula (gross - unavailable - vacation - sick - maternity -
--   non_payd_leave - paid_leave) that appears 6+ times across the codebase.
--
-- Changes:
--   1. Add net_available_hours column
--   2. Backfill from existing data
--   3. Recreate sp_recalculate_availability to populate the new column
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Add the column
-- ---------------------------------------------------------------------------
ALTER TABLE bi_data_per_day
    ADD COLUMN net_available_hours DECIMAL(7,4) NOT NULL DEFAULT 0
    AFTER paid_leave_hours;

-- ---------------------------------------------------------------------------
-- 2. Backfill from existing data
-- ---------------------------------------------------------------------------
UPDATE bi_data_per_day
SET net_available_hours = GREATEST(
    COALESCE(gross_available_hours, 0)
    - COALESCE(unavailable_hours, 0)
    - COALESCE(vacation_hours, 0)
    - COALESCE(sick_hours, 0)
    - COALESCE(maternity_leave_hours, 0)
    - COALESCE(non_payd_leave_hours, 0)
    - COALESCE(paid_leave_hours, 0), 0);

-- ---------------------------------------------------------------------------
-- 3. Recreate sp_recalculate_availability to populate net_available_hours
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
    ) ENGINE=MEMORY;

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

    INSERT INTO bi_data_per_day (
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

        -- Vacation hours
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            COALESCE(vac.hours, 0.0)
        ) AS vacation_hours,

        -- Sick hours
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            COALESCE(sick.hours, 0.0)
        ) AS sick_hours,

        -- Maternity leave hours
        LEAST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
            CASE WHEN tus.status_type = 'MATERNITY_LEAVE'
                 THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                 ELSE 0.0
            END + COALESCE(mat.hours, 0.0)
        ) AS maternity_leave_hours,

        -- Non-paid leave
        CASE WHEN tus.status_type = 'NON_PAY_LEAVE'
             THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
             ELSE 0.0
        END AS non_payd_leave_hours,

        -- Paid leave
        CASE WHEN tus.status_type = 'PAID_LEAVE'
             THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
             ELSE 0.0
        END AS paid_leave_hours,

        -- Net available hours (pre-computed)
        GREATEST(
            CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
            - CASE
                WHEN dd.is_company_shutdown = 1 THEN
                    CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                WHEN dd.day_of_week = 5 THEN LEAST(2.0, tus.allocation / 5.0)
                ELSE 0.0
              END
            - LEAST(
                CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
                COALESCE(vac.hours, 0.0))
            - LEAST(
                CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
                COALESCE(sick.hours, 0.0))
            - LEAST(
                CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END,
                CASE WHEN tus.status_type = 'MATERNITY_LEAVE'
                     THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                     ELSE 0.0
                END + COALESCE(mat.hours, 0.0))
            - CASE WHEN tus.status_type = 'NON_PAY_LEAVE'
                   THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                   ELSE 0.0
              END
            - CASE WHEN tus.status_type = 'PAID_LEAVE'
                   THEN CASE WHEN dd.is_weekend = 1 THEN 0.0 ELSE tus.allocation / 5.0 END
                   ELSE 0.0
              END
        , 0) AS net_available_hours,

        tus.is_tw_bonus_eligible,
        NOW()

    FROM tmp_user_status tus
    JOIN dim_date dd ON dd.date_key = tus.date_key
    LEFT JOIN (
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w
        WHERE w.taskuuid = 'f585f46f-19c1-4a3a-9ebd-1a4f21007282'
          AND w.registered >= p_start_date AND w.registered < p_end_date
        GROUP BY w.useruuid, w.registered
    ) vac ON vac.useruuid = tus.useruuid AND vac.registered = dd.date_key
    LEFT JOIN (
        SELECT w.useruuid, w.registered, SUM(w.workduration) AS hours
        FROM work w
        WHERE w.taskuuid = '02bf71c5-f588-46cf-9695-5864020eb1c4'
          AND w.registered >= p_start_date AND w.registered < p_end_date
        GROUP BY w.useruuid, w.registered
    ) sick ON sick.useruuid = tus.useruuid AND sick.registered = dd.date_key
    LEFT JOIN (
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
        net_available_hours    = VALUES(net_available_hours),
        is_tw_bonus_eligible   = VALUES(is_tw_bonus_eligible),
        last_update            = NOW();

    DROP TEMPORARY TABLE IF EXISTS tmp_user_status;
END //

DELIMITER ;
