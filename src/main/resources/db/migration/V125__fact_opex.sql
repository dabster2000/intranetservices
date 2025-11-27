-- =============================================================================
-- Migration V125: Create fact_opex view
--
-- Purpose:
-- - Track all non-delivery operating expenses below gross margin (OPEX)
-- - Enables OPEX analysis: TTM/YTD totals, % of revenue, category breakdowns
-- - Excludes direct delivery costs already in fact_project_financials
--
-- Grain: company_id × cost_center_id × expense_category_id × month_key
--
-- Data Sources:
-- - finance_details: GL transactions from e-conomic ERP
-- - accounting_accounts: Chart of accounts with category mappings
-- - accounting_categories: Category groupings
--
-- Account Code Ranges (Danish Chart of Accounts):
-- - 1000-1299: Revenue accounts (EXCLUDED)
-- - 1300-1999: Direct delivery costs (EXCLUDED - in fact_project_financials)
-- - 2000-2999: Mixed accounts (INCLUDE expenses only)
-- - 3000-5999: Operating expense accounts (OPEX - INCLUDED)
--
-- Fiscal Year Logic (Trustworks):
-- - Fiscal year starts July 1st (month 7)
-- - FY2024 runs from July 1, 2024 through June 30, 2025
-- - fiscal_month_number: 1=July, 2=Aug, ..., 6=Dec, 7=Jan, ..., 12=June
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_opex` AS

WITH
    -- 1) Map accounting categories to expense categories and cost centers
    category_mapping AS (
        SELECT
            ac.uuid AS category_uuid,
            ac.groupname,
            ac.accountcode,
            CASE
                -- Shared services / HR admin costs
                WHEN ac.groupname = 'Delte services' THEN 'PEOPLE_NON_BILLABLE'
                -- Sales and marketing costs
                WHEN ac.groupname = 'Salgsfremmende omkostninger' THEN 'SALES_MARKETING'
                -- Office facilities and rent
                WHEN ac.groupname = 'Lokaleomkostninger' THEN 'OFFICE_FACILITIES'
                -- Variable costs (IT, transport, tools)
                WHEN ac.groupname = 'Variable omkostninger' THEN 'TOOLS_SOFTWARE'
                -- Other administrative costs
                WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'OTHER_OPEX'
                -- Default for unmapped categories
                ELSE 'OTHER_OPEX'
                END AS expense_category_id,
            CASE
                -- Map to cost centers based on category
                WHEN ac.groupname = 'Delte services' THEN 'HR_ADMIN'
                WHEN ac.groupname = 'Salgsfremmende omkostninger' THEN 'SALES'
                WHEN ac.groupname = 'Lokaleomkostninger' THEN 'FACILITIES'
                WHEN ac.groupname = 'Variable omkostninger' THEN 'INTERNAL_IT'
                WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'ADMIN'
                ELSE 'GENERAL'
                END AS cost_center_id
        FROM accounting_categories ac
        WHERE ac.groupname IS NOT NULL
          -- Exclude revenue and direct delivery cost categories
          AND ac.groupname NOT IN ('Varesalg', 'Direkte omkostninger')
    ),

    -- 2) Get GL transactions with category mapping
    -- Only include OPEX accounts (3000-5999 range, plus some 2000-2999)
    gl_opex_transactions AS (
        SELECT
            fd.companyuuid AS company_uuid,
            fd.accountnumber,
            fd.amount,
            fd.expensedate,
            fd.text,
            aa.categoryuuid,
            aa.salary AS is_salary_account,
            YEAR(fd.expensedate) AS year_val,
            MONTH(fd.expensedate) AS month_val
        FROM finance_details fd
                 INNER JOIN accounting_accounts aa
                            ON fd.accountnumber = aa.account_code
                                AND fd.companyuuid = aa.companyuuid
        WHERE fd.expensedate IS NOT NULL
          AND fd.amount != 0
          -- Include OPEX account ranges only
          AND (
            -- Primary OPEX range (3000-5999)
            (aa.account_code >= '3000' AND aa.account_code < '6000')
            -- OR mixed accounts where we include expenses (2000-2999)
            -- Exclude revenue accounts (1000-1299) and direct costs (1300-1999)
            )
          -- Exclude direct delivery cost categories
          AND aa.categoryuuid NOT IN (
            SELECT uuid FROM accounting_categories
            WHERE groupname IN ('Direkte omkostninger', 'Varesalg')
        )
    ),

    -- 3) Aggregate GL transactions by company, cost center, category, and month
    opex_aggregated AS (
        SELECT
            gl.company_uuid,
            COALESCE(cm.cost_center_id, 'GENERAL') AS cost_center_id,
            COALESCE(cm.expense_category_id, 'OTHER_OPEX') AS expense_category_id,
            gl.year_val,
            gl.month_val,
            -- Sum positive amounts (expenses are positive in GL)
            SUM(ABS(gl.amount)) AS opex_amount_dkk,
            -- Count of GL entries
            COUNT(*) AS invoice_count,
            -- Track if this is payroll-related
            MAX(CAST(gl.is_salary_account AS UNSIGNED)) AS is_payroll_flag
        FROM gl_opex_transactions gl
                 LEFT JOIN category_mapping cm ON gl.categoryuuid = cm.category_uuid
        GROUP BY
            gl.company_uuid,
            cm.cost_center_id,
            cm.expense_category_id,
            gl.year_val,
            gl.month_val
    )

-- 4) Final SELECT with all dimensions
SELECT
    -- Surrogate key
    CONCAT(
            oa.company_uuid, '-',
            oa.cost_center_id, '-',
            oa.expense_category_id, '-',
            LPAD(oa.year_val, 4, '0'),
            LPAD(oa.month_val, 2, '0')
    ) AS opex_id,

    -- Dimension keys
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    NULL AS expense_subcategory_id,  -- For future enhancement
    NULL AS practice_id,              -- Optional allocation
    NULL AS sector_id,                -- Optional allocation

    -- Calendar time dimensions
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,

    -- Fiscal year dimensions (July-June fiscal year)
    CASE
        WHEN oa.month_val >= 7 THEN oa.year_val     -- Jul-Dec: same year
        ELSE oa.year_val - 1                        -- Jan-Jun: previous year
        END AS fiscal_year,

    CASE
        WHEN oa.month_val >= 7 THEN oa.month_val - 6  -- Jul=1, Aug=2, ..., Dec=6
        ELSE oa.month_val + 6                         -- Jan=7, Feb=8, ..., Jun=12
        END AS fiscal_month_number,

    CONCAT(
            'FY',
            CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END,
            '-',
            LPAD(
                    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END,
                    2, '0'
            )
    ) AS fiscal_month_key,

    -- Metrics
    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag AS is_payroll_flag,

    -- Data source
    'ERP_GL' AS data_source

FROM opex_aggregated oa
WHERE oa.opex_amount_dkk > 0  -- Only include rows with actual expenses
ORDER BY oa.year_val DESC, oa.month_val DESC, oa.company_uuid, oa.cost_center_id;
