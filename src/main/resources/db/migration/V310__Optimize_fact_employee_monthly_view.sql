-- Optimize fact_employee_monthly view -- eliminate correlated subqueries.
--
-- The original view (V???__Create_fact_employee_monthly.sql) computed
-- joiners_count and leavers_count using two correlated subqueries
-- per row of bi_data_per_day:
--
--     (SELECT MIN(b2.document_date) FROM bi_data_per_day b2
--      WHERE b2.useruuid = daily_employee_data.useruuid)
--     (SELECT MAX(b3.document_date) FROM bi_data_per_day b3
--      WHERE b3.useruuid = daily_employee_data.useruuid)
--
-- With ~70 users x ~10 years x ~365 days of bi_data_per_day rows, this
-- produced 500K+ subquery executions on every read. A 3-month
-- reconciliation query timed out at >3 minutes.
--
-- Block 4 of sp_refresh_fact_tables() already uses the optimized form
-- (an extra `user_date_bounds` CTE that pre-aggregates first/last date
-- per user once, then joins). The materialized fact_employee_monthly_mat
-- consequently rebuilds in ~6 seconds. This migration brings the same
-- optimization to the view itself so direct reads / reconciliation
-- queries are fast too. Output shape is byte-for-byte identical.

CREATE OR REPLACE
    ALGORITHM = UNDEFINED
    DEFINER = `admin`@`%`
    SQL SECURITY DEFINER
VIEW `fact_employee_monthly` AS
WITH
    user_date_bounds AS (
        SELECT
            useruuid,
            MIN(document_date) AS first_date,
            MAX(document_date) AS last_date
        FROM bi_data_per_day
        WHERE gross_available_hours > 0
          AND status_type != 'TERMINATED'
          AND consultant_type IN ('CONSULTANT', 'STAFF')
        GROUP BY useruuid
    ),

    daily_employee_data AS (
        SELECT
            b.companyuuid,
            b.document_date,
            b.year AS year_val,
            b.month AS month_val,
            b.useruuid,
            b.gross_available_hours,
            b.consultant_type,
            b.status_type,
            COALESCE(u.practice, 'UD') AS practice_id,
            CASE
                WHEN b.consultant_type = 'CONSULTANT'
                    AND b.status_type IN ('ACTIVE', 'PROBATION')
                    THEN 'BILLABLE'
                ELSE 'NON_BILLABLE'
            END AS role_type,
            COALESCE(b.gross_available_hours, 0) / 8.0 AS daily_fte,
            udb.first_date,
            udb.last_date
        FROM bi_data_per_day b
        INNER JOIN `user` u ON b.useruuid = u.uuid
        INNER JOIN user_date_bounds udb ON udb.useruuid = b.useruuid
        WHERE b.gross_available_hours > 0
          AND b.status_type != 'TERMINATED'
          AND b.consultant_type IN ('CONSULTANT', 'STAFF')
    ),

    month_boundaries AS (
        SELECT
            companyuuid,
            year_val,
            month_val,
            practice_id,
            role_type,
            COUNT(DISTINCT CASE
                WHEN DAYOFMONTH(document_date) = 1 THEN useruuid
            END) AS headcount_start,
            COUNT(DISTINCT CASE
                WHEN document_date = LAST_DAY(document_date) THEN useruuid
            END) AS headcount_end
        FROM daily_employee_data
        GROUP BY companyuuid, year_val, month_val, practice_id, role_type
    ),

    monthly_fte AS (
        SELECT
            companyuuid,
            year_val,
            month_val,
            practice_id,
            role_type,
            COUNT(DISTINCT useruuid) AS distinct_employees,
            SUM(daily_fte) / COUNT(DISTINCT document_date) AS avg_monthly_fte,
            COUNT(DISTINCT CASE WHEN role_type = 'BILLABLE' THEN useruuid END) AS billable_headcount,
            COUNT(DISTINCT CASE WHEN role_type = 'NON_BILLABLE' THEN useruuid END) AS non_billable_headcount,
            SUM(CASE WHEN role_type = 'BILLABLE' THEN daily_fte ELSE 0 END) / COUNT(DISTINCT document_date) AS fte_billable,
            SUM(CASE WHEN role_type = 'NON_BILLABLE' THEN daily_fte ELSE 0 END) / COUNT(DISTINCT document_date) AS fte_non_billable
        FROM daily_employee_data
        GROUP BY companyuuid, year_val, month_val, practice_id, role_type
    ),

    joiners_leavers AS (
        SELECT
            companyuuid,
            year_val,
            month_val,
            practice_id,
            role_type,
            COUNT(DISTINCT CASE
                WHEN document_date = first_date THEN useruuid
            END) AS joiners_count,
            COUNT(DISTINCT CASE
                WHEN document_date = last_date AND document_date < CURDATE()
                    THEN useruuid
            END) AS leavers_count
        FROM daily_employee_data
        GROUP BY companyuuid, year_val, month_val, practice_id, role_type
    )

SELECT
    CONCAT(mf.companyuuid, '-', mf.practice_id, '-', mf.role_type, '-',
           CONCAT(LPAD(mf.year_val, 4, '0'), LPAD(mf.month_val, 2, '0'))
    ) AS employee_month_id,
    mf.companyuuid AS company_id,
    mf.practice_id,
    mf.role_type,
    NULL AS cost_center_id,
    CONCAT(LPAD(mf.year_val, 4, '0'), LPAD(mf.month_val, 2, '0')) AS month_key,
    mf.year_val AS year,
    mf.month_val AS month_number,
    CASE WHEN mf.month_val >= 7 THEN mf.year_val ELSE mf.year_val - 1 END AS fiscal_year,
    CASE WHEN mf.month_val >= 7 THEN mf.month_val - 6 ELSE mf.month_val + 6 END AS fiscal_month_number,
    COALESCE(mb.headcount_start, 0) AS headcount_start,
    COALESCE(mb.headcount_end, 0) AS headcount_end,
    ROUND((COALESCE(mb.headcount_start, 0) + COALESCE(mb.headcount_end, 0)) / 2.0, 2) AS average_headcount,
    COALESCE(mf.billable_headcount, 0) AS billable_headcount,
    COALESCE(mf.non_billable_headcount, 0) AS non_billable_headcount,
    ROUND(COALESCE(mf.avg_monthly_fte, 0), 2) AS fte_total,
    ROUND(COALESCE(mf.fte_billable, 0), 2) AS fte_billable,
    ROUND(COALESCE(mf.fte_non_billable, 0), 2) AS fte_non_billable,
    COALESCE(jl.joiners_count, 0) AS joiners_count,
    COALESCE(jl.leavers_count, 0) AS leavers_count,
    0 AS voluntary_leavers_count,
    'HR_SYSTEM' AS data_source
FROM monthly_fte mf
LEFT JOIN month_boundaries mb
    ON mf.companyuuid = mb.companyuuid
    AND mf.year_val = mb.year_val
    AND mf.month_val = mb.month_val
    AND mf.practice_id = mb.practice_id
    AND mf.role_type = mb.role_type
LEFT JOIN joiners_leavers jl
    ON mf.companyuuid = jl.companyuuid
    AND mf.year_val = jl.year_val
    AND mf.month_val = jl.month_val
    AND mf.practice_id = jl.practice_id
    AND mf.role_type = jl.role_type
ORDER BY mf.year_val DESC, mf.month_val DESC, mf.companyuuid, mf.practice_id, mf.role_type;
