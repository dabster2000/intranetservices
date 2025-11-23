-- Validation Queries for V117 Cost Duplication Fix
-- Run these after deploying V117_Update_fact_project_financials_companyuuid.sql

-- ============================================================================
-- QUERY 1: Verify costs are split by company (no duplication)
-- ============================================================================
-- This shows revenue and costs broken down by company for recent months
SELECT
    year,
    month_number,
    companyuuid,
    COUNT(*) as project_count,
    ROUND(SUM(recognized_revenue_dkk), 2) as revenue_dkk,
    ROUND(SUM(employee_salary_cost_dkk), 2) as employee_costs_dkk,
    ROUND(SUM(external_consultant_cost_dkk), 2) as external_costs_dkk,
    ROUND(SUM(project_expense_cost_dkk), 2) as expense_costs_dkk,
    ROUND(SUM(direct_delivery_cost_dkk), 2) as total_costs_dkk,
    ROUND(
        (SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk)) /
        NULLIF(SUM(recognized_revenue_dkk), 0) * 100,
        2
    ) as margin_pct
FROM fact_project_financials
WHERE year = 2025
  AND month_number >= 10
GROUP BY year, month_number, companyuuid
ORDER BY year DESC, month_number DESC, companyuuid;

-- Expected: Each company shows different cost amounts (no duplication)


-- ============================================================================
-- QUERY 2: Verify monthly totals match sum of company breakdowns
-- ============================================================================
-- This ensures aggregation works correctly - monthly total should equal sum of companies

-- Monthly totals (what Chart A displays)
SELECT
    'MONTHLY_TOTAL' as level,
    year,
    month_number,
    NULL as companyuuid,
    ROUND(SUM(recognized_revenue_dkk), 2) as revenue_dkk,
    ROUND(SUM(direct_delivery_cost_dkk), 2) as cost_dkk,
    ROUND(
        (SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk)) /
        NULLIF(SUM(recognized_revenue_dkk), 0) * 100,
        2
    ) as margin_pct
FROM fact_project_financials
WHERE year = 2025 AND month_number = 11
GROUP BY year, month_number

UNION ALL

-- Company breakdown totals
SELECT
    'COMPANY_BREAKDOWN' as level,
    year,
    month_number,
    companyuuid,
    ROUND(SUM(recognized_revenue_dkk), 2) as revenue_dkk,
    ROUND(SUM(direct_delivery_cost_dkk), 2) as cost_dkk,
    ROUND(
        (SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk)) /
        NULLIF(SUM(recognized_revenue_dkk), 0) * 100,
        2
    ) as margin_pct
FROM fact_project_financials
WHERE year = 2025 AND month_number = 11
GROUP BY year, month_number, companyuuid

ORDER BY level DESC, companyuuid;

-- Expected: MONTHLY_TOTAL row should equal SUM of COMPANY_BREAKDOWN rows


-- ============================================================================
-- QUERY 3: Identify multi-company projects (potential cost split scenarios)
-- ============================================================================
-- Shows projects worked on by multiple companies in same month
SELECT
    project_id,
    p.name as project_name,
    year,
    month_number,
    COUNT(DISTINCT companyuuid) as company_count,
    GROUP_CONCAT(DISTINCT companyuuid ORDER BY companyuuid SEPARATOR ', ') as companies,
    ROUND(SUM(recognized_revenue_dkk), 2) as total_revenue,
    ROUND(SUM(direct_delivery_cost_dkk), 2) as total_cost,
    ROUND(SUM(total_hours), 2) as total_hours
FROM fact_project_financials f
JOIN project p ON f.project_id = p.uuid
WHERE year = 2025
GROUP BY project_id, p.name, year, month_number
HAVING company_count > 1
ORDER BY total_revenue DESC
LIMIT 20;

-- Expected: Projects with multiple companies should show split costs, not duplicated


-- ============================================================================
-- QUERY 4: Drill-down into specific multi-company project
-- ============================================================================
-- Replace PROJECT_UUID with actual UUID from Query 3 results
SET @project_uuid = 'REPLACE_WITH_ACTUAL_PROJECT_UUID';

SELECT
    f.companyuuid,
    c.name as company_name,
    f.year,
    f.month_number,
    ROUND(f.recognized_revenue_dkk, 2) as revenue,
    ROUND(f.employee_salary_cost_dkk, 2) as employee_costs,
    ROUND(f.external_consultant_cost_dkk, 2) as external_costs,
    ROUND(f.project_expense_cost_dkk, 2) as expenses,
    ROUND(f.direct_delivery_cost_dkk, 2) as total_costs,
    f.total_hours,
    f.consultant_count,
    ROUND(
        (f.recognized_revenue_dkk - f.direct_delivery_cost_dkk) /
        NULLIF(f.recognized_revenue_dkk, 0) * 100,
        2
    ) as margin_pct
FROM fact_project_financials f
LEFT JOIN company c ON f.companyuuid = c.uuid
WHERE f.project_id = @project_uuid
  AND f.year = 2025
  AND f.month_number = 11
ORDER BY f.companyuuid;

-- Expected: Each company row shows only ITS costs, not all project costs


-- ============================================================================
-- QUERY 5: Cost attribution verification - Check userstatus lookups work
-- ============================================================================
-- Verify that work entries are attributed to correct companies
SELECT
    u.firstname,
    u.lastname,
    DATE_FORMAT(w.registered, '%Y-%m') as work_month,
    -- Company from userstatus (what view uses)
    COALESCE(
        (SELECT us.companyuuid
         FROM userstatus us
         WHERE us.useruuid = w.useruuid
           AND us.statusdate <= w.registered
         ORDER BY us.statusdate DESC
         LIMIT 1),
        'd8894494-2fb4-4f72-9e05-e6032e6dd691'
    ) as attributed_company,
    c.name as company_name,
    COUNT(*) as work_entries,
    ROUND(SUM(w.workduration), 2) as total_hours
FROM work w
JOIN user u ON w.useruuid = u.uuid
LEFT JOIN company c ON c.uuid = COALESCE(
    (SELECT us.companyuuid
     FROM userstatus us
     WHERE us.useruuid = w.useruuid
       AND us.statusdate <= w.registered
     ORDER BY us.statusdate DESC
     LIMIT 1),
    'd8894494-2fb4-4f72-9e05-e6032e6dd691'
)
WHERE w.registered >= '2025-10-01'
  AND w.workduration > 0
GROUP BY u.uuid, u.firstname, u.lastname, DATE_FORMAT(w.registered, '%Y-%m'), attributed_company, c.name
ORDER BY work_month DESC, u.lastname, u.firstname
LIMIT 50;

-- Expected: Each user's work attributed to their company at time of work


-- ============================================================================
-- QUERY 6: Compare V116 vs V117 cost totals (if you have V116 data saved)
-- ============================================================================
-- This shows if costs changed after adding company dimension
-- NOTE: Run this BEFORE applying V117 to save V116 baseline

-- Save V116 baseline (run BEFORE V117):
-- CREATE TABLE v116_baseline AS
-- SELECT year, month_number,
--        SUM(recognized_revenue_dkk) as revenue,
--        SUM(direct_delivery_cost_dkk) as cost
-- FROM fact_project_financials
-- WHERE year = 2025
-- GROUP BY year, month_number;

-- After V117, compare:
-- SELECT
--     v116.year,
--     v116.month_number,
--     v116.revenue as v116_revenue,
--     v116.cost as v116_cost,
--     v117.revenue as v117_revenue,
--     v117.cost as v117_cost,
--     ROUND(v117.cost - v116.cost, 2) as cost_difference,
--     ROUND((v117.cost - v116.cost) / NULLIF(v116.cost, 0) * 100, 2) as cost_diff_pct
-- FROM v116_baseline v116
-- JOIN (
--     SELECT year, month_number,
--            SUM(recognized_revenue_dkk) as revenue,
--            SUM(direct_delivery_cost_dkk) as cost
--     FROM fact_project_financials
--     GROUP BY year, month_number
-- ) v117 ON v116.year = v117.year AND v116.month_number = v117.month_number
-- ORDER BY v116.year, v116.month_number;

-- Expected: Costs should be SAME (no duplication fixed = no change in totals)


-- ============================================================================
-- QUERY 7: Expense attribution verification
-- ============================================================================
-- Verify expenses attributed to correct companies
SELECT
    e.useruuid,
    u.firstname,
    u.lastname,
    DATE_FORMAT(e.expensedate, '%Y-%m') as expense_month,
    COALESCE(
        (SELECT us.companyuuid
         FROM userstatus us
         WHERE us.useruuid = e.useruuid
           AND us.statusdate <= e.expensedate
         ORDER BY us.statusdate DESC
         LIMIT 1),
        'd8894494-2fb4-4f72-9e05-e6032e6dd691'
    ) as attributed_company,
    c.name as company_name,
    COUNT(*) as expense_count,
    ROUND(SUM(e.amount), 2) as total_amount
FROM expenses e
JOIN user u ON e.useruuid = u.uuid
LEFT JOIN company c ON c.uuid = COALESCE(
    (SELECT us.companyuuid
     FROM userstatus us
     WHERE us.useruuid = e.useruuid
       AND us.statusdate <= e.expensedate
     ORDER BY us.statusdate DESC
     LIMIT 1),
    'd8894494-2fb4-4f72-9e05-e6032e6dd691'
)
WHERE e.expensedate >= '2025-10-01'
  AND e.status IN ('VERIFIED_BOOKED', 'VERIFIED_UNBOOKED')
  AND e.projectuuid IS NOT NULL
GROUP BY e.useruuid, u.firstname, u.lastname, DATE_FORMAT(e.expensedate, '%Y-%m'), attributed_company, c.name
ORDER BY expense_month DESC, u.lastname, u.firstname
LIMIT 50;

-- Expected: Each user's expenses attributed to their company at time of expense


-- ============================================================================
-- QUERY 8: Overall system health check after V117
-- ============================================================================
SELECT
    'V117 Health Check' as metric,
    COUNT(DISTINCT project_id) as total_projects,
    COUNT(DISTINCT companyuuid) as total_companies,
    COUNT(*) as total_rows,
    SUM(CASE WHEN recognized_revenue_dkk > 0 THEN 1 ELSE 0 END) as rows_with_revenue,
    SUM(CASE WHEN direct_delivery_cost_dkk > 0 THEN 1 ELSE 0 END) as rows_with_costs,
    SUM(CASE WHEN employee_salary_cost_dkk > 0 THEN 1 ELSE 0 END) as rows_with_employee_costs,
    SUM(CASE WHEN external_consultant_cost_dkk > 0 THEN 1 ELSE 0 END) as rows_with_external_costs,
    SUM(CASE WHEN project_expense_cost_dkk > 0 THEN 1 ELSE 0 END) as rows_with_expenses,
    ROUND(SUM(recognized_revenue_dkk) / 1000000, 2) as total_revenue_millions,
    ROUND(SUM(direct_delivery_cost_dkk) / 1000000, 2) as total_costs_millions,
    ROUND(
        (SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk)) /
        NULLIF(SUM(recognized_revenue_dkk), 0) * 100,
        2
    ) as overall_margin_pct
FROM fact_project_financials
WHERE year >= 2024;

-- Expected: Row count should be HIGHER than V116 (more rows due to company dimension)
-- Expected: Revenue and cost totals should be SAME as V116 (no duplication)


-- ============================================================================
-- QUERY 9: Verify no NULL companyuuids (data quality check)
-- ============================================================================
SELECT
    year,
    month_number,
    COUNT(*) as null_company_rows,
    SUM(recognized_revenue_dkk) as revenue,
    SUM(direct_delivery_cost_dkk) as cost
FROM fact_project_financials
WHERE companyuuid IS NULL
  AND year >= 2024
GROUP BY year, month_number
ORDER BY year DESC, month_number DESC;

-- Expected: ZERO rows (all should have companyuuid, defaulting to main company if needed)


-- ============================================================================
-- QUERY 10: Chart A backend query simulation (what service layer executes)
-- ============================================================================
-- This simulates CxoFinanceService.getRevenueMarginTrend()
SELECT
    f.month_key,
    f.year,
    f.month_number,
    ROUND(SUM(f.recognized_revenue_dkk), 2) AS revenue,
    ROUND(SUM(f.direct_delivery_cost_dkk), 2) AS cost,
    ROUND(
        (SUM(f.recognized_revenue_dkk) - SUM(f.direct_delivery_cost_dkk)) /
        NULLIF(SUM(f.recognized_revenue_dkk), 0) * 100,
        2
    ) AS margin_pct
FROM fact_project_financials f
WHERE f.month_key >= '202410'  -- Last 3 months
  AND f.month_key <= '202501'
GROUP BY f.year, f.month_number, f.month_key
ORDER BY f.year ASC, f.month_number ASC;

-- Expected: Accurate margin % (costs not duplicated across companies)
-- Expected: Results match frontend Chart A display
