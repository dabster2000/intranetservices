-- ============================================================================
-- V329: Retire bi_availability_per_day
-- ============================================================================
-- Purpose
--   The `bi_availability_per_day` table was the source of capacity data for the
--   `fact_staffing_forecast_week` view, but the Java code that populated it
--   (AvailabilityCalculatingExecutor + EmployeeAvailabilityPerDayAggregate.persist)
--   has had zero callers for some time. The active Java path now writes
--   capacity data into fact_user_day via BiDataPerDayRepository.insertOrUpdateData.
--
--   Schema-wise, fact_user_day has the same column set as bi_availability_per_day
--   (gross_available_hours, unavailable_hours, vacation_hours, sick_hours,
--   maternity_leave_hours, non_payd_leave_hours, paid_leave_hours,
--   consultant_type, status_type, useruuid, document_date, companyuuid),
--   so it is a drop-in replacement.
--
-- Changes
--   1. Recreate `fact_staffing_forecast_week` to source capacity from
--      fact_user_day instead of bi_availability_per_day. Definition is
--      otherwise identical.
--   2. Drop the FK fk_bi_availability_per_day_user (added in production via
--      sql/add-user-foreign-keys.sql; may not exist on staging yet).
--   3. Drop the table bi_availability_per_day.
--
-- Idempotency
--   - CREATE OR REPLACE VIEW for the view.
--   - DROP FOREIGN KEY IF EXISTS / DROP TABLE IF EXISTS for the FK and table.
--
-- Java cleanup (in same PR)
--   - Removed: dk.trustworks.intranet.bi.services.AvailabilityCalculatingExecutor
--   - Removed: dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate
--   - Refactored: UserAvailabilityCalculatorService now writes directly to
--     bi_data_per_day via BiDataPerDayRepository (no transient holder entity).
-- ============================================================================

-- Step 1: Replace the view to read capacity from fact_user_day
CREATE OR REPLACE VIEW fact_staffing_forecast_week AS
WITH
week_calendar AS (
    SELECT
        CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK         AS week_start_date,
        CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n * 7 + 6 DAY  AS week_end_date,
        YEARWEEK(CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK, 3) AS week_key,
        YEAR(CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK)        AS year_val,
        WEEK(CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK, 3)     AS iso_week_number
    FROM (
        SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
        UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
        UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
    ) weeks
),
user_company_mapping AS (
    SELECT DISTINCT
        b.useruuid AS user_id,
        b.document_date,
        COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id
    FROM bi_budget_per_day b
    WHERE b.budgetHours > 0
      AND b.document_date >= CURDATE()
      AND b.document_date <  CURDATE() + INTERVAL 12 WEEK
),
forecast_billable_aggregated AS (
    SELECT
        b.useruuid AS user_id,
        COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
        YEARWEEK(b.document_date, 3) AS week_key,
        SUM(b.budgetHours)                                  AS forecast_billable_hours,
        SUM(b.budgetHoursWithNoAvailabilityAdjustment)      AS raw_billable_hours,
        SUM(b.budgetHours * b.rate)                         AS forecast_revenue_dkk,
        COUNT(DISTINCT b.contractuuid)                      AS contract_count
    FROM bi_budget_per_day b
    WHERE b.budgetHours > 0
      AND b.document_date >= CURDATE()
      AND b.document_date <  CURDATE() + INTERVAL 12 WEEK
    GROUP BY b.useruuid, COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691'), YEARWEEK(b.document_date, 3)
),
capacity_by_user_company_week AS (
    SELECT
        a.useruuid                          AS user_id,
        ucm.company_id                      AS company_id,
        YEARWEEK(a.document_date, 3)        AS week_key,
        SUM(a.gross_available_hours)        AS gross_capacity_hours,
        SUM(a.vacation_hours)               AS planned_vacation_hours,
        SUM(a.sick_hours)                   AS planned_sick_hours,
        SUM(a.maternity_leave_hours)        AS planned_maternity_hours,
        SUM(a.non_payd_leave_hours)         AS planned_unpaid_leave_hours,
        SUM(a.paid_leave_hours)             AS planned_paid_leave_hours,
        SUM(a.unavailable_hours)            AS total_unavailable_hours,
        SUM(a.gross_available_hours) - SUM(a.unavailable_hours) AS capacity_hours,
        MAX(a.consultant_type)              AS consultant_type,
        MAX(a.status_type)                  AS status_type
    FROM fact_user_day a
    JOIN user_company_mapping ucm
        ON a.useruuid = ucm.user_id
       AND a.document_date = ucm.document_date
    WHERE a.status_type NOT IN ('TERMINATED', 'PREBOARDING')
      AND a.consultant_type IN ('CONSULTANT', 'STUDENT', 'EXTERNAL')
      AND a.document_date >= CURDATE()
      AND a.document_date <  CURDATE() + INTERVAL 12 WEEK
    GROUP BY a.useruuid, ucm.company_id, YEARWEEK(a.document_date, 3)
),
aggregated_user_company_week AS (
    SELECT
        f.user_id, f.company_id, f.week_key,
        f.forecast_billable_hours, f.raw_billable_hours, f.forecast_revenue_dkk, f.contract_count,
        COALESCE(c.capacity_hours, 0)         AS capacity_hours,
        COALESCE(c.gross_capacity_hours, 0)   AS gross_capacity_hours,
        COALESCE(c.total_unavailable_hours, 0) AS total_unavailable_hours,
        COALESCE(c.planned_vacation_hours, 0) AS planned_vacation_hours,
        c.consultant_type, c.status_type,
        'BACKLOG' AS source_type
    FROM forecast_billable_aggregated f
    LEFT JOIN capacity_by_user_company_week c
        ON f.user_id = c.user_id
       AND f.company_id = c.company_id
       AND f.week_key = c.week_key
    UNION ALL
    SELECT
        c.user_id, c.company_id, c.week_key,
        0, 0, 0, 0,
        c.capacity_hours, c.gross_capacity_hours, c.total_unavailable_hours, c.planned_vacation_hours,
        c.consultant_type, c.status_type,
        'CAPACITY_ONLY' AS source_type
    FROM capacity_by_user_company_week c
    LEFT JOIN forecast_billable_aggregated f
        ON c.user_id = f.user_id
       AND c.company_id = f.company_id
       AND c.week_key = f.week_key
    WHERE f.user_id IS NULL
),
project_detail AS (
    SELECT
        b.useruuid AS user_id,
        COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
        YEARWEEK(b.document_date, 3) AS week_key,
        b.contractuuid AS project_id,
        b.clientuuid   AS client_id,
        SUM(b.budgetHours) AS project_billable_hours
    FROM bi_budget_per_day b
    WHERE b.budgetHours > 0
      AND b.document_date >= CURDATE()
      AND b.document_date <  CURDATE() + INTERVAL 12 WEEK
    GROUP BY b.useruuid, COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691'),
             YEARWEEK(b.document_date, 3), b.contractuuid, b.clientuuid
),
final_enriched AS (
    SELECT
        agg.user_id, agg.company_id, agg.week_key,
        agg.forecast_billable_hours, agg.raw_billable_hours, agg.forecast_revenue_dkk, agg.contract_count,
        agg.capacity_hours, agg.gross_capacity_hours, agg.total_unavailable_hours, agg.planned_vacation_hours,
        agg.consultant_type, agg.status_type, agg.source_type,
        pd.project_id, pd.client_id, pd.project_billable_hours,
        COALESCE(u.practice, 'UD') AS practice_id,
        wc.week_start_date, wc.week_end_date, wc.year_val, wc.iso_week_number
    FROM aggregated_user_company_week agg
    LEFT JOIN project_detail pd
        ON agg.user_id = pd.user_id
       AND agg.company_id = pd.company_id
       AND agg.week_key = pd.week_key
       AND agg.source_type = 'BACKLOG'
    LEFT JOIN user u ON agg.user_id = u.uuid
    LEFT JOIN week_calendar wc ON agg.week_key = wc.week_key
)
SELECT
    CONCAT(fe.user_id, '-', fe.company_id, '-', fe.week_key,
           COALESCE(CONCAT('-', fe.project_id), '')) AS forecast_staffing_id,
    fe.user_id, fe.company_id, fe.practice_id, fe.project_id, fe.client_id,
    fe.week_key, fe.week_start_date, fe.week_end_date,
    fe.year_val AS year, fe.iso_week_number,
    COALESCE(fe.project_billable_hours, fe.forecast_billable_hours) AS forecast_billable_hours,
    GREATEST(0, fe.capacity_hours - fe.forecast_billable_hours)     AS forecast_nonbillable_hours,
    COALESCE(fe.project_billable_hours, fe.forecast_billable_hours) AS forecast_total_hours,
    fe.capacity_hours, fe.gross_capacity_hours,
    fe.total_unavailable_hours AS planned_absence_hours,
    fe.planned_vacation_hours,
    fe.source_type,
    CASE WHEN fe.source_type = 'BACKLOG' THEN 100.0 ELSE NULL END AS probability_pct,
    'BI_BUDGET' AS data_source,
    CASE WHEN fe.capacity_hours > 0
         THEN ROUND(fe.forecast_billable_hours / fe.capacity_hours * 100, 2)
         ELSE NULL END AS forecast_utilization_pct
FROM final_enriched fe
WHERE fe.week_start_date IS NOT NULL
ORDER BY fe.week_start_date, fe.user_id, fe.company_id, fe.project_id;


-- Step 2: Drop the FK constraint that points at user(uuid)
-- (Added by sql/add-user-foreign-keys.sql; may not be present on every env)
SET @ddl := IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE()
       AND CONSTRAINT_NAME   = 'fk_bi_availability_per_day_user') > 0,
    'ALTER TABLE `bi_availability_per_day` DROP FOREIGN KEY `fk_bi_availability_per_day_user`',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;


-- Step 3: Drop the abandoned table
DROP TABLE IF EXISTS bi_availability_per_day;
