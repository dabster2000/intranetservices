-- =============================================================================
-- Migration V126: Create fact_opex_budget view (STUB)
--
-- Purpose:
-- - Provide OPEX budget data structure for variance analysis
-- - Enable OPEX vs Budget (YTD) KPIs and expense detail reporting
-- - Support OPEX bridge charts with budget as baseline
--
-- Grain: company_id × cost_center_id × expense_category_id × month_key × budget_scenario
--
-- Current Status: STUB VIEW (empty result set with correct schema)
-- - Returns no data until budget source is implemented
-- - Ready for population via manual import or ERP integration
--
-- Data Population Options:
-- 1. Create opex_budget table and INSERT budget data manually
-- 2. Import from CSV/Excel via admin interface
-- 3. Integrate with e-conomic ERP budget API
-- 4. Connect to dedicated financial planning system
--
-- Migration History:
-- V125 (2025-11-27): Initial stub view creation
--
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_opex_budget` AS

WITH
    -- Month calendar for budget period structure (36 months)
    month_calendar AS (
        SELECT
            DATE_FORMAT(
                    DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                             INTERVAL n MONTH),
                    '%Y%m'
            ) AS month_key,
            YEAR(
                    DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                             INTERVAL n MONTH)
            ) AS year_val,
            MONTH(
                    DATE_SUB(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                             INTERVAL n MONTH)
            ) AS month_val
        FROM (
                 SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
                 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
                 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
                 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
                 UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23
                 UNION ALL SELECT 24 UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27
                 UNION ALL SELECT 28 UNION ALL SELECT 29 UNION ALL SELECT 30 UNION ALL SELECT 31
                 UNION ALL SELECT 32 UNION ALL SELECT 33 UNION ALL SELECT 34 UNION ALL SELECT 35
             ) months
    ),

    -- STUB: Budget data placeholder
    -- TODO: Replace with actual budget source table when available
    -- Example future implementation:
    --   SELECT * FROM opex_budget WHERE approved = TRUE
    -- OR
    --   SELECT * FROM erp_budget_export WHERE budget_type = 'OPEX'
    budget_source AS (
        SELECT
            NULL AS company_id,
            NULL AS cost_center_id,
            NULL AS expense_category_id,
            NULL AS expense_subcategory_id,
            NULL AS practice_id,
            NULL AS sector_id,
            NULL AS month_key,
            NULL AS budget_scenario,
            NULL AS budget_opex_dkk,
            NULL AS budget_headcount,
            NULL AS data_source
        WHERE 1 = 0  -- Returns empty result set
    )

SELECT
    -- Surrogate key
    CONCAT(
            bs.company_id, '-',
            bs.cost_center_id, '-',
            bs.expense_category_id, '-',
            bs.month_key, '-',
            bs.budget_scenario
    ) AS opex_budget_id,

    -- Dimension columns
    bs.company_id,
    bs.cost_center_id,
    bs.expense_category_id,
    bs.expense_subcategory_id,
    bs.practice_id,
    bs.sector_id,

    -- Time dimensions (calendar)
    bs.month_key,
    CAST(SUBSTRING(bs.month_key, 1, 4) AS UNSIGNED) AS year,
    CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) AS month_number,

    -- Fiscal year dimensions (July-June fiscal year)
    CASE
        WHEN CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) >= 7
            THEN CAST(SUBSTRING(bs.month_key, 1, 4) AS UNSIGNED)
        ELSE CAST(SUBSTRING(bs.month_key, 1, 4) AS UNSIGNED) - 1
        END AS fiscal_year,

    CASE
        WHEN CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) >= 7
            THEN CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) - 6
        ELSE CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) + 6
        END AS fiscal_month_number,

    CONCAT(
            'FY',
            CASE
                WHEN CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) >= 7
                    THEN CAST(SUBSTRING(bs.month_key, 1, 4) AS UNSIGNED)
                ELSE CAST(SUBSTRING(bs.month_key, 1, 4) AS UNSIGNED) - 1
                END,
            '-',
            LPAD(
                    CASE
                        WHEN CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) >= 7
                            THEN CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) - 6
                        ELSE CAST(SUBSTRING(bs.month_key, 5, 2) AS UNSIGNED) + 6
                        END,
                    2, '0'
            )
    ) AS fiscal_month_key,

    -- Budget scenario
    bs.budget_scenario,

    -- Metrics
    bs.budget_opex_dkk,
    bs.budget_headcount,

    -- Data lineage
    bs.data_source

FROM budget_source bs
WHERE bs.company_id IS NOT NULL  -- Additional safety filter (redundant with WHERE 1=0)
ORDER BY bs.month_key DESC, bs.company_id, bs.cost_center_id;