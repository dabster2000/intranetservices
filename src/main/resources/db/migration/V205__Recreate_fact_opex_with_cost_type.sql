-- =============================================================================
-- Migration V205: Recreate fact_opex view with cost_type filter and output
--
-- Purpose:
--   Replace the brittle account-code-range filter (3000-5999) in fact_opex
--   with a semantic cost_type filter so the view is company-agnostic and
--   works correctly regardless of each company's e-conomics chart of accounts.
--
--   Also expose cost_type as an output column so downstream consumers
--   (CxoFinanceService, BI tools) can filter SALARIES vs OPEX without
--   additional joins.
--
-- What changes vs V125:
--   REMOVED: aa.account_code >= '3000' AND aa.account_code < '6000' range filter
--   REMOVED: aa.categoryuuid NOT IN (SELECT ... WHERE groupname IN ('Direkte
--            omkostninger', 'Varesalg')) exclusion
--   ADDED:   aa.cost_type IN ('OPEX', 'SALARIES') filter on accounting_accounts
--   ADDED:   aa.cost_type as output column (cost_type) so callers can
--            distinguish SALARIES rows from OPEX rows in a single query
--   RETAINED: All existing dimension columns (opex_id, company_id,
--             cost_center_id, expense_category_id, month_key, fiscal_year,
--             fiscal_month_number, fiscal_month_key)
--   RETAINED: All existing metric columns (opex_amount_dkk, invoice_count,
--             is_payroll_flag)
--   RETAINED: data_source = 'ERP_GL'
--
-- Cost_type semantics in this view:
--   OPEX     — Operating expenses (rent, software, marketing, admin) — no salary
--              pool capping; shared accounts distributed by headcount ratio
--   SALARIES — Staff/admin payroll — salary pool capping applies in Java layer
--              (IntercompanyCalcService staff base × 1.02 buffer)
--   All other cost_type values (REVENUE, DIRECT_COSTS, IGNORE, OTHER) are
--   EXCLUDED from this view — they belong to separate views or are suppressed.
--
-- Grain: company_id × cost_center_id × expense_category_id × cost_type × month_key
--   Note: adding cost_type effectively increases the grain for mixed categories.
--   The surrogate key (opex_id) now includes cost_type to remain unique.
--
-- Data Sources:
--   - finance_details: GL transactions from e-conomic ERP
--   - accounting_accounts: Chart of accounts with cost_type classification
--   - accounting_categories: Category groupings (for cost_center / expense_category)
--
-- Impact on fact_opex_mat:
--   fact_opex_mat is a materialized copy refreshed by sp_refresh_fact_tables().
--   After this migration, the next call to sp_refresh_fact_tables() will
--   truncate and reload fact_opex_mat from this updated view — no DDL change
--   to fact_opex_mat is needed.
--
-- Impact on fact_operating_cost_distribution (V197):
--   UNCHANGED — that view filters on aa.shared = 1, not on account ranges.
--   The distribution view will automatically include SALARIES and OPEX shared
--   accounts now that those accounts are correctly classified.
--
-- Fiscal Year Logic (Trustworks):
--   July 1 → June 30. fiscal_year: month >= 7 uses calendar year, else year - 1.
--   fiscal_month_number: 1=July, 2=Aug, ..., 6=Dec, 7=Jan, ..., 12=June.
--
-- Regression target:
--   fact_opex totals should be within ±5% of pre-migration totals.
--   SALARIES amounts now appear as separate rows (cost_type = 'SALARIES')
--   that were previously included in OPEX totals when salary=1 accounts
--   fell in the 3000-5999 range. Callers that summed ALL fact_opex rows
--   will see same totals; callers that need to separate payroll should now
--   filter on cost_type = 'SALARIES'.
--
-- Idempotent: CREATE OR REPLACE VIEW — safe to re-run.
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_opex` AS

WITH

-- ---------------------------------------------------------------------------
-- 1) Map accounting categories to expense categories and cost centers.
--    Identical to V125; preserves existing cost_center/expense_category
--    semantics for OPEX categories. SALARIES accounts default to GENERAL /
--    PEOPLE_NON_BILLABLE since their category groupname varies.
-- ---------------------------------------------------------------------------
category_mapping AS (
    SELECT
        ac.uuid                 AS category_uuid,
        ac.groupname,
        CASE
            WHEN ac.groupname = 'Delte services'                  THEN 'PEOPLE_NON_BILLABLE'
            WHEN ac.groupname = 'Salgsfremmende omkostninger'     THEN 'SALES_MARKETING'
            WHEN ac.groupname = 'Lokaleomkostninger'              THEN 'OFFICE_FACILITIES'
            WHEN ac.groupname = 'Variable omkostninger'           THEN 'TOOLS_SOFTWARE'
            WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'OTHER_OPEX'
            -- Salary accounts and any unmapped OPEX categories default to OTHER_OPEX
            ELSE 'OTHER_OPEX'
        END                     AS expense_category_id,
        CASE
            WHEN ac.groupname = 'Delte services'                  THEN 'HR_ADMIN'
            WHEN ac.groupname = 'Salgsfremmende omkostninger'     THEN 'SALES'
            WHEN ac.groupname = 'Lokaleomkostninger'              THEN 'FACILITIES'
            WHEN ac.groupname = 'Variable omkostninger'           THEN 'INTERNAL_IT'
            WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'ADMIN'
            ELSE 'GENERAL'
        END                     AS cost_center_id
    FROM accounting_categories ac
    WHERE ac.groupname IS NOT NULL
),

-- ---------------------------------------------------------------------------
-- 2) Get GL transactions classified as OPEX or SALARIES.
--    Filter: aa.cost_type IN ('OPEX', 'SALARIES')
--    Replaces the old account-code range filter (3000-5999) and the
--    groupname exclusion list ('Direkte omkostninger', 'Varesalg').
--    Joining on companyuuid + account_code ensures company-specific accounts
--    are resolved correctly when the same account code exists in multiple
--    companies' charts.
-- ---------------------------------------------------------------------------
gl_opex_transactions AS (
    SELECT
        fd.companyuuid          AS company_uuid,
        fd.accountnumber,
        fd.amount,
        fd.expensedate,
        aa.categoryuuid,
        aa.salary               AS is_salary_account,
        aa.cost_type,
        YEAR(fd.expensedate)    AS year_val,
        MONTH(fd.expensedate)   AS month_val
    FROM finance_details fd
    INNER JOIN accounting_accounts aa
        ON  fd.accountnumber = aa.account_code
        AND fd.companyuuid   = aa.companyuuid
    WHERE fd.expensedate IS NOT NULL
      AND fd.amount      != 0
      -- Semantic filter: only OPEX and SALARIES accounts flow into this view
      AND aa.cost_type IN ('OPEX', 'SALARIES')
),

-- ---------------------------------------------------------------------------
-- 3) Aggregate GL transactions by company, cost center, category,
--    cost_type, and month.
--    cost_type is included in the GROUP BY so SALARIES rows are kept
--    separate from OPEX rows even when they share the same category mapping.
-- ---------------------------------------------------------------------------
opex_aggregated AS (
    SELECT
        gl.company_uuid,
        COALESCE(cm.cost_center_id,      'GENERAL')   AS cost_center_id,
        COALESCE(cm.expense_category_id, 'OTHER_OPEX') AS expense_category_id,
        gl.cost_type,
        gl.year_val,
        gl.month_val,
        SUM(ABS(gl.amount))                            AS opex_amount_dkk,
        COUNT(*)                                       AS invoice_count,
        MAX(CAST(gl.is_salary_account AS UNSIGNED))    AS is_payroll_flag
    FROM gl_opex_transactions gl
    LEFT JOIN category_mapping cm ON gl.categoryuuid = cm.category_uuid
    GROUP BY
        gl.company_uuid,
        cm.cost_center_id,
        cm.expense_category_id,
        gl.cost_type,
        gl.year_val,
        gl.month_val
)

-- ---------------------------------------------------------------------------
-- 4) Final SELECT with all dimensions.
--    cost_type is now an explicit output column so callers can filter
--    SALARIES vs OPEX without re-joining accounting_accounts.
--    Surrogate key (opex_id) now includes cost_type segment to remain
--    unique across the new grain.
-- ---------------------------------------------------------------------------
SELECT
    -- Surrogate key: includes cost_type to keep rows unique
    CONCAT(
        oa.company_uuid,       '-',
        oa.cost_center_id,     '-',
        oa.expense_category_id, '-',
        oa.cost_type,          '-',
        LPAD(oa.year_val,  4, '0'),
        LPAD(oa.month_val, 2, '0')
    )                                                               AS opex_id,

    -- Dimension keys
    oa.company_uuid                                                 AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,                                                   -- NEW: OPEX or SALARIES
    NULL                                                            AS expense_subcategory_id,
    NULL                                                            AS practice_id,
    NULL                                                            AS sector_id,

    -- Calendar time dimensions
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val                                                     AS year,
    oa.month_val                                                    AS month_number,

    -- Fiscal year dimensions (July-June fiscal year)
    CASE
        WHEN oa.month_val >= 7 THEN oa.year_val
        ELSE oa.year_val - 1
    END                                                             AS fiscal_year,

    CASE
        WHEN oa.month_val >= 7 THEN oa.month_val - 6   -- Jul=1, Aug=2, ..., Dec=6
        ELSE oa.month_val + 6                          -- Jan=7, Feb=8, ..., Jun=12
    END                                                             AS fiscal_month_number,

    CONCAT(
        'FY',
        CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END,
        '-',
        LPAD(
            CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END,
            2, '0'
        )
    )                                                               AS fiscal_month_key,

    -- Metrics
    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,

    -- Data source
    'ERP_GL'                                                        AS data_source

FROM opex_aggregated oa
WHERE oa.opex_amount_dkk > 0
ORDER BY oa.year_val DESC, oa.month_val DESC, oa.company_uuid, oa.cost_center_id;
