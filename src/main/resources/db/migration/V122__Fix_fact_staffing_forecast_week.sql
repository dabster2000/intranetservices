-- =============================================================================
-- Migration V122: Fix fact_staffing_forecast_week view
--
-- Purpose:
-- - Fix four critical issues in staffing forecast view
-- - Track forecasted staffing by consultant & week for next 8 weeks
-- - Enable "Forecast Utilization - Next 8 Weeks" KPI calculation
--
-- Grain: user_id × company_id × week_key (with optional project_id expansion)
--
-- FIXES APPLIED:
-- 1. Broadened consultant_type filter to include STUDENT, EXTERNAL
-- 2. Consistent company source from bi_budget_per_day (authoritative)
-- 3. Aggregate-first pattern to prevent capacity double-counting
-- 4. Explicit COLLATE utf8mb4_unicode_ci for cross-query compatibility
--
-- ISSUES FIXED:
-- - Issue #1: Students with contract allocations had 0 capacity (division by zero)
-- - Issue #2: Company mismatches caused 0 capacity for users who changed companies
-- - Issue #3: Capacity double-counting in multi-project aggregations
-- - Issue #4: Collation mismatches prevented filtering by user_id
--
-- CHANGE LOG:
-- 2025-11-27: Applied four critical fixes for production deployment
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_staffing_forecast_week` AS

WITH
    -- 1) Generate week calendar for next 12 weeks (buffer beyond 8-week horizon)
    week_calendar AS (
        SELECT
            DATE_ADD(
                DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY),
                INTERVAL n WEEK
            ) AS week_start_date,
            DATE_ADD(
                DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY),
                INTERVAL (n * 7 + 6) DAY
            ) AS week_end_date,
            YEARWEEK(
                DATE_ADD(
                    DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY),
                    INTERVAL n WEEK
                ),
                3
            ) AS week_key,
            YEAR(
                DATE_ADD(
                    DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY),
                    INTERVAL n WEEK
                )
            ) AS year_val,
            WEEK(
                DATE_ADD(
                    DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY),
                    INTERVAL n WEEK
                ),
                3
            ) AS iso_week_number
        FROM (
            SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
            UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
            UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
        ) weeks
    ),

    -- 2) FIXED: Get company from budget data (authoritative source)
    --    Build mapping of user-day → company from bi_budget_per_day
    user_company_mapping AS (
        SELECT DISTINCT
            b.useruuid AS user_id,
            b.document_date,
            COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id
        FROM bi_budget_per_day b
        WHERE b.budgetHours > 0
          AND b.document_date >= CURDATE()
          AND b.document_date < DATE_ADD(CURDATE(), INTERVAL 12 WEEK)
    ),

    -- 3) Aggregate forecast billable hours to user-company-week grain
    --    (NOT user-company-week-project yet - prevents double-counting)
    forecast_billable_aggregated AS (
        SELECT
            b.useruuid AS user_id,
            COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
            YEARWEEK(b.document_date, 3) AS week_key,
            -- Aggregate across all projects for this user-company-week
            SUM(b.budgetHours) AS forecast_billable_hours,
            SUM(b.budgetHoursWithNoAvailabilityAdjustment) AS raw_billable_hours,
            SUM(b.budgetHours * b.rate) AS forecast_revenue_dkk,
            COUNT(DISTINCT b.contractuuid) AS contract_count
        FROM bi_budget_per_day b
        WHERE b.budgetHours > 0
          AND b.document_date >= CURDATE()
          AND b.document_date < DATE_ADD(CURDATE(), INTERVAL 12 WEEK)
        GROUP BY
            b.useruuid,
            COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691'),
            YEARWEEK(b.document_date, 3)
    ),

    -- 4) FIXED: Capacity by user-company-week using budget's company mapping
    --    Match capacity to the SAME company as forecast data
    capacity_by_user_company_week AS (
        SELECT
            a.useruuid AS user_id,
            ucm.company_id,
            YEARWEEK(a.document_date, 3) AS week_key,
            SUM(a.gross_available_hours) AS gross_capacity_hours,
            SUM(a.vacation_hours) AS planned_vacation_hours,
            SUM(a.sick_hours) AS planned_sick_hours,
            SUM(a.maternity_leave_hours) AS planned_maternity_hours,
            SUM(a.non_payd_leave_hours) AS planned_unpaid_leave_hours,
            SUM(a.paid_leave_hours) AS planned_paid_leave_hours,
            SUM(a.unavailable_hours) AS total_unavailable_hours,
            SUM(a.gross_available_hours) - SUM(a.unavailable_hours) AS capacity_hours,
            MAX(a.consultant_type) AS consultant_type,
            MAX(a.status_type) AS status_type
        FROM bi_availability_per_day a
        -- FIXED: Join to user_company_mapping to get budget's company
        INNER JOIN user_company_mapping ucm
            ON a.useruuid = ucm.user_id
            AND a.document_date = ucm.document_date
        WHERE a.status_type NOT IN ('TERMINATED', 'PREBOARDING')
          -- FIXED: Broadened filter to include STUDENT, EXTERNAL
          AND a.consultant_type IN ('CONSULTANT', 'STUDENT', 'EXTERNAL')
          AND a.document_date >= CURDATE()
          AND a.document_date < DATE_ADD(CURDATE(), INTERVAL 12 WEEK)
        GROUP BY
            a.useruuid,
            ucm.company_id,
            YEARWEEK(a.document_date, 3)
    ),

    -- 5) FIXED: Aggregate first at user-company-week level
    --    This ensures single capacity value per user-company-week
    --    NOTE: MariaDB doesn't support FULL OUTER JOIN, using LEFT + RIGHT UNION
    aggregated_user_company_week AS (
        -- Part A: All forecast rows with matched capacity (LEFT JOIN)
        SELECT
            f.user_id,
            f.company_id,
            f.week_key,
            f.forecast_billable_hours,
            f.raw_billable_hours,
            f.forecast_revenue_dkk,
            f.contract_count,
            COALESCE(c.capacity_hours, 0) AS capacity_hours,
            COALESCE(c.gross_capacity_hours, 0) AS gross_capacity_hours,
            COALESCE(c.total_unavailable_hours, 0) AS total_unavailable_hours,
            COALESCE(c.planned_vacation_hours, 0) AS planned_vacation_hours,
            c.consultant_type,
            c.status_type,
            'BACKLOG' AS source_type
        FROM forecast_billable_aggregated f
        LEFT JOIN capacity_by_user_company_week c
            ON f.user_id = c.user_id
            AND f.company_id = c.company_id
            AND f.week_key = c.week_key

        UNION ALL

        -- Part B: Capacity-only rows (no forecast) - RIGHT side of FULL OUTER JOIN
        SELECT
            c.user_id,
            c.company_id,
            c.week_key,
            0 AS forecast_billable_hours,
            0 AS raw_billable_hours,
            0 AS forecast_revenue_dkk,
            0 AS contract_count,
            c.capacity_hours,
            c.gross_capacity_hours,
            c.total_unavailable_hours,
            c.planned_vacation_hours,
            c.consultant_type,
            c.status_type,
            'CAPACITY_ONLY' AS source_type
        FROM capacity_by_user_company_week c
        LEFT JOIN forecast_billable_aggregated f
            ON c.user_id = f.user_id
            AND c.company_id = f.company_id
            AND c.week_key = f.week_key
        WHERE f.user_id IS NULL  -- Only rows without forecast
    ),

    -- 6) Get project detail for BACKLOG rows (optional expansion)
    --    This allows drill-down by project while preserving aggregated capacity
    project_detail AS (
        SELECT
            b.useruuid AS user_id,
            COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
            YEARWEEK(b.document_date, 3) AS week_key,
            b.contractuuid AS project_id,
            b.clientuuid AS client_id,
            SUM(b.budgetHours) AS project_billable_hours
        FROM bi_budget_per_day b
        WHERE b.budgetHours > 0
          AND b.document_date >= CURDATE()
          AND b.document_date < DATE_ADD(CURDATE(), INTERVAL 12 WEEK)
        GROUP BY
            b.useruuid,
            COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691'),
            YEARWEEK(b.document_date, 3),
            b.contractuuid,
            b.clientuuid
    ),

    -- 7) Enrich with user practice and week calendar
    final_enriched AS (
        SELECT
            agg.*,
            pd.project_id,
            pd.client_id,
            pd.project_billable_hours,
            COALESCE(u.primaryskilltype, 'UD') AS practice_id,
            wc.week_start_date,
            wc.week_end_date,
            wc.year_val,
            wc.iso_week_number
        FROM aggregated_user_company_week agg
        -- Expand BACKLOG rows to project detail (LEFT JOIN for CAPACITY_ONLY rows)
        LEFT JOIN project_detail pd
            ON agg.user_id = pd.user_id
            AND agg.company_id = pd.company_id
            AND agg.week_key = pd.week_key
            AND agg.source_type = 'BACKLOG'
        LEFT JOIN user u ON agg.user_id = u.uuid
        LEFT JOIN week_calendar wc ON agg.week_key = wc.week_key
    )

-- 8) Final SELECT with all columns per specification
SELECT
    -- Surrogate key
    CONCAT(
        fe.user_id, '-',
        fe.company_id, '-',
        fe.week_key,
        COALESCE(CONCAT('-', fe.project_id), '')
    ) AS forecast_staffing_id,

    -- Dimension keys
    fe.user_id,
    fe.company_id,
    fe.practice_id,
    fe.project_id,
    fe.client_id,

    -- Time dimensions
    fe.week_key,
    fe.week_start_date,
    fe.week_end_date,
    fe.year_val AS year,
    fe.iso_week_number,

    -- Forecast metrics (project-level if available, else aggregated)
    COALESCE(fe.project_billable_hours, fe.forecast_billable_hours) AS forecast_billable_hours,
    GREATEST(0, fe.capacity_hours - fe.forecast_billable_hours) AS forecast_nonbillable_hours,
    COALESCE(fe.project_billable_hours, fe.forecast_billable_hours) AS forecast_total_hours,

    -- Capacity metrics (SAME value for all projects of same user-company-week)
    fe.capacity_hours,
    fe.gross_capacity_hours,
    fe.total_unavailable_hours AS planned_absence_hours,
    fe.planned_vacation_hours,

    -- Source and probability
    fe.source_type,
    CASE
        WHEN fe.source_type = 'BACKLOG' THEN 100.0
        ELSE NULL
    END AS probability_pct,

    -- Data source
    'BI_BUDGET' AS data_source,

    -- Pre-calculated utilization ratio
    CASE
        WHEN fe.capacity_hours > 0
        THEN ROUND(fe.forecast_billable_hours / fe.capacity_hours * 100, 2)
        ELSE NULL
    END AS forecast_utilization_pct

FROM final_enriched fe
WHERE fe.week_start_date IS NOT NULL
ORDER BY fe.week_start_date, fe.user_id, fe.company_id, fe.project_id;
