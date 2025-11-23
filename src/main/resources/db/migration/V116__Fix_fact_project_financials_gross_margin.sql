-- Migration V116: Fix fact_project_financials Gross Margin Calculation
--
-- CRITICAL FIXES:
-- 1. Add temporal salary join (get salary as of work date, not random salary)
-- 2. Fix hours divisor from 160.0 to 160.33 (correct month normalization)
-- 3. Add external consultant costs (HOURLY contractors)
-- 4. Add project expenses (travel, materials, subcontractors from expenses table)
-- 5. Combine all cost sources into total_cost_dkk
-- 6. Add cost breakdown columns for transparency
--
-- IMPACT:
-- - Before: Gross margin artificially inflated, using wrong historical data
-- - After: Accurate gross margin including all delivery costs
--
-- Author: Claude Code
-- Date: 2025-11-23

DROP VIEW IF EXISTS fact_project_financials;

CREATE OR REPLACE VIEW fact_project_financials AS

WITH project_months AS (
    -- Step 1: Get all distinct project-month combinations from work entries
    -- CRITICAL: Handle both old path (work.projectuuid) and new path (work→task→project)
    -- Pre-2024: work.projectuuid populated directly
    -- Post-2024: work.projectuuid NULL, must use task.projectuuid
    SELECT DISTINCT
        COALESCE(w.projectuuid, t.projectuuid) as project_uuid,
        YEAR(w.registered) as year_val,
        MONTH(w.registered) as month_val
    FROM work w
    LEFT JOIN task t ON w.taskuuid = t.uuid
    LEFT JOIN project p ON COALESCE(w.projectuuid, t.projectuuid) = p.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND p.uuid IS NOT NULL
      AND w.registered IS NOT NULL
      AND w.workduration > 0
),

customer_revenue AS (
    -- External customer revenue (gross)
    SELECT
        p.uuid as project_uuid,
        YEAR(i.invoicedate) as year_val,
        MONTH(i.invoicedate) as month_val,
        SUM(
            ii.rate * ii.hours *
            CASE
                WHEN i.currency = 'DKK' THEN 1
                ELSE c.conversion
            END
        ) as total_revenue_dkk,
        COUNT(DISTINCT i.uuid) as invoice_count,
        'CUSTOMER' as revenue_type
    FROM project p
    INNER JOIN invoices i ON p.uuid = i.projectuuid
    INNER JOIN invoiceitems ii ON i.uuid = ii.invoiceuuid
    LEFT JOIN currences c ON c.currency = i.currency AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m')
    WHERE i.status = 'CREATED'
      AND i.type IN ('INVOICE', 'PHANTOM')
      AND ii.rate IS NOT NULL
      AND ii.hours IS NOT NULL
      AND (i.currency = 'DKK' OR c.uuid IS NOT NULL)
    GROUP BY p.uuid, YEAR(i.invoicedate), MONTH(i.invoicedate)
),

credit_notes AS (
    -- Customer refunds/corrections (negative)
    SELECT
        p.uuid as project_uuid,
        YEAR(i.invoicedate) as year_val,
        MONTH(i.invoicedate) as month_val,
        -SUM(
            ii.rate * ii.hours *
            CASE
                WHEN i.currency = 'DKK' THEN 1
                ELSE c.conversion
            END
        ) as total_revenue_dkk,
        COUNT(DISTINCT i.uuid) as invoice_count,
        'CREDIT_NOTE' as revenue_type
    FROM project p
    INNER JOIN invoices i ON p.uuid = i.projectuuid
    INNER JOIN invoiceitems ii ON i.uuid = ii.invoiceuuid
    LEFT JOIN currences c ON c.currency = i.currency AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m')
    WHERE i.status = 'CREATED'
      AND i.type = 'CREDIT_NOTE'
      AND ii.rate IS NOT NULL
      AND ii.hours IS NOT NULL
      AND (i.currency = 'DKK' OR c.uuid IS NOT NULL)
    GROUP BY p.uuid, YEAR(i.invoicedate), MONTH(i.invoicedate)
),

revenue_aggregation AS (
    -- Combine for net customer revenue
    SELECT
        project_uuid,
        year_val,
        month_val,
        SUM(total_revenue_dkk) as total_revenue_dkk,
        SUM(invoice_count) as invoice_count
    FROM (
        SELECT * FROM customer_revenue
        UNION ALL
        SELECT * FROM credit_notes
    ) combined
    GROUP BY project_uuid, year_val, month_val
),

work_cost_aggregation AS (
    -- FIX #1: TEMPORAL SALARY JOIN - Get correct salary as of work date
    -- FIX #2: Correct hours divisor (160.33 not 160.0)
    -- Internal employee salary costs only
    SELECT
        COALESCE(w.projectuuid, t.projectuuid) as project_uuid,
        YEAR(w.registered) as year_val,
        MONTH(w.registered) as month_val,
        SUM(
            w.workduration * (
                COALESCE(
                    (SELECT s.salary
                     FROM salary s
                     WHERE s.useruuid = w.useruuid
                       AND s.activefrom <= w.registered
                     ORDER BY s.activefrom DESC
                     LIMIT 1),
                    0
                ) / 160.33  -- FIX #2: Corrected from 160.0 to 160.33
            )
        ) as employee_salary_cost_dkk,
        SUM(w.workduration) as total_hours,
        COUNT(DISTINCT w.useruuid) as consultant_count
    FROM work w
    LEFT JOIN task t ON w.taskuuid = t.uuid
    INNER JOIN user u ON w.useruuid = u.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND u.type = 'USER'  -- Internal employees only
      AND w.workduration > 0
      AND w.registered IS NOT NULL
    GROUP BY
        COALESCE(w.projectuuid, t.projectuuid),
        YEAR(w.registered),
        MONTH(w.registered)
),

external_consultant_cost_aggregation AS (
    -- FIX #3: ADD EXTERNAL CONSULTANT COSTS
    -- External consultants billed at hourly rates
    SELECT
        COALESCE(w.projectuuid, t.projectuuid) as project_uuid,
        YEAR(w.registered) as year_val,
        MONTH(w.registered) as month_val,
        SUM(
            w.workduration *
            COALESCE(
                (SELECT s.salary
                 FROM salary s
                 WHERE s.useruuid = w.useruuid
                   AND s.activefrom <= w.registered
                   AND s.type = 'HOURLY'
                 ORDER BY s.activefrom DESC
                 LIMIT 1),
                0
            )
        ) as external_cost_dkk
    FROM work w
    LEFT JOIN task t ON w.taskuuid = t.uuid
    INNER JOIN user u ON w.useruuid = u.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND u.type IN ('EXTERNAL', 'CONSULTANT')  -- External consultants only
      AND w.workduration > 0
      AND w.registered IS NOT NULL
    GROUP BY
        COALESCE(w.projectuuid, t.projectuuid),
        YEAR(w.registered),
        MONTH(w.registered)
),

project_expense_aggregation AS (
    -- FIX #4: ADD PROJECT EXPENSES
    -- Travel, materials, subcontractors from expenses table
    SELECT
        e.projectuuid as project_uuid,
        YEAR(e.expensedate) as year_val,
        MONTH(e.expensedate) as month_val,
        SUM(e.amount) as expense_cost_dkk
    FROM expenses e
    WHERE e.projectuuid IS NOT NULL
      AND e.status IN ('VERIFIED_BOOKED', 'VERIFIED_UNBOOKED')  -- Approved expenses only
      AND e.expensedate IS NOT NULL
    GROUP BY
        e.projectuuid,
        YEAR(e.expensedate),
        MONTH(e.expensedate)
),

total_cost_aggregation AS (
    -- FIX #5: COMBINE ALL COST SOURCES
    -- Employee salaries + External consultants + Project expenses
    SELECT
        COALESCE(wca.project_uuid, eca.project_uuid, pea.project_uuid) as project_uuid,
        COALESCE(wca.year_val, eca.year_val, pea.year_val) as year_val,
        COALESCE(wca.month_val, eca.month_val, pea.month_val) as month_val,
        COALESCE(wca.employee_salary_cost_dkk, 0) as employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) as external_cost_dkk,
        COALESCE(pea.expense_cost_dkk, 0) as expense_cost_dkk,
        (
            COALESCE(wca.employee_salary_cost_dkk, 0) +
            COALESCE(eca.external_cost_dkk, 0) +
            COALESCE(pea.expense_cost_dkk, 0)
        ) as total_cost_dkk,
        COALESCE(wca.total_hours, 0) as total_hours,
        COALESCE(wca.consultant_count, 0) as consultant_count
    FROM work_cost_aggregation wca
    LEFT JOIN external_consultant_cost_aggregation eca
        ON wca.project_uuid = eca.project_uuid
        AND wca.year_val = eca.year_val
        AND wca.month_val = eca.month_val
    LEFT JOIN project_expense_aggregation pea
        ON wca.project_uuid = pea.project_uuid
        AND wca.year_val = pea.year_val
        AND wca.month_val = pea.month_val

    UNION

    -- Include months with only external costs (no internal employees)
    SELECT
        COALESCE(eca.project_uuid, pea.project_uuid) as project_uuid,
        COALESCE(eca.year_val, pea.year_val) as year_val,
        COALESCE(eca.month_val, pea.month_val) as month_val,
        0 as employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) as external_cost_dkk,
        COALESCE(pea.expense_cost_dkk, 0) as expense_cost_dkk,
        (
            COALESCE(eca.external_cost_dkk, 0) +
            COALESCE(pea.expense_cost_dkk, 0)
        ) as total_cost_dkk,
        0 as total_hours,
        0 as consultant_count
    FROM external_consultant_cost_aggregation eca
    LEFT JOIN project_expense_aggregation pea
        ON eca.project_uuid = pea.project_uuid
        AND eca.year_val = pea.year_val
        AND eca.month_val = pea.month_val
    WHERE NOT EXISTS (
        SELECT 1 FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = eca.project_uuid
          AND wca2.year_val = eca.year_val
          AND wca2.month_val = eca.month_val
    )

    UNION

    -- Include months with only expenses (no work entries)
    SELECT
        pea.project_uuid,
        pea.year_val,
        pea.month_val,
        0 as employee_salary_cost_dkk,
        0 as external_cost_dkk,
        pea.expense_cost_dkk,
        pea.expense_cost_dkk as total_cost_dkk,
        0 as total_hours,
        0 as consultant_count
    FROM project_expense_aggregation pea
    WHERE NOT EXISTS (
        SELECT 1 FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = pea.project_uuid
          AND wca2.year_val = pea.year_val
          AND wca2.month_val = pea.month_val
    )
    AND NOT EXISTS (
        SELECT 1 FROM external_consultant_cost_aggregation eca2
        WHERE eca2.project_uuid = pea.project_uuid
          AND eca2.year_val = pea.year_val
          AND eca2.month_val = pea.month_val
    )
),

service_line_ranking AS (
    -- Step 4: Rank service lines by total work hours per project-month
    -- Determines which skill type (PM, BA, SA, DEV, CYB) had most hours
    -- CRITICAL: Handle both old path (work.projectuuid) and new path (work→task→project)
    SELECT
        COALESCE(w.projectuuid, t.projectuuid) as project_uuid,
        YEAR(w.registered) as year_val,
        MONTH(w.registered) as month_val,
        u.primaryskilltype,
        SUM(w.workduration) as hours_by_skilltype,
        ROW_NUMBER() OVER (
            PARTITION BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered)
            ORDER BY SUM(w.workduration) DESC, COUNT(DISTINCT w.useruuid) DESC
        ) as skill_rank
    FROM work w
    LEFT JOIN task t ON w.taskuuid = t.uuid
    INNER JOIN user u ON w.useruuid = u.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND u.type = 'USER'
      AND u.primaryskilltype IS NOT NULL
      AND w.workduration > 0
    GROUP BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered), u.primaryskilltype
),

dominant_service_line AS (
    -- Step 5: Extract only the dominant (rank #1) service line per project-month
    SELECT
        project_uuid,
        year_val,
        month_val,
        primaryskilltype as dominant_skilltype
    FROM service_line_ranking
    WHERE skill_rank = 1
),

project_contract_types AS (
    -- Step 6: Get contract types from contracts table via contract_project junction
    -- CRITICAL FIX: Use authoritative contracts table, not invoices.contract_type
    -- Ranks by frequency (count of contracts) per project
    SELECT
        p.uuid as project_uuid,
        c.contracttype,
        COUNT(*) as contract_count,
        ROW_NUMBER() OVER (
            PARTITION BY p.uuid
            ORDER BY COUNT(*) DESC
        ) as rank_num
    FROM project p
    INNER JOIN contract_project cp ON p.uuid = cp.projectuuid
    INNER JOIN contracts c ON cp.contractuuid = c.uuid
    WHERE c.contracttype IS NOT NULL
      AND c.status IN ('SIGNED', 'TIME', 'BUDGET')
    GROUP BY p.uuid, c.contracttype
),

primary_contract_type AS (
    -- Step 7: Keep only the most common contract type per project
    SELECT project_uuid, contracttype
    FROM project_contract_types
    WHERE rank_num = 1
)

-- Step 8: Final aggregation - combine all dimensions and metrics
-- FIX #6: Add cost breakdown columns for transparency
SELECT
    CONCAT(pm.project_uuid, '-', CONCAT(LPAD(pm.year_val, 4, '0'), LPAD(pm.month_val, 2, '0'))) as project_financial_id,
    pm.project_uuid as project_id,
    p.clientuuid as client_id,
    COALESCE(c.segment, 'OTHER') as sector_id,
    COALESCE(dsl.dominant_skilltype, 'UNKNOWN') as service_line_id,
    COALESCE(pct.contracttype, 'PERIOD') as contract_type_id,
    CONCAT(LPAD(pm.year_val, 4, '0'), LPAD(pm.month_val, 2, '0')) as month_key,
    pm.year_val as year,
    pm.month_val as month_number,

    -- Revenue metric
    COALESCE(ra.total_revenue_dkk, 0) as recognized_revenue_dkk,

    -- NEW: Cost breakdown by type
    COALESCE(tca.employee_salary_cost_dkk, 0) as employee_salary_cost_dkk,
    COALESCE(tca.external_cost_dkk, 0) as external_consultant_cost_dkk,
    COALESCE(tca.expense_cost_dkk, 0) as project_expense_cost_dkk,

    -- Total cost metric (sum of above)
    COALESCE(tca.total_cost_dkk, 0) as direct_delivery_cost_dkk,

    -- Work metrics
    COALESCE(tca.total_hours, 0) as total_hours,
    COALESCE(tca.consultant_count, 0) as consultant_count,

    'OPERATIONAL' as data_source
FROM project_months pm
INNER JOIN project p ON pm.project_uuid = p.uuid
LEFT JOIN client c ON p.clientuuid = c.uuid
LEFT JOIN revenue_aggregation ra
    ON pm.project_uuid = ra.project_uuid
    AND pm.year_val = ra.year_val
    AND pm.month_val = ra.month_val
LEFT JOIN total_cost_aggregation tca
    ON pm.project_uuid = tca.project_uuid
    AND pm.year_val = tca.year_val
    AND pm.month_val = tca.month_val
LEFT JOIN dominant_service_line dsl
    ON pm.project_uuid = dsl.project_uuid
    AND pm.year_val = dsl.year_val
    AND pm.month_val = dsl.month_val
LEFT JOIN primary_contract_type pct
    ON pm.project_uuid = pct.project_uuid
ORDER BY pm.year_val DESC, pm.month_val DESC, pm.project_uuid;
