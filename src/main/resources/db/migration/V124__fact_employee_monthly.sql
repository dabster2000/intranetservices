CREATE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_employee_monthly` AS
WITH daily_employee_data AS (
    -- Step 1: Get daily employee data with practice and role classification
    SELECT
        b.companyuuid,
        b.document_date,
        b.year AS year_val,
        b.month AS month_val,
        b.useruuid,
        b.gross_available_hours,
        b.consultant_type,
        b.status_type,
        COALESCE(u.primaryskilltype, 'UD') AS practice_id,
        -- Classify as BILLABLE or NON_BILLABLE
        CASE
            WHEN b.consultant_type = 'CONSULTANT'
                AND b.status_type IN ('ACTIVE', 'PROBATION')
                THEN 'BILLABLE'
            ELSE 'NON_BILLABLE'
            END AS role_type,
        -- Calculate daily FTE (gross_available_hours / 8.0)
        COALESCE(b.gross_available_hours, 0) / 8.0 AS daily_fte
    FROM bi_data_per_day b
             INNER JOIN user u ON b.useruuid = u.uuid
    WHERE b.gross_available_hours > 0
      AND b.status_type != 'TERMINATED'
      AND b.consultant_type IN ('CONSULTANT', 'STAFF')
),

     month_boundaries AS (
         -- Step 2: Get first and last day of each month with employee counts
         SELECT
             companyuuid,
             year_val,
             month_val,
             practice_id,
             role_type,
             -- Headcount on first day of month
             COUNT(DISTINCT CASE
                                WHEN DAY(document_date) = 1
                                    THEN useruuid
                 END) AS headcount_start,
             -- Headcount on last day of month
             COUNT(DISTINCT CASE
                                WHEN document_date = LAST_DAY(document_date)
                                    THEN useruuid
                 END) AS headcount_end
         FROM daily_employee_data
         GROUP BY
             companyuuid,
             year_val,
             month_val,
             practice_id,
             role_type
     ),

     monthly_fte AS (
         -- Step 3: Aggregate FTE by company, practice, role_type, month
         SELECT
             companyuuid,
             year_val,
             month_val,
             practice_id,
             role_type,
             -- Total distinct employees in this month
             COUNT(DISTINCT useruuid) AS distinct_employees,
             -- Average daily FTE across all days in month
             SUM(daily_fte) / COUNT(DISTINCT document_date) AS avg_monthly_fte,
             -- Count billable employees
             COUNT(DISTINCT CASE
                                WHEN role_type = 'BILLABLE'
                                    THEN useruuid
                 END) AS billable_headcount,
             -- Count non-billable employees
             COUNT(DISTINCT CASE
                                WHEN role_type = 'NON_BILLABLE'
                                    THEN useruuid
                 END) AS non_billable_headcount,
             -- Sum FTE for billable
             SUM(CASE
                     WHEN role_type = 'BILLABLE'
                         THEN daily_fte
                     ELSE 0
                 END) / COUNT(DISTINCT document_date) AS fte_billable,
             -- Sum FTE for non-billable
             SUM(CASE
                     WHEN role_type = 'NON_BILLABLE'
                         THEN daily_fte
                     ELSE 0
                 END) / COUNT(DISTINCT document_date) AS fte_non_billable
         FROM daily_employee_data
         GROUP BY
             companyuuid,
             year_val,
             month_val,
             practice_id,
             role_type
     ),

     joiners_leavers AS (
         -- Step 4: Calculate joiners and leavers per month
         SELECT
             companyuuid,
             year_val,
             month_val,
             practice_id,
             role_type,
             -- Joiners: first appearance in this month
             COUNT(DISTINCT CASE
                                WHEN document_date = (
                                    SELECT MIN(b2.document_date)
                                    FROM bi_data_per_day b2
                                    WHERE b2.useruuid = daily_employee_data.useruuid
                                )
                                    THEN useruuid
                 END) AS joiners_count,
             -- Leavers: last appearance in this month
             COUNT(DISTINCT CASE
                                WHEN document_date = (
                                    SELECT MAX(b3.document_date)
                                    FROM bi_data_per_day b3
                                    WHERE b3.useruuid = daily_employee_data.useruuid
                                )
                                    AND document_date < CURDATE()
                                    THEN useruuid
                 END) AS leavers_count
         FROM daily_employee_data
         GROUP BY
             companyuuid,
             year_val,
             month_val,
             practice_id,
             role_type
     )

-- Step 5: Final aggregation combining all metrics
SELECT
    CONCAT(
            mf.companyuuid, '-',
            mf.practice_id, '-',
            mf.role_type, '-',
            CONCAT(LPAD(mf.year_val, 4, '0'), LPAD(mf.month_val, 2, '0'))
    ) AS employee_month_id,

    -- Dimensions
    mf.companyuuid AS company_id,
    mf.practice_id AS practice_id,
    mf.role_type AS role_type,
    NULL AS cost_center_id,

    -- Time dimensions
    CONCAT(LPAD(mf.year_val, 4, '0'), LPAD(mf.month_val, 2, '0')) AS month_key,
    mf.year_val AS year,
    mf.month_val AS month_number,

    -- Fiscal year calculations (July-June)
    CASE
        WHEN mf.month_val >= 7 THEN mf.year_val
        ELSE mf.year_val - 1
        END AS fiscal_year,

    CASE
        WHEN mf.month_val >= 7 THEN mf.month_val - 6
        ELSE mf.month_val + 6
        END AS fiscal_month_number,

    -- Headcount metrics
    COALESCE(mb.headcount_start, 0) AS headcount_start,
    COALESCE(mb.headcount_end, 0) AS headcount_end,
    ROUND((COALESCE(mb.headcount_start, 0) + COALESCE(mb.headcount_end, 0)) / 2.0, 2) AS average_headcount,
    COALESCE(mf.billable_headcount, 0) AS billable_headcount,
    COALESCE(mf.non_billable_headcount, 0) AS non_billable_headcount,

    -- FTE metrics
    ROUND(COALESCE(mf.avg_monthly_fte, 0), 2) AS fte_total,
    ROUND(COALESCE(mf.fte_billable, 0), 2) AS fte_billable,
    ROUND(COALESCE(mf.fte_non_billable, 0), 2) AS fte_non_billable,

    -- Movement metrics
    COALESCE(jl.joiners_count, 0) AS joiners_count,
    COALESCE(jl.leavers_count, 0) AS leavers_count,
    0 AS voluntary_leavers_count,

    -- Metadata
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

ORDER BY
    mf.year_val DESC,
    mf.month_val DESC,
    mf.companyuuid,
    mf.practice_id,
    mf.role_type;