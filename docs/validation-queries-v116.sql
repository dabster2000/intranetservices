-- Validation Queries for V116 Migration
-- Run these after deploying V116__Fix_fact_project_financials_gross_margin.sql

-- ============================================================================
-- QUERY 1: Verify view structure and new cost breakdown columns
-- ============================================================================
DESCRIBE fact_project_financials;

-- Expected new columns:
-- - employee_salary_cost_dkk
-- - external_consultant_cost_dkk
-- - project_expense_cost_dkk
-- - direct_delivery_cost_dkk (should equal sum of above three)


-- ============================================================================
-- QUERY 2: Check temporal salary join works correctly
-- Compare old (any salary) vs new (temporal salary) for sample users
-- ============================================================================
SELECT
    w.useruuid,
    u.firstname,
    u.lastname,
    DATE_FORMAT(w.registered, '%Y-%m') as work_month,
    w.registered as work_date,

    -- NEW: Temporal salary (as of work date)
    (SELECT s.salary
     FROM salary s
     WHERE s.useruuid = w.useruuid
       AND s.activefrom <= w.registered
     ORDER BY s.activefrom DESC
     LIMIT 1) as temporal_salary,

    -- OLD: First salary found (wrong!)
    (SELECT s.salary
     FROM salary s
     WHERE s.useruuid = w.useruuid
     LIMIT 1) as first_salary_found,

    -- Count how many salary records exist for this user
    (SELECT COUNT(*)
     FROM salary s
     WHERE s.useruuid = w.useruuid) as salary_record_count

FROM work w
INNER JOIN user u ON w.useruuid = u.uuid
WHERE w.registered >= '2024-01-01'
  AND w.useruuid IN (
      -- Users with multiple salary records (temporal join matters)
      SELECT useruuid
      FROM salary
      GROUP BY useruuid
      HAVING COUNT(*) > 1
      LIMIT 5
  )
ORDER BY w.useruuid, w.registered
LIMIT 20;

-- Expected: temporal_salary changes over time as salary increases
-- Expected: first_salary_found stays constant (wrong approach)


-- ============================================================================
-- QUERY 3: Cost breakdown validation - Verify sum equals total
-- ============================================================================
SELECT
    month_key,
    year,
    month_number,
    SUM(employee_salary_cost_dkk) as total_employee_costs,
    SUM(external_consultant_cost_dkk) as total_external_costs,
    SUM(project_expense_cost_dkk) as total_expenses,
    SUM(direct_delivery_cost_dkk) as total_costs,

    -- Verification: sum of parts should equal total
    SUM(employee_salary_cost_dkk + external_consultant_cost_dkk + project_expense_cost_dkk) as sum_of_parts,

    -- Difference (should be 0 or very close to 0)
    SUM(direct_delivery_cost_dkk) -
    SUM(employee_salary_cost_dkk + external_consultant_cost_dkk + project_expense_cost_dkk) as difference

FROM fact_project_financials
WHERE year >= 2024
GROUP BY month_key, year, month_number
ORDER BY year DESC, month_number DESC;

-- Expected: difference column should be 0.00 for all rows


-- ============================================================================
-- QUERY 4: Gross Margin Comparison - Before vs After (estimated)
-- ============================================================================
SELECT
    year,
    month_number,
    SUM(recognized_revenue_dkk) as revenue,

    -- NEW: Total costs (all sources)
    SUM(direct_delivery_cost_dkk) as total_costs_new,

    -- OLD: Employee salary only (from work_cost_aggregation)
    SUM(employee_salary_cost_dkk) as employee_costs_only,

    -- NEW: Gross margin
    SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk) as gross_margin_new,

    -- OLD: Gross margin (employee costs only)
    SUM(recognized_revenue_dkk) - SUM(employee_salary_cost_dkk) as gross_margin_old,

    -- NEW: Margin %
    ROUND(
        (SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk)) /
        NULLIF(SUM(recognized_revenue_dkk), 0) * 100,
        2
    ) as margin_pct_new,

    -- OLD: Margin % (employee costs only)
    ROUND(
        (SUM(recognized_revenue_dkk) - SUM(employee_salary_cost_dkk)) /
        NULLIF(SUM(recognized_revenue_dkk), 0) * 100,
        2
    ) as margin_pct_old,

    -- Difference in margin %
    ROUND(
        ((SUM(recognized_revenue_dkk) - SUM(employee_salary_cost_dkk)) /
         NULLIF(SUM(recognized_revenue_dkk), 0) * 100) -
        ((SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk)) /
         NULLIF(SUM(recognized_revenue_dkk), 0) * 100),
        2
    ) as margin_pct_difference

FROM fact_project_financials
WHERE year = 2025
GROUP BY year, month_number
ORDER BY year DESC, month_number DESC;

-- Expected: margin_pct_new should be LOWER than margin_pct_old
-- Expected: margin_pct_difference should be positive (showing we were over-estimating margin)


-- ============================================================================
-- QUERY 5: External consultant detection - Verify EXTERNAL users included
-- ============================================================================
SELECT
    year,
    month_number,
    COUNT(DISTINCT project_id) as projects_with_external_costs,
    SUM(external_consultant_cost_dkk) as total_external_costs,
    SUM(employee_salary_cost_dkk) as total_employee_costs,
    ROUND(
        SUM(external_consultant_cost_dkk) /
        NULLIF(SUM(employee_salary_cost_dkk + external_consultant_cost_dkk), 0) * 100,
        2
    ) as external_cost_pct
FROM fact_project_financials
WHERE external_consultant_cost_dkk > 0
  AND year >= 2024
GROUP BY year, month_number
ORDER BY year DESC, month_number DESC;

-- Expected: If you have external consultants, this should show data
-- Expected: If external_cost_pct > 0, external consultants are now included


-- ============================================================================
-- QUERY 6: Project expense detection - Verify expenses included
-- ============================================================================
SELECT
    year,
    month_number,
    COUNT(DISTINCT project_id) as projects_with_expenses,
    SUM(project_expense_cost_dkk) as total_expenses,
    SUM(direct_delivery_cost_dkk) as total_costs,
    ROUND(
        SUM(project_expense_cost_dkk) /
        NULLIF(SUM(direct_delivery_cost_dkk), 0) * 100,
        2
    ) as expense_cost_pct
FROM fact_project_financials
WHERE project_expense_cost_dkk > 0
  AND year >= 2024
GROUP BY year, month_number
ORDER BY year DESC, month_number DESC;

-- Expected: If you have approved expenses, this should show data
-- Expected: expense_cost_pct shows what % of costs are from expenses


-- ============================================================================
-- QUERY 7: Sample project drill-down - Verify all cost types
-- ============================================================================
SELECT
    f.project_id,
    p.name as project_name,
    f.month_key,
    f.recognized_revenue_dkk,
    f.employee_salary_cost_dkk,
    f.external_consultant_cost_dkk,
    f.project_expense_cost_dkk,
    f.direct_delivery_cost_dkk,

    -- Verify sum
    (f.employee_salary_cost_dkk + f.external_consultant_cost_dkk + f.project_expense_cost_dkk) as sum_check,

    -- Gross margin
    ROUND(
        (f.recognized_revenue_dkk - f.direct_delivery_cost_dkk) /
        NULLIF(f.recognized_revenue_dkk, 0) * 100,
        2
    ) as margin_pct

FROM fact_project_financials f
INNER JOIN project p ON f.project_id = p.uuid
WHERE f.year = 2025
  AND f.month_number = (SELECT MAX(month_number) FROM fact_project_financials WHERE year = 2025)
  AND f.recognized_revenue_dkk > 0
ORDER BY f.recognized_revenue_dkk DESC
LIMIT 20;

-- Expected: Top revenue projects should show breakdown of costs
-- Expected: sum_check should equal direct_delivery_cost_dkk


-- ============================================================================
-- QUERY 8: Hours divisor verification (160.33 vs 160.0)
-- ============================================================================
-- This shows the impact of fixing the divisor from 160.0 to 160.33

SELECT
    'Impact of 160.0 → 160.33 fix' as description,
    SUM(employee_salary_cost_dkk) as employee_costs_with_160_33,
    SUM(employee_salary_cost_dkk * (160.33 / 160.0)) as would_be_with_160_0,
    SUM(employee_salary_cost_dkk) - SUM(employee_salary_cost_dkk * (160.33 / 160.0)) as cost_increase,
    ROUND(
        (SUM(employee_salary_cost_dkk) - SUM(employee_salary_cost_dkk * (160.33 / 160.0))) /
        NULLIF(SUM(employee_salary_cost_dkk * (160.33 / 160.0)), 0) * 100,
        3
    ) as increase_pct
FROM fact_project_financials
WHERE year = 2025;

-- Expected: increase_pct should be approximately 0.206% (160.33/160.0 - 1)


-- ============================================================================
-- QUERY 9: Identify projects with only expenses (no work entries)
-- ============================================================================
-- These projects would have been missing from the old view

SELECT
    f.project_id,
    p.name as project_name,
    f.month_key,
    f.employee_salary_cost_dkk,
    f.external_consultant_cost_dkk,
    f.project_expense_cost_dkk,
    f.total_hours
FROM fact_project_financials f
INNER JOIN project p ON f.project_id = p.uuid
WHERE f.project_expense_cost_dkk > 0
  AND f.total_hours = 0
  AND f.year >= 2024
ORDER BY f.project_expense_cost_dkk DESC
LIMIT 20;

-- Expected: Projects with expenses but no work hours
-- Expected: These were missing from old view (no work entries = no project_months row)


-- ============================================================================
-- QUERY 10: Overall system health check
-- ============================================================================
SELECT
    'Overall Stats' as metric,
    COUNT(DISTINCT project_id) as total_projects,
    COUNT(*) as total_project_months,
    SUM(CASE WHEN recognized_revenue_dkk > 0 THEN 1 ELSE 0 END) as months_with_revenue,
    SUM(CASE WHEN direct_delivery_cost_dkk > 0 THEN 1 ELSE 0 END) as months_with_costs,
    SUM(CASE WHEN employee_salary_cost_dkk > 0 THEN 1 ELSE 0 END) as months_with_employee_costs,
    SUM(CASE WHEN external_consultant_cost_dkk > 0 THEN 1 ELSE 0 END) as months_with_external_costs,
    SUM(CASE WHEN project_expense_cost_dkk > 0 THEN 1 ELSE 0 END) as months_with_expenses,
    ROUND(SUM(recognized_revenue_dkk) / 1000000, 2) as total_revenue_millions,
    ROUND(SUM(direct_delivery_cost_dkk) / 1000000, 2) as total_costs_millions,
    ROUND(
        (SUM(recognized_revenue_dkk) - SUM(direct_delivery_cost_dkk)) /
        NULLIF(SUM(recognized_revenue_dkk), 0) * 100,
        2
    ) as overall_margin_pct
FROM fact_project_financials
WHERE year >= 2024;

-- Expected: Reasonable counts and totals
-- Expected: overall_margin_pct should be realistic (typically 20-50%)
