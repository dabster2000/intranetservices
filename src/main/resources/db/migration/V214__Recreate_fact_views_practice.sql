-- =============================================================================
-- Migration V214: Recreate fact views after primaryskilltype → practice rename
--
-- Purpose:
--   After V213 renames user.primaryskilltype to user.practice, all views that
--   referenced the old column name are broken. This migration recreates each
--   affected view with the updated column reference.
--
-- Views recreated (only those that referenced primaryskilltype or primary_skill_level):
--   1. fact_user_utilization      (originally V119)
--   2. fact_employee_monthly      (originally V124)
--   3. fact_revenue_budget        (originally V120)
--   4. fact_backlog               (originally V121)
--   5. fact_project_financials    (latest version: V194)
--   6. fact_salary_monthly        (originally V210)
--   7. fact_salary_monthly_teamroles (companion to fact_salary_monthly, V210)
--
-- Views NOT recreated (no primaryskilltype reference):
--   - fact_minimum_viable_rate    (already uses user_career_level since V178/V207)
--   - fact_company_revenue        (no user join, no primaryskilltype)
--   - fact_client_revenue         (no user join, no primaryskilltype)
--   - fact_pipeline               (inline mapping from sales_lead.competencies)
--
-- Change summary for each view:
--   COALESCE(u.primaryskilltype, 'UD') → COALESCE(u.practice, 'UD')
--   u.primaryskilltype IS NOT NULL     → u.practice IS NOT NULL
--   u.primaryskilltype                 → u.practice
--
-- Rollback strategy:
--   Re-run V119, V120, V121, V124, V194, and V210 to restore the prior
--   definitions. These views hold no data; rollback is instantaneous.
--
-- All statements use CREATE OR REPLACE VIEW — idempotent, safe to re-run.
-- =============================================================================


-- =============================================================================
-- 1. fact_user_utilization
--    Source: V119
--    Change: COALESCE(u.primaryskilltype, 'UD') → COALESCE(u.practice, 'UD')
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_user_utilization` AS
WITH user_months AS (
    SELECT
        b.`useruuid` AS user_id,
        b.`companyuuid` AS companyuuid,
        b.`year` AS year_val,
        b.`month` AS month_val,
        SUM(COALESCE(b.`gross_available_hours`, 0))    AS gross_available_hours,
        SUM(COALESCE(b.`unavailable_hours`, 0))        AS unavailable_hours,
        SUM(COALESCE(b.`vacation_hours`, 0))           AS vacation_hours,
        SUM(COALESCE(b.`sick_hours`, 0))               AS sick_hours,
        SUM(COALESCE(b.`maternity_leave_hours`, 0))    AS maternity_leave_hours,
        SUM(COALESCE(b.`non_payd_leave_hours`, 0))     AS non_payd_leave_hours,
        SUM(COALESCE(b.`paid_leave_hours`, 0))         AS paid_leave_hours
    FROM `bi_data_per_day` b
    WHERE b.`consultant_type` = 'CONSULTANT'
      AND b.`status_type` != 'TERMINATED'
    GROUP BY
        b.`useruuid`,
        b.`companyuuid`,
        b.`year`,
        b.`month`
),
     billable_hours_by_user_month AS (
         SELECT
             wf.`useruuid` AS user_id,
             YEAR(wf.`registered`) AS year_val,
             MONTH(wf.`registered`) AS month_val,
             SUM(
                     CASE
                         WHEN wf.`rate` > 0
                             AND wf.`workduration` > 0
                             THEN wf.`workduration`
                         ELSE 0
                         END
             ) AS billable_hours
         FROM `work_full` wf
         WHERE wf.`registered` IS NOT NULL
           AND wf.`workduration` > 0
           AND wf.`rate` > 0
           AND wf.`type` = 'CONSULTANT'
         GROUP BY
             wf.`useruuid`,
             YEAR(wf.`registered`),
             MONTH(wf.`registered`)
     )
SELECT
    CONCAT(
            um.user_id,
            '-',
            CONCAT(LPAD(um.year_val, 4, '0'), LPAD(um.month_val, 2, '0'))
    ) AS utilization_id,
    um.user_id          AS user_id,
    um.companyuuid      AS companyuuid,
    COALESCE(u.`practice`, 'UD') AS practice_id,
    NULL                AS client_id,
    'OTHER'             AS sector_id,
    'PERIOD'            AS contract_type_id,
    CONCAT(
            LPAD(um.year_val, 4, '0'),
            LPAD(um.month_val, 2, '0')
    ) AS month_key,
    um.year_val         AS year,
    um.month_val        AS month_number,

    -- availability + leave breakdown (monthly)
    um.gross_available_hours         AS gross_available_hours,
    um.unavailable_hours             AS unavailable_hours,
    um.vacation_hours                AS vacation_hours,
    um.sick_hours                    AS sick_hours,
    um.maternity_leave_hours         AS maternity_leave_hours,
    um.non_payd_leave_hours          AS non_payd_leave_hours,
    um.paid_leave_hours              AS paid_leave_hours,

    -- net available & billable
    (um.gross_available_hours - um.unavailable_hours) AS net_available_hours,
    COALESCE(bh.billable_hours, 0)                   AS billable_hours,

    -- utilization: 0 if net_available_hours <= 0
    CASE
        WHEN (um.gross_available_hours - um.unavailable_hours) > 0
            THEN COALESCE(bh.billable_hours, 0)
            / (um.gross_available_hours - um.unavailable_hours)
        ELSE 0
        END AS utilization_ratio
FROM user_months um
         LEFT JOIN billable_hours_by_user_month bh
                   ON bh.user_id  = um.user_id
                       AND bh.year_val = um.year_val
                       AND bh.month_val = um.month_val
         LEFT JOIN `user` u
                   ON u.`uuid` = um.user_id
ORDER BY
    um.year_val DESC,
    um.month_val DESC,
    um.user_id;


-- =============================================================================
-- 2. fact_employee_monthly
--    Source: V124
--    Change: COALESCE(u.primaryskilltype, 'UD') → COALESCE(u.practice, 'UD')
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
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
        COALESCE(u.practice, 'UD') AS practice_id,
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


-- =============================================================================
-- 3. fact_revenue_budget
--    Source: V120
--    Change: COALESCE(u.primaryskilltype, 'UD') → COALESCE(u.practice, 'UD')
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
SQL SECURITY DEFINER
VIEW `fact_revenue_budget` AS
WITH budget_with_dimensions AS (
    SELECT
        b.companyuuid,
        b.clientuuid,
        b.contractuuid,
        b.useruuid,
        b.year AS year_val,
        b.month AS month_val,
        b.budgetHours,
        b.rate,
        COALESCE(u.practice, 'UD') AS service_line_id,
        COALESCE(c.segment, 'OTHER') AS sector_id,
        COALESCE(ct.contracttype, 'PERIOD') AS contract_type_id
    FROM bi_budget_per_day b
    LEFT JOIN user u ON b.useruuid = u.uuid
    LEFT JOIN client c ON b.clientuuid = c.uuid
    LEFT JOIN contracts ct ON b.contractuuid = ct.uuid
    WHERE b.budgetHours > 0
      AND b.document_date IS NOT NULL
      AND b.companyuuid IS NOT NULL
),
budget_aggregated AS (
    SELECT
        bd.companyuuid,
        bd.service_line_id,
        bd.sector_id,
        bd.contract_type_id,
        bd.year_val,
        bd.month_val,
        SUM(bd.budgetHours * bd.rate) AS budget_revenue_dkk,
        SUM(bd.budgetHours) AS budget_hours,
        COUNT(DISTINCT bd.contractuuid) AS contract_count,
        COUNT(DISTINCT bd.useruuid) AS consultant_count
    FROM budget_with_dimensions bd
    GROUP BY bd.companyuuid, bd.service_line_id, bd.sector_id, bd.contract_type_id, bd.year_val, bd.month_val
)
SELECT
    -- Primary Key
    CONCAT(
        ba.companyuuid, '-',
        ba.service_line_id, '-',
        ba.sector_id, '-',
        ba.contract_type_id, '-',
        LPAD(ba.year_val, 4, '0'),
        LPAD(ba.month_val, 2, '0')
    ) AS revenue_budget_id,

    -- Dimension Columns
    ba.companyuuid AS company_id,
    ba.service_line_id,
    ba.sector_id,
    ba.contract_type_id,

    -- Calendar Time Dimensions (existing)
    CONCAT(LPAD(ba.year_val, 4, '0'), LPAD(ba.month_val, 2, '0')) AS month_key,
    ba.year_val AS year,
    ba.month_val AS month_number,

    -- Fiscal Year Dimensions
    CASE
        WHEN ba.month_val >= 7 THEN ba.year_val     -- Jul-Dec: same year
        ELSE ba.year_val - 1                        -- Jan-Jun: previous year
    END AS fiscal_year,

    CASE
        WHEN ba.month_val >= 7 THEN ba.month_val - 6  -- Jul=1, Aug=2, ..., Dec=6
        ELSE ba.month_val + 6                         -- Jan=7, Feb=8, ..., Jun=12
    END AS fiscal_month_number,

    CONCAT(
        'FY',
        CASE WHEN ba.month_val >= 7 THEN ba.year_val ELSE ba.year_val - 1 END,
        '-',
        LPAD(
            CASE WHEN ba.month_val >= 7 THEN ba.month_val - 6 ELSE ba.month_val + 6 END,
            2, '0'
        )
    ) AS fiscal_month_key,

    -- Budget Scenario
    'ORIGINAL' AS budget_scenario,

    -- Metrics
    ba.budget_revenue_dkk,
    ba.budget_hours,
    ba.contract_count,
    ba.consultant_count
FROM budget_aggregated ba
ORDER BY ba.year_val DESC, ba.month_val DESC, ba.companyuuid, ba.service_line_id;


-- =============================================================================
-- 4. fact_backlog
--    Source: V121
--    Changes:
--      - u.primaryskilltype → u.practice  (in contract_service_line subquery)
--      - u.primaryskilltype IS NOT NULL → u.practice IS NOT NULL
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_backlog` AS

WITH
    -- 1) Aggregate bi_budget_per_day to contract-month level
    backlog_by_contract_month AS (
        SELECT
            b.contractuuid AS contract_uuid,
            b.clientuuid AS client_uuid,
            COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_uuid,
            b.year AS year_val,
            b.month AS month_val,
            -- Backlog revenue = SUM(adjusted hours × rate) for all days in month
            SUM(b.budgetHours * b.rate) AS backlog_revenue_dkk,
            -- Also track raw hours for transparency
            SUM(b.budgetHours) AS adjusted_hours,
            SUM(b.budgetHoursWithNoAvailabilityAdjustment) AS raw_hours,
            COUNT(DISTINCT b.useruuid) AS consultant_count
        FROM bi_budget_per_day b
        WHERE b.budgetHours > 0
          -- Only include future months (current month and beyond)
          AND b.document_date >= DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01'))
        GROUP BY b.contractuuid, b.clientuuid, b.companyuuid, b.year, b.month
    ),

    -- 2) Get contract metadata (contract type, status)
    contract_metadata AS (
        SELECT
            c.uuid AS contract_uuid,
            c.contracttype AS contract_type,
            c.status AS contract_status
        FROM contracts c
        WHERE c.status IN ('SIGNED', 'TIME', 'BUDGET')
    ),

    -- 3) Get dominant service line per contract (from assigned consultants)
    contract_service_line AS (
        SELECT
            cc.contractuuid AS contract_uuid,
            COALESCE(
                (SELECT u.practice
                 FROM contract_consultants cc2
                 JOIN user u ON cc2.useruuid = u.uuid
                 WHERE cc2.contractuuid = cc.contractuuid
                   AND u.practice IS NOT NULL
                 GROUP BY u.practice
                 ORDER BY COUNT(*) DESC, SUM(cc2.hours) DESC
                 LIMIT 1),
                'UD'
            ) AS service_line_id
        FROM contract_consultants cc
        GROUP BY cc.contractuuid
    )

SELECT
    -- Surrogate key (contract-company-month)
    CONCAT(
        bcm.contract_uuid, '-',
        bcm.company_uuid, '-',
        LPAD(bcm.year_val, 4, '0'),
        LPAD(bcm.month_val, 2, '0')
    ) AS backlog_id,

    -- Dimension keys
    bcm.contract_uuid AS project_id,
    bcm.client_uuid AS client_id,
    bcm.company_uuid AS company_id,
    COALESCE(csl.service_line_id, 'UD') AS service_line_id,
    COALESCE(cl.segment, 'OTHER') AS sector_id,
    COALESCE(cm.contract_type, 'PERIOD') AS contract_type_id,

    -- Time dimensions
    CONCAT(LPAD(bcm.year_val, 4, '0'), LPAD(bcm.month_val, 2, '0')) AS delivery_month_key,
    bcm.year_val AS year,
    bcm.month_val AS month_number,

    -- Metrics
    bcm.backlog_revenue_dkk,
    bcm.consultant_count,
    1 AS contract_count,  -- Each row is one contract

    -- Status (all backlog from active contracts)
    'ACTIVE' AS project_status,

    -- Data source
    'BI_BUDGET' AS data_source

FROM backlog_by_contract_month bcm
    LEFT JOIN contract_metadata cm ON bcm.contract_uuid = cm.contract_uuid
    LEFT JOIN client cl ON bcm.client_uuid = cl.uuid
    LEFT JOIN contract_service_line csl ON bcm.contract_uuid = csl.contract_uuid
WHERE bcm.backlog_revenue_dkk > 0
ORDER BY bcm.year_val, bcm.month_val, bcm.contract_uuid;


-- =============================================================================
-- 5. fact_project_financials
--    Source: V194 (latest version — includes companyuuid in composite key)
--    Changes:
--      - u.primaryskilltype → u.practice  (in service_line_ranking CTE)
--      - u.primaryskilltype IS NOT NULL → u.practice IS NOT NULL
--      - CTE variable primaryskilltype → practice
--      - dominant_service_line: slr.primaryskilltype → slr.practice
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
SQL SECURITY DEFINER
VIEW `fact_project_financials` AS
WITH

-- 1) Project-month base from work entries
project_months AS (
    SELECT DISTINCT
        COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
        YEAR(w.registered) AS year_val,
        MONTH(w.registered) AS month_val
    FROM `work` w
    LEFT JOIN `task` t ON w.taskuuid = t.uuid
    LEFT JOIN `project` p ON COALESCE(w.projectuuid, t.projectuuid) = p.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND p.uuid IS NOT NULL
      AND w.registered IS NOT NULL
      AND w.workduration > 0
),

-- 2) Invoice lines with companyuuid resolved from userstatus on invoicedate
invoice_line_companies AS (
    SELECT
        p.uuid AS project_uuid,
        YEAR(i.invoicedate) AS year_val,
        MONTH(i.invoicedate) AS month_val,
        COALESCE(
            (
                SELECT us.companyuuid
                FROM userstatus us
                WHERE us.useruuid = ii.consultantuuid
                  AND us.statusdate <= i.invoicedate
                ORDER BY us.statusdate DESC
                LIMIT 1
            ),
            'd8894494-2fb4-4f72-9e05-e6032e6dd691'
        ) AS companyuuid,
        CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END AS sign,
        (ii.rate * ii.hours *
            CASE WHEN i.currency = 'DKK' THEN 1 ELSE c.conversion END
        ) AS line_amount_dkk
    FROM `project` p
    JOIN `invoices` i ON p.uuid = i.projectuuid
    JOIN `invoiceitems` ii ON i.uuid = ii.invoiceuuid
    LEFT JOIN `currences` c ON c.currency = i.currency AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m')
    WHERE i.status = 'CREATED'
      AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE')
      AND ii.rate IS NOT NULL
      AND ii.hours IS NOT NULL
      AND (i.currency = 'DKK' OR c.uuid IS NOT NULL)
),

-- 3) Revenue per project-month-company
revenue_by_company AS (
    SELECT
        ilc.project_uuid,
        ilc.year_val,
        ilc.month_val,
        ilc.companyuuid,
        SUM(ilc.sign * ilc.line_amount_dkk) AS total_revenue_dkk
    FROM invoice_line_companies ilc
    GROUP BY ilc.project_uuid, ilc.year_val, ilc.month_val, ilc.companyuuid
),

-- 4) Work cost (employees) per project-month-company
work_cost_aggregation AS (
    SELECT
        wc.project_uuid,
        wc.year_val,
        wc.month_val,
        wc.companyuuid,
        SUM(wc.employee_salary_cost_dkk) AS employee_salary_cost_dkk,
        SUM(wc.workduration) AS total_hours,
        COUNT(DISTINCT wc.useruuid) AS consultant_count
    FROM (
        SELECT
            COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
            YEAR(w.registered) AS year_val,
            MONTH(w.registered) AS month_val,
            COALESCE(
                (
                    SELECT us.companyuuid
                    FROM userstatus us
                    WHERE us.useruuid = w.useruuid
                      AND us.statusdate <= w.registered
                    ORDER BY us.statusdate DESC
                    LIMIT 1
                ),
                'd8894494-2fb4-4f72-9e05-e6032e6dd691'
            ) AS companyuuid,
            w.useruuid,
            w.workduration,
            w.workduration * (
                COALESCE(
                    (
                        SELECT s.salary
                        FROM salary s
                        WHERE s.useruuid = w.useruuid
                          AND s.activefrom <= w.registered
                        ORDER BY s.activefrom DESC
                        LIMIT 1
                    ),
                    0
                ) / 160.33
            ) AS employee_salary_cost_dkk
        FROM `work` w
        LEFT JOIN `task` t ON w.taskuuid = t.uuid
        JOIN `user` u ON w.useruuid = u.uuid
        WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
          AND u.type = 'USER'
          AND w.workduration > 0
          AND w.registered IS NOT NULL
    ) wc
    GROUP BY wc.project_uuid, wc.year_val, wc.month_val, wc.companyuuid
),

-- 5) External consultant cost per project-month-company
external_consultant_cost_aggregation AS (
    SELECT
        ec.project_uuid,
        ec.year_val,
        ec.month_val,
        ec.companyuuid,
        SUM(ec.external_cost_dkk) AS external_cost_dkk
    FROM (
        SELECT
            COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
            YEAR(w.registered) AS year_val,
            MONTH(w.registered) AS month_val,
            COALESCE(
                (
                    SELECT us.companyuuid
                    FROM userstatus us
                    WHERE us.useruuid = w.useruuid
                      AND us.statusdate <= w.registered
                    ORDER BY us.statusdate DESC
                    LIMIT 1
                ),
                'd8894494-2fb4-4f72-9e05-e6032e6dd691'
            ) AS companyuuid,
            w.workduration * COALESCE(
                (
                    SELECT s.salary
                    FROM salary s
                    WHERE s.useruuid = w.useruuid
                      AND s.activefrom <= w.registered
                      AND s.type = 'HOURLY'
                    ORDER BY s.activefrom DESC
                    LIMIT 1
                ),
                0
            ) AS external_cost_dkk
        FROM `work` w
        LEFT JOIN `task` t ON w.taskuuid = t.uuid
        JOIN `user` u ON w.useruuid = u.uuid
        WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
          AND u.type IN ('EXTERNAL', 'CONSULTANT')
          AND w.workduration > 0
          AND w.registered IS NOT NULL
    ) ec
    GROUP BY ec.project_uuid, ec.year_val, ec.month_val, ec.companyuuid
),

-- 6) Project expenses aggregated per project-month (no company split yet)
expenses_by_project_month AS (
    SELECT
        e.projectuuid AS project_uuid,
        YEAR(e.expensedate) AS year_val,
        MONTH(e.expensedate) AS month_val,
        SUM(e.amount) AS expense_cost_dkk
    FROM `expenses` e
    WHERE e.projectuuid IS NOT NULL
      AND e.status IN ('VERIFIED_BOOKED','VERIFIED_UNBOOKED')
      AND e.expensedate IS NOT NULL
    GROUP BY e.projectuuid, YEAR(e.expensedate), MONTH(e.expensedate)
),

-- 7) Build company weights per project-month
revenue_weights AS (
    SELECT
        rbc.project_uuid,
        rbc.year_val,
        rbc.month_val,
        rbc.companyuuid,
        rbc.total_revenue_dkk,
        SUM(rbc.total_revenue_dkk) OVER (PARTITION BY rbc.project_uuid, rbc.year_val, rbc.month_val) AS sum_rev,
        CASE
            WHEN SUM(rbc.total_revenue_dkk) OVER (PARTITION BY rbc.project_uuid, rbc.year_val, rbc.month_val) > 0
                THEN rbc.total_revenue_dkk / SUM(rbc.total_revenue_dkk) OVER (PARTITION BY rbc.project_uuid, rbc.year_val, rbc.month_val)
            ELSE NULL
        END AS rev_weight
    FROM revenue_by_company rbc
),

hour_weights AS (
    SELECT
        wca.project_uuid,
        wca.year_val,
        wca.month_val,
        wca.companyuuid,
        wca.total_hours,
        SUM(wca.total_hours) OVER (PARTITION BY wca.project_uuid, wca.year_val, wca.month_val) AS sum_hours,
        CASE
            WHEN SUM(wca.total_hours) OVER (PARTITION BY wca.project_uuid, wca.year_val, wca.month_val) > 0
                THEN wca.total_hours / SUM(wca.total_hours) OVER (PARTITION BY wca.project_uuid, wca.year_val, wca.month_val)
            ELSE NULL
        END AS hour_weight
    FROM work_cost_aggregation wca
),

-- 8) Distribute expenses by chosen weights
project_expense_by_company AS (
    SELECT
        e.project_uuid,
        e.year_val,
        e.month_val,
        w.companyuuid,
        e.expense_cost_dkk * w.weight AS expense_cost_dkk
    FROM expenses_by_project_month e
    JOIN (
        SELECT project_uuid, year_val, month_val, companyuuid, rev_weight AS weight
        FROM revenue_weights
        WHERE sum_rev > 0
        UNION ALL
        SELECT project_uuid, year_val, month_val, companyuuid, hour_weight AS weight
        FROM hour_weights
        WHERE sum_hours > 0
        UNION ALL
        SELECT e2.project_uuid, e2.year_val, e2.month_val, 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AS companyuuid, 1.0 AS weight
        FROM expenses_by_project_month e2
        WHERE NOT EXISTS (
            SELECT 1 FROM revenue_weights r
            WHERE r.project_uuid = e2.project_uuid AND r.year_val = e2.year_val AND r.month_val = e2.month_val AND r.sum_rev > 0
        )
        AND NOT EXISTS (
            SELECT 1 FROM hour_weights h
            WHERE h.project_uuid = e2.project_uuid AND h.year_val = e2.year_val AND h.month_val = e2.month_val AND h.sum_hours > 0
        )
    ) w ON w.project_uuid = e.project_uuid AND w.year_val = e.year_val AND w.month_val = e.month_val
),

-- 9) Total cost per project-month-company
total_cost_aggregation AS (
    SELECT
        COALESCE(wca.project_uuid, eca.project_uuid, pebc.project_uuid) AS project_uuid,
        COALESCE(wca.year_val, eca.year_val, pebc.year_val) AS year_val,
        COALESCE(wca.month_val, eca.month_val, pebc.month_val) AS month_val,
        COALESCE(wca.companyuuid, eca.companyuuid, pebc.companyuuid) AS companyuuid,
        COALESCE(wca.employee_salary_cost_dkk, 0) AS employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) AS external_cost_dkk,
        COALESCE(pebc.expense_cost_dkk, 0) AS expense_cost_dkk,
        COALESCE(wca.employee_salary_cost_dkk, 0) + COALESCE(eca.external_cost_dkk, 0) + COALESCE(pebc.expense_cost_dkk, 0) AS total_cost_dkk,
        COALESCE(wca.total_hours, 0) AS total_hours,
        COALESCE(wca.consultant_count, 0) AS consultant_count
    FROM work_cost_aggregation wca
    LEFT JOIN external_consultant_cost_aggregation eca
        ON wca.project_uuid = eca.project_uuid AND wca.year_val = eca.year_val AND wca.month_val = eca.month_val AND wca.companyuuid = eca.companyuuid
    LEFT JOIN project_expense_by_company pebc
        ON wca.project_uuid = pebc.project_uuid AND wca.year_val = pebc.year_val AND wca.month_val = pebc.month_val AND wca.companyuuid = pebc.companyuuid

    UNION

    SELECT
        COALESCE(eca.project_uuid, pebc.project_uuid) AS project_uuid,
        COALESCE(eca.year_val, pebc.year_val) AS year_val,
        COALESCE(eca.month_val, pebc.month_val) AS month_val,
        COALESCE(eca.companyuuid, pebc.companyuuid) AS companyuuid,
        0 AS employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) AS external_cost_dkk,
        COALESCE(pebc.expense_cost_dkk, 0) AS expense_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) + COALESCE(pebc.expense_cost_dkk, 0) AS total_cost_dkk,
        0 AS total_hours,
        0 AS consultant_count
    FROM external_consultant_cost_aggregation eca
    LEFT JOIN project_expense_by_company pebc
        ON eca.project_uuid = pebc.project_uuid AND eca.year_val = pebc.year_val AND eca.month_val = pebc.month_val AND eca.companyuuid = pebc.companyuuid
    WHERE NOT EXISTS (
        SELECT 1 FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = eca.project_uuid AND wca2.year_val = eca.year_val AND wca2.month_val = eca.month_val AND wca2.companyuuid = eca.companyuuid
        LIMIT 1
    )

    UNION

    SELECT
        pebc.project_uuid,
        pebc.year_val,
        pebc.month_val,
        pebc.companyuuid,
        0 AS employee_salary_cost_dkk,
        0 AS external_cost_dkk,
        pebc.expense_cost_dkk AS expense_cost_dkk,
        pebc.expense_cost_dkk AS total_cost_dkk,
        0 AS total_hours,
        0 AS consultant_count
    FROM project_expense_by_company pebc
    WHERE NOT EXISTS (
        SELECT 1 FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = pebc.project_uuid AND wca2.year_val = pebc.year_val AND wca2.month_val = pebc.month_val AND wca2.companyuuid = pebc.companyuuid
        LIMIT 1
    )
    AND NOT EXISTS (
        SELECT 1 FROM external_consultant_cost_aggregation eca2
        WHERE eca2.project_uuid = pebc.project_uuid AND eca2.year_val = pebc.year_val AND eca2.month_val = pebc.month_val AND eca2.companyuuid = pebc.companyuuid
        LIMIT 1
    )
),

-- 10) Service line: dominant practice per project-month (no company split)
service_line_ranking AS (
    SELECT
        COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
        YEAR(w.registered) AS year_val,
        MONTH(w.registered) AS month_val,
        u.practice AS practice,
        SUM(w.workduration) AS hours_by_practice,
        ROW_NUMBER() OVER (
            PARTITION BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered)
            ORDER BY SUM(w.workduration) DESC, COUNT(DISTINCT w.useruuid) DESC
        ) AS skill_rank
    FROM `work` w
    LEFT JOIN `task` t ON w.taskuuid = t.uuid
    JOIN `user` u ON w.useruuid = u.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND u.type = 'USER'
      AND u.practice IS NOT NULL
      AND w.workduration > 0
    GROUP BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered), u.practice
),

dominant_service_line AS (
    SELECT slr.project_uuid, slr.year_val, slr.month_val, slr.practice AS dominant_skilltype
    FROM service_line_ranking slr
    WHERE slr.skill_rank = 1
),

-- 11) Contract type per project
project_contract_types AS (
    SELECT
        p.uuid AS project_uuid,
        c.contracttype AS contracttype,
        COUNT(0) AS contract_count,
        ROW_NUMBER() OVER (
            PARTITION BY p.uuid
            ORDER BY COUNT(0) DESC
        ) AS rank_num
    FROM `project` p
    JOIN `contract_project` cp ON p.uuid = cp.projectuuid
    JOIN `contracts` c ON cp.contractuuid = c.uuid
    WHERE c.contracttype IS NOT NULL
      AND c.status IN ('SIGNED','TIME','BUDGET')
    GROUP BY p.uuid, c.contracttype
),

primary_contract_type AS (
    SELECT pct.project_uuid, pct.contracttype
    FROM project_contract_types pct
    WHERE pct.rank_num = 1
),

-- 12) Final project-month-company key set
project_month_companies AS (
    SELECT DISTINCT pm.project_uuid, pm.year_val, pm.month_val, rbc.companyuuid
    FROM project_months pm
    JOIN revenue_by_company rbc ON pm.project_uuid = rbc.project_uuid AND pm.year_val = rbc.year_val AND pm.month_val = rbc.month_val

    UNION

    SELECT DISTINCT pm.project_uuid, pm.year_val, pm.month_val, tca.companyuuid
    FROM project_months pm
    JOIN total_cost_aggregation tca ON pm.project_uuid = tca.project_uuid AND pm.year_val = tca.year_val AND pm.month_val = tca.month_val

    UNION

    SELECT pm.project_uuid, pm.year_val, pm.month_val, 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AS companyuuid
    FROM project_months pm
    WHERE NOT EXISTS (
        SELECT 1 FROM revenue_by_company rbc
        WHERE rbc.project_uuid = pm.project_uuid AND rbc.year_val = pm.year_val AND rbc.month_val = pm.month_val
    )
    AND NOT EXISTS (
        SELECT 1 FROM total_cost_aggregation tca
        WHERE tca.project_uuid = pm.project_uuid AND tca.year_val = pm.year_val AND tca.month_val = pm.month_val
    )
)

SELECT
    -- Composite key includes companyuuid to prevent INSERT IGNORE duplicate-row drops
    CONCAT(pmc.project_uuid, '-', pmc.companyuuid, '-', CONCAT(LPAD(pmc.year_val, 4, '0'), LPAD(pmc.month_val, 2, '0'))) AS project_financial_id,
    pmc.project_uuid AS project_id,
    p.clientuuid AS client_id,
    pmc.companyuuid AS companyuuid,
    COALESCE(c.segment, 'OTHER') AS sector_id,
    COALESCE(dsl.dominant_skilltype, 'UNKNOWN') AS service_line_id,
    COALESCE(pct.contracttype, 'PERIOD') AS contract_type_id,
    CONCAT(LPAD(pmc.year_val, 4, '0'), LPAD(pmc.month_val, 2, '0')) AS month_key,
    pmc.year_val AS year,
    pmc.month_val AS month_number,
    COALESCE(rbc.total_revenue_dkk, 0) AS recognized_revenue_dkk,
    COALESCE(tca.employee_salary_cost_dkk, 0) AS employee_salary_cost_dkk,
    COALESCE(tca.external_cost_dkk, 0) AS external_consultant_cost_dkk,
    COALESCE(tca.expense_cost_dkk, 0) AS project_expense_cost_dkk,
    COALESCE(tca.total_cost_dkk, 0) AS direct_delivery_cost_dkk,
    COALESCE(tca.total_hours, 0) AS total_hours,
    COALESCE(tca.consultant_count, 0) AS consultant_count,
    'OPERATIONAL' AS data_source
FROM project_month_companies pmc
JOIN `project` p ON pmc.project_uuid = p.uuid
LEFT JOIN `client` c ON p.clientuuid = c.uuid
LEFT JOIN revenue_by_company rbc
    ON pmc.project_uuid = rbc.project_uuid AND pmc.year_val = rbc.year_val AND pmc.month_val = rbc.month_val AND pmc.companyuuid = rbc.companyuuid
LEFT JOIN total_cost_aggregation tca
    ON pmc.project_uuid = tca.project_uuid AND pmc.year_val = tca.year_val AND pmc.month_val = tca.month_val AND pmc.companyuuid = tca.companyuuid
LEFT JOIN dominant_service_line dsl
    ON pmc.project_uuid = dsl.project_uuid AND pmc.year_val = dsl.year_val AND pmc.month_val = dsl.month_val
LEFT JOIN primary_contract_type pct ON pmc.project_uuid = pct.project_uuid
ORDER BY pmc.year_val DESC, pmc.month_val DESC, pmc.project_uuid, pmc.companyuuid;


-- =============================================================================
-- 6. fact_salary_monthly
--    Source: V210
--    Change: u.primaryskilltype AS practice_id → u.practice AS practice_id
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_salary_monthly` AS

WITH

nums AS (
    -- 256-row sequence 0..255 for month offset generation
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
    UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
    UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
    UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
    UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23
    UNION ALL SELECT 24 UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27
    UNION ALL SELECT 28 UNION ALL SELECT 29 UNION ALL SELECT 30 UNION ALL SELECT 31
    UNION ALL SELECT 32 UNION ALL SELECT 33 UNION ALL SELECT 34 UNION ALL SELECT 35
    UNION ALL SELECT 36 UNION ALL SELECT 37 UNION ALL SELECT 38 UNION ALL SELECT 39
    UNION ALL SELECT 40 UNION ALL SELECT 41 UNION ALL SELECT 42 UNION ALL SELECT 43
    UNION ALL SELECT 44 UNION ALL SELECT 45 UNION ALL SELECT 46 UNION ALL SELECT 47
    UNION ALL SELECT 48 UNION ALL SELECT 49 UNION ALL SELECT 50 UNION ALL SELECT 51
    UNION ALL SELECT 52 UNION ALL SELECT 53 UNION ALL SELECT 54 UNION ALL SELECT 55
    UNION ALL SELECT 56 UNION ALL SELECT 57 UNION ALL SELECT 58 UNION ALL SELECT 59
    UNION ALL SELECT 60 UNION ALL SELECT 61 UNION ALL SELECT 62 UNION ALL SELECT 63
    UNION ALL SELECT 64 UNION ALL SELECT 65 UNION ALL SELECT 66 UNION ALL SELECT 67
    UNION ALL SELECT 68 UNION ALL SELECT 69 UNION ALL SELECT 70 UNION ALL SELECT 71
    UNION ALL SELECT 72 UNION ALL SELECT 73 UNION ALL SELECT 74 UNION ALL SELECT 75
    UNION ALL SELECT 76 UNION ALL SELECT 77 UNION ALL SELECT 78 UNION ALL SELECT 79
    UNION ALL SELECT 80 UNION ALL SELECT 81 UNION ALL SELECT 82 UNION ALL SELECT 83
    UNION ALL SELECT 84 UNION ALL SELECT 85 UNION ALL SELECT 86 UNION ALL SELECT 87
    UNION ALL SELECT 88 UNION ALL SELECT 89 UNION ALL SELECT 90 UNION ALL SELECT 91
    UNION ALL SELECT 92 UNION ALL SELECT 93 UNION ALL SELECT 94 UNION ALL SELECT 95
    UNION ALL SELECT 96 UNION ALL SELECT 97 UNION ALL SELECT 98 UNION ALL SELECT 99
    UNION ALL SELECT 100 UNION ALL SELECT 101 UNION ALL SELECT 102 UNION ALL SELECT 103
    UNION ALL SELECT 104 UNION ALL SELECT 105 UNION ALL SELECT 106 UNION ALL SELECT 107
    UNION ALL SELECT 108 UNION ALL SELECT 109 UNION ALL SELECT 110 UNION ALL SELECT 111
    UNION ALL SELECT 112 UNION ALL SELECT 113 UNION ALL SELECT 114 UNION ALL SELECT 115
    UNION ALL SELECT 116 UNION ALL SELECT 117 UNION ALL SELECT 118 UNION ALL SELECT 119
    UNION ALL SELECT 120 UNION ALL SELECT 121 UNION ALL SELECT 122 UNION ALL SELECT 123
    UNION ALL SELECT 124 UNION ALL SELECT 125 UNION ALL SELECT 126 UNION ALL SELECT 127
    UNION ALL SELECT 128 UNION ALL SELECT 129 UNION ALL SELECT 130 UNION ALL SELECT 131
    UNION ALL SELECT 132 UNION ALL SELECT 133 UNION ALL SELECT 134 UNION ALL SELECT 135
    UNION ALL SELECT 136 UNION ALL SELECT 137 UNION ALL SELECT 138 UNION ALL SELECT 139
    UNION ALL SELECT 140 UNION ALL SELECT 141 UNION ALL SELECT 142 UNION ALL SELECT 143
    UNION ALL SELECT 144 UNION ALL SELECT 145 UNION ALL SELECT 146 UNION ALL SELECT 147
    UNION ALL SELECT 148 UNION ALL SELECT 149 UNION ALL SELECT 150 UNION ALL SELECT 151
    UNION ALL SELECT 152 UNION ALL SELECT 153 UNION ALL SELECT 154 UNION ALL SELECT 155
    UNION ALL SELECT 156 UNION ALL SELECT 157 UNION ALL SELECT 158 UNION ALL SELECT 159
    UNION ALL SELECT 160 UNION ALL SELECT 161 UNION ALL SELECT 162 UNION ALL SELECT 163
    UNION ALL SELECT 164 UNION ALL SELECT 165 UNION ALL SELECT 166 UNION ALL SELECT 167
    UNION ALL SELECT 168 UNION ALL SELECT 169 UNION ALL SELECT 170 UNION ALL SELECT 171
    UNION ALL SELECT 172 UNION ALL SELECT 173 UNION ALL SELECT 174 UNION ALL SELECT 175
    UNION ALL SELECT 176 UNION ALL SELECT 177 UNION ALL SELECT 178 UNION ALL SELECT 179
    UNION ALL SELECT 180 UNION ALL SELECT 181 UNION ALL SELECT 182 UNION ALL SELECT 183
    UNION ALL SELECT 184 UNION ALL SELECT 185 UNION ALL SELECT 186 UNION ALL SELECT 187
    UNION ALL SELECT 188 UNION ALL SELECT 189 UNION ALL SELECT 190 UNION ALL SELECT 191
    UNION ALL SELECT 192 UNION ALL SELECT 193 UNION ALL SELECT 194 UNION ALL SELECT 195
    UNION ALL SELECT 196 UNION ALL SELECT 197 UNION ALL SELECT 198 UNION ALL SELECT 199
    UNION ALL SELECT 200 UNION ALL SELECT 201 UNION ALL SELECT 202 UNION ALL SELECT 203
    UNION ALL SELECT 204 UNION ALL SELECT 205 UNION ALL SELECT 206 UNION ALL SELECT 207
    UNION ALL SELECT 208 UNION ALL SELECT 209 UNION ALL SELECT 210 UNION ALL SELECT 211
    UNION ALL SELECT 212 UNION ALL SELECT 213 UNION ALL SELECT 214 UNION ALL SELECT 215
    UNION ALL SELECT 216 UNION ALL SELECT 217 UNION ALL SELECT 218 UNION ALL SELECT 219
    UNION ALL SELECT 220 UNION ALL SELECT 221 UNION ALL SELECT 222 UNION ALL SELECT 223
    UNION ALL SELECT 224 UNION ALL SELECT 225 UNION ALL SELECT 226 UNION ALL SELECT 227
    UNION ALL SELECT 228 UNION ALL SELECT 229 UNION ALL SELECT 230 UNION ALL SELECT 231
    UNION ALL SELECT 232 UNION ALL SELECT 233 UNION ALL SELECT 234 UNION ALL SELECT 235
    UNION ALL SELECT 236 UNION ALL SELECT 237 UNION ALL SELECT 238 UNION ALL SELECT 239
    UNION ALL SELECT 240 UNION ALL SELECT 241 UNION ALL SELECT 242 UNION ALL SELECT 243
    UNION ALL SELECT 244 UNION ALL SELECT 245 UNION ALL SELECT 246 UNION ALL SELECT 247
    UNION ALL SELECT 248 UNION ALL SELECT 249 UNION ALL SELECT 250 UNION ALL SELECT 251
    UNION ALL SELECT 252 UNION ALL SELECT 253 UNION ALL SELECT 254 UNION ALL SELECT 255
),

status_periods AS (
    SELECT
        us.useruuid,
        us.companyuuid,
        us.statusdate                                       AS period_start,
        COALESCE(
            (SELECT MIN(us2.statusdate)
             FROM userstatus us2
             WHERE us2.useruuid = us.useruuid
               AND us2.statusdate > us.statusdate),
            DATE_ADD(CURDATE(), INTERVAL 1 MONTH)
        )                                                   AS period_end
    FROM userstatus us
),

month_spine AS (
    SELECT DISTINCT
        sp.useruuid,
        sp.companyuuid,
        YEAR(DATE_ADD(sp.period_start, INTERVAL n.n MONTH))  AS year_num,
        MONTH(DATE_ADD(sp.period_start, INTERVAL n.n MONTH)) AS month_num
    FROM status_periods sp
    JOIN nums n
        ON DATE_ADD(sp.period_start, INTERVAL n.n MONTH) < sp.period_end
       AND DATE_ADD(sp.period_start, INTERVAL n.n MONTH) <= LAST_DAY(CURDATE())
       AND DATE_ADD(sp.period_start, INTERVAL n.n MONTH) >= DATE_SUB(CURDATE(), INTERVAL 120 MONTH)
),

status_max_date AS (
    SELECT
        ms.useruuid,
        ms.companyuuid,
        ms.year_num,
        ms.month_num,
        LAST_DAY(STR_TO_DATE(CONCAT(ms.year_num, '-', ms.month_num, '-01'), '%Y-%c-%d'))
            AS month_end,
        STR_TO_DATE(CONCAT(ms.year_num, '-', ms.month_num, '-01'), '%Y-%c-%d')
            AS month_start,
        MAX(us.statusdate) AS max_statusdate
    FROM month_spine ms
    JOIN userstatus us
        ON  us.useruuid  = ms.useruuid
        AND us.statusdate <= LAST_DAY(STR_TO_DATE(CONCAT(ms.year_num, '-', ms.month_num, '-01'), '%Y-%c-%d'))
    GROUP BY ms.useruuid, ms.companyuuid, ms.year_num, ms.month_num
),

latest_status AS (
    SELECT
        smd.useruuid,
        smd.companyuuid,
        smd.year_num,
        smd.month_num,
        smd.month_end,
        smd.month_start,
        us.status           AS employee_status,
        us.type             AS employee_type,
        us.companyuuid      AS status_companyuuid
    FROM status_max_date smd
    JOIN userstatus us
        ON  us.useruuid   = smd.useruuid
        AND us.statusdate = smd.max_statusdate
),

eligible_months AS (
    SELECT
        ls.useruuid,
        ls.companyuuid,
        ls.year_num,
        ls.month_num,
        ls.month_end,
        ls.month_start,
        ls.employee_status,
        ls.employee_type,
        CASE WHEN ls.employee_status = 'NON_PAY_LEAVE' THEN 1 ELSE 0 END
            AS is_leave_month
    FROM latest_status ls
    WHERE ls.employee_status NOT IN ('TERMINATED', 'PREBOARDING')
      AND ls.status_companyuuid = ls.companyuuid
),

salary_max_date AS (
    SELECT
        em.useruuid,
        em.month_end,
        MAX(s.activefrom) AS max_activefrom
    FROM eligible_months em
    JOIN salary s
        ON  s.useruuid   = em.useruuid
        AND s.activefrom <= em.month_end
    GROUP BY em.useruuid, em.month_end
),

latest_salary AS (
    SELECT
        smd.useruuid,
        smd.month_end,
        CASE
            WHEN s.useruuid IN (
                '8fa7f75a-57bf-4c6f-8db7-7e16067c1bcd',
                '7948c5e8-162c-4053-b905-0f59a21d7746',
                'ca0e1027-061f-49e7-b66a-a487c815f5a0'
            ) THEN 150000.0
            ELSE CAST(s.salary AS DOUBLE)
        END                             AS effective_salary,
        CAST(s.salary AS DOUBLE)        AS db_salary,
        s.type                          AS salary_type
    FROM salary_max_date smd
    JOIN salary s
        ON  s.useruuid   = smd.useruuid
        AND s.activefrom = smd.max_activefrom
),

pension_max_date AS (
    SELECT
        em.useruuid,
        em.month_end,
        MAX(up.active_date) AS max_active_date
    FROM eligible_months em
    LEFT JOIN user_pension up
        ON  up.useruuid    = em.useruuid
        AND up.active_date <= em.month_end
    GROUP BY em.useruuid, em.month_end
),

latest_pension AS (
    SELECT
        pmd.useruuid,
        pmd.month_end,
        COALESCE(up.pension_own, 0)     AS pension_own_pct,
        COALESCE(up.pension_company, 0) AS pension_company_pct
    FROM pension_max_date pmd
    LEFT JOIN user_pension up
        ON  up.useruuid    = pmd.useruuid
        AND up.active_date = pmd.max_active_date
),

active_supplements AS (
    SELECT
        em.useruuid,
        em.month_end,
        COALESCE(SUM(ss.value), 0.0) AS supplements
    FROM eligible_months em
    LEFT JOIN salary_supplement ss
        ON  ss.useruuid    = em.useruuid
        AND ss.from_month <= em.month_end
        AND (ss.to_month >= em.month_start OR ss.to_month IS NULL)
    GROUP BY em.useruuid, em.month_end
),

monthly_lump_sums AS (
    SELECT
        em.useruuid,
        em.month_end,
        COALESCE(SUM(ls.lump_sum), 0.0) AS lump_sums
    FROM eligible_months em
    LEFT JOIN salary_lump_sum ls
        ON  ls.useruuid = em.useruuid
        AND ls.month   >= em.month_start
        AND ls.month   <= em.month_end
    GROUP BY em.useruuid, em.month_end
),

hourly_hours AS (
    SELECT
        em.useruuid,
        em.month_end,
        COALESCE(SUM(w.workduration), 0.0) AS hourly_hours
    FROM eligible_months em
    LEFT JOIN work w
        ON  w.useruuid = em.useruuid
        AND w.taskuuid = 'a7314f77-5e03-4f56-8b1c-0562e601f22f'
        AND w.paid_out >= em.month_start
        AND w.paid_out <  DATE_ADD(em.month_start, INTERVAL 1 MONTH)
    GROUP BY em.useruuid, em.month_end
)

SELECT
    CONCAT(em.useruuid, '-', em.companyuuid, '-',
           LPAD(em.year_num, 4, '0'), LPAD(em.month_num, 2, '0'))
                                                        AS salary_monthly_id,

    em.useruuid,
    em.companyuuid,
    u.practice                                          AS practice_id,

    lsal.effective_salary,
    lsal.db_salary,
    lsal.salary_type,

    lpen.pension_own_pct,
    lpen.pension_company_pct,
    ROUND(
        lsal.effective_salary
        * (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0,
        2
    )                                                   AS pension_deduction,

    ROUND(lsal.effective_salary * 0.0045, 2)            AS bededag,

    ROUND(asup.supplements, 2)                          AS supplements,
    ROUND(mlump.lump_sums, 2)                           AS lump_sums,
    ROUND(hh.hourly_hours, 4)                           AS hourly_hours,

    CASE
        WHEN lsal.salary_type = 'HOURLY'
            THEN ROUND(hh.hourly_hours * lsal.effective_salary, 2)
        ELSE 0.0
    END                                                 AS base_pay,

    CASE
        WHEN lsal.salary_type = 'NORMAL' THEN
            ROUND(
                lsal.effective_salary
                * (1.0
                   - (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0
                   + 0.0045)
                + asup.supplements
                + mlump.lump_sums,
                2
            )
        WHEN lsal.salary_type = 'HOURLY' THEN
            ROUND(
                (hh.hourly_hours * lsal.effective_salary) * 1.0045
                - (hh.hourly_hours * lsal.effective_salary
                   * (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0),
                2
            )
        ELSE 0.0
    END                                                 AS salary_sum,

    em.employee_status,
    em.employee_type,
    em.is_leave_month,

    CONCAT(LPAD(em.year_num, 4, '0'), LPAD(em.month_num, 2, '0'))
                                                        AS month_key,
    CAST(em.year_num AS SIGNED)                         AS year,
    CAST(em.month_num AS SIGNED)                        AS month_number,

    CASE
        WHEN em.month_num >= 7 THEN CAST(em.year_num AS SIGNED)
        ELSE CAST(em.year_num - 1 AS SIGNED)
    END                                                 AS fiscal_year,

    CASE
        WHEN em.month_num >= 7 THEN em.month_num - 6
        ELSE em.month_num + 6
    END                                                 AS fiscal_month_number,

    'SALARY_DB_CALC'                                    AS data_source

FROM eligible_months em

JOIN user u
    ON u.uuid = em.useruuid

JOIN latest_salary lsal
    ON  lsal.useruuid   = em.useruuid
    AND lsal.month_end  = em.month_end

LEFT JOIN latest_pension lpen
    ON  lpen.useruuid  = em.useruuid
    AND lpen.month_end = em.month_end

LEFT JOIN active_supplements asup
    ON  asup.useruuid  = em.useruuid
    AND asup.month_end = em.month_end

LEFT JOIN monthly_lump_sums mlump
    ON  mlump.useruuid  = em.useruuid
    AND mlump.month_end = em.month_end

LEFT JOIN hourly_hours hh
    ON  hh.useruuid  = em.useruuid
    AND hh.month_end = em.month_end

WHERE NOT (em.employee_type = 'EXTERNAL' AND lsal.db_salary = 0)

AND NOT (lsal.salary_type = 'HOURLY'
         AND hh.hourly_hours = 0
         AND asup.supplements = 0
         AND mlump.lump_sums  = 0);


-- =============================================================================
-- 7. fact_salary_monthly_teamroles (companion view — no change needed,
--    but recreated to remain in sync with the primary view definition above)
--    Source: V210
--    Change: none — this view reads from fact_salary_monthly, not user directly
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_salary_monthly_teamroles` AS

SELECT
    CONCAT(fsm.useruuid, '-', fsm.companyuuid, '-', fsm.month_key, '-', tr.teamuuid)
                                                        AS salary_team_id,

    fsm.salary_monthly_id,
    fsm.useruuid,
    fsm.companyuuid,
    tr.teamuuid,
    fsm.practice_id,
    fsm.effective_salary,
    fsm.db_salary,
    fsm.salary_type,
    fsm.pension_own_pct,
    fsm.pension_company_pct,
    fsm.pension_deduction,
    fsm.bededag,
    fsm.supplements,
    fsm.lump_sums,
    fsm.hourly_hours,
    fsm.base_pay,
    fsm.salary_sum,
    fsm.employee_status,
    fsm.employee_type,
    fsm.is_leave_month,
    fsm.month_key,
    fsm.year,
    fsm.month_number,
    fsm.fiscal_year,
    fsm.fiscal_month_number,
    fsm.data_source

FROM fact_salary_monthly fsm

JOIN teamroles tr
    ON  tr.useruuid    = fsm.useruuid
    AND tr.membertype  = 'MEMBER'
    AND tr.startdate  <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.year, '-', fsm.month_number, '-01'), '%Y-%c-%d'))
    AND (tr.enddate IS NULL
         OR tr.enddate > STR_TO_DATE(CONCAT(fsm.year, '-', fsm.month_number, '-01'), '%Y-%c-%d'));
