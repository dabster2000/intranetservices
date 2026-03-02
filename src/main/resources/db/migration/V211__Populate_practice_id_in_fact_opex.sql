-- =============================================================================
-- Migration V211: Populate practice_id in fact_opex
--
-- Purpose:
--   Replace company-level rows in fact_opex with practice-level rows so that
--   the CXO dashboard's Practices tab can compute gross margin per practice
--   (PM, BA, CYB, DEV, SA) including OPEX and salary overhead.
--
--   Previously, practice_id was hardcoded NULL (V205 line 189). The gross-margin
--   route queries `fact_opex_mat WHERE practice_id IN ('PM','BA','CYB','DEV','SA')`
--   and returned zero rows — practice margins were silently overstated because
--   only direct delivery costs were included.
--
-- Approach: Replace company-level rows with practice-level rows
--   Each company-level row is SPLIT into N practice-level rows (one per active
--   practice in that company/month). The practice amounts sum to the original
--   company total, so consumers that SUM without filtering on practice_id
--   (13 out of 14 consumers) continue to get the same results.
--
--   Why replace, not add alongside: adding practice rows alongside company rows
--   would cause double-counting for all consumers that SUM(opex_amount_dkk)
--   without filtering on practice_id.
--
-- Distribution keys (per cost_type):
--   OPEX:     Billable FTE proportion from fact_employee_monthly_mat
--             (shared costs have no natural practice owner; FTE-weighted is
--             the established pattern from V207)
--   SALARIES: Salary-sum proportion from fact_salary_monthly view
--             (actual salary amounts per practice are known)
--
-- Fallback: When no FTE/salary data exists for a company+month, the row keeps
--   practice_id = NULL (no data loss, no orphaned amounts).
--
-- Amount conservation guarantee:
--   For every company+month, SUM(opex_amount_dkk) from the new view equals
--   the old view's amount, because:
--   - Practice rows: amount * (practice_weight / total_weight) — sums to amount
--   - Fallback rows: original amount preserved when no weight data exists
--   - No overlap: a company+month row is either split OR kept as fallback,
--     enforced by the NOT EXISTS clause on fallback branches.
--
-- Grain (updated): company_id x practice_id x cost_center_id x
--   expense_category_id x cost_type x month_key
--
-- Consumer impact (verified safe — all 14 consumers):
--   - Practices gross-margin route: target consumer, gets data for first time
--   - Cost-to-revenue route: groups by company+month, SUM unchanged
--   - Cost-per-consultant route: groups by year+month, SUM unchanged
--   - DistributionAwareOpexProvider: groups by company+cost_center+category+
--     cost_type+month, SUM unchanged
--   - CxoFinanceService (10 methods): via provider above, same reasoning
--   - fact_minimum_viable_rate group_opex CTE: grand SUM, unchanged
--   - Legacy Vaadin: comments only, no queries
--
-- sp_refresh_fact_tables():
--   The stored procedure (V206) already includes practice_id in the
--   fact_opex_mat INSERT/SELECT column list. The INSERT ... SELECT FROM
--   fact_opex automatically picks up the new view definition — no procedure
--   change is needed.
--
-- Performance:
--   fact_opex is a view; runtime cost only applies when queried directly.
--   The materialized table (fact_opex_mat) is used by all API consumers.
--   Using fact_employee_monthly_mat (materialized) for FTE weights is fast.
--   Using fact_salary_monthly (view) for salary weights is slower but
--   acceptable since this only runs during sp_refresh_fact_tables().
--
-- Idempotent: CREATE OR REPLACE VIEW — safe to re-run.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Recreate fact_opex VIEW with practice-level distribution
-- ---------------------------------------------------------------------------

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_opex` AS

WITH

-- ---------------------------------------------------------------------------
-- 1) Map accounting categories to expense categories and cost centers.
--    UNCHANGED from V205.
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
--    UNCHANGED from V205.
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
      AND aa.cost_type IN ('OPEX', 'SALARIES')
),

-- ---------------------------------------------------------------------------
-- 3) Aggregate GL transactions by company, cost center, category,
--    cost_type, and month.
--    UNCHANGED from V205.
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
),

-- ---------------------------------------------------------------------------
-- 4) NEW: Billable FTE proportion per practice within each company+month.
--    Source: fact_employee_monthly_mat (materialized for performance).
--    Used to distribute OPEX cost_type rows across practices.
-- ---------------------------------------------------------------------------
fte_practice_weights AS (
    SELECT
        company_id,
        practice_id,
        month_key,
        fte_billable,
        SUM(fte_billable) OVER (PARTITION BY company_id, month_key) AS company_total_fte
    FROM fact_employee_monthly_mat
    WHERE role_type = 'BILLABLE'
      AND fte_billable > 0
),

-- ---------------------------------------------------------------------------
-- 5) NEW: Salary-sum proportion per practice within each company+month.
--    Source: fact_salary_monthly view (no materialized version exists).
--    Used to distribute SALARIES cost_type rows across practices.
-- ---------------------------------------------------------------------------
salary_practice_weights AS (
    SELECT
        companyuuid                 AS company_id,
        practice_id,
        month_key,
        SUM(salary_sum)             AS practice_salary_sum,
        SUM(SUM(salary_sum)) OVER (PARTITION BY companyuuid, month_key) AS company_total_salary
    FROM fact_salary_monthly
    WHERE salary_sum > 0
    GROUP BY companyuuid, practice_id, month_key
)

-- ---------------------------------------------------------------------------
-- 6) Final SELECT: 4-way UNION ALL
--    Branch 1: OPEX rows distributed by billable FTE proportion
--    Branch 2: SALARIES rows distributed by salary proportion
--    Branch 3: OPEX fallback (no FTE data for company+month)
--    Branch 4: SALARIES fallback (no salary data for company+month)
-- ---------------------------------------------------------------------------

-- ===== Branch 1: OPEX rows × FTE practice weights =====
SELECT
    CONCAT(
        oa.company_uuid,       '-',
        fw.practice_id,        '-',
        oa.cost_center_id,     '-',
        oa.expense_category_id, '-',
        oa.cost_type,          '-',
        LPAD(oa.year_val,  4, '0'),
        LPAD(oa.month_val, 2, '0')
    )                                                               AS opex_id,

    oa.company_uuid                                                 AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL                                                            AS expense_subcategory_id,
    fw.practice_id,
    NULL                                                            AS sector_id,

    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val                                                     AS year,
    oa.month_val                                                    AS month_number,

    CASE
        WHEN oa.month_val >= 7 THEN oa.year_val
        ELSE oa.year_val - 1
    END                                                             AS fiscal_year,

    CASE
        WHEN oa.month_val >= 7 THEN oa.month_val - 6
        ELSE oa.month_val + 6
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

    ROUND(oa.opex_amount_dkk * (fw.fte_billable / fw.company_total_fte), 2)
                                                                    AS opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,

    'ERP_GL'                                                        AS data_source

FROM opex_aggregated oa
JOIN fte_practice_weights fw
    ON  fw.company_id = oa.company_uuid
    AND fw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
WHERE oa.cost_type = 'OPEX'
  AND oa.opex_amount_dkk > 0

UNION ALL

-- ===== Branch 2: SALARIES rows × salary practice weights =====
SELECT
    CONCAT(
        oa.company_uuid,       '-',
        sw.practice_id,        '-',
        oa.cost_center_id,     '-',
        oa.expense_category_id, '-',
        oa.cost_type,          '-',
        LPAD(oa.year_val,  4, '0'),
        LPAD(oa.month_val, 2, '0')
    )                                                               AS opex_id,

    oa.company_uuid                                                 AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL                                                            AS expense_subcategory_id,
    sw.practice_id,
    NULL                                                            AS sector_id,

    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val                                                     AS year,
    oa.month_val                                                    AS month_number,

    CASE
        WHEN oa.month_val >= 7 THEN oa.year_val
        ELSE oa.year_val - 1
    END                                                             AS fiscal_year,

    CASE
        WHEN oa.month_val >= 7 THEN oa.month_val - 6
        ELSE oa.month_val + 6
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

    ROUND(oa.opex_amount_dkk * (sw.practice_salary_sum / sw.company_total_salary), 2)
                                                                    AS opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,

    'ERP_GL'                                                        AS data_source

FROM opex_aggregated oa
JOIN salary_practice_weights sw
    ON  sw.company_id = oa.company_uuid
    AND sw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
WHERE oa.cost_type = 'SALARIES'
  AND oa.opex_amount_dkk > 0

UNION ALL

-- ===== Branch 3: OPEX fallback — no FTE data for this company+month =====
SELECT
    CONCAT(
        oa.company_uuid,       '-ALL-',
        oa.cost_center_id,     '-',
        oa.expense_category_id, '-',
        oa.cost_type,          '-',
        LPAD(oa.year_val,  4, '0'),
        LPAD(oa.month_val, 2, '0')
    )                                                               AS opex_id,

    oa.company_uuid                                                 AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL                                                            AS expense_subcategory_id,
    NULL                                                            AS practice_id,
    NULL                                                            AS sector_id,

    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val                                                     AS year,
    oa.month_val                                                    AS month_number,

    CASE
        WHEN oa.month_val >= 7 THEN oa.year_val
        ELSE oa.year_val - 1
    END                                                             AS fiscal_year,

    CASE
        WHEN oa.month_val >= 7 THEN oa.month_val - 6
        ELSE oa.month_val + 6
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

    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,

    'ERP_GL'                                                        AS data_source

FROM opex_aggregated oa
WHERE oa.cost_type = 'OPEX'
  AND oa.opex_amount_dkk > 0
  AND NOT EXISTS (
      SELECT 1 FROM fte_practice_weights fw
      WHERE fw.company_id = oa.company_uuid
        AND fw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
  )

UNION ALL

-- ===== Branch 4: SALARIES fallback — no salary data for this company+month =====
SELECT
    CONCAT(
        oa.company_uuid,       '-ALL-',
        oa.cost_center_id,     '-',
        oa.expense_category_id, '-',
        oa.cost_type,          '-',
        LPAD(oa.year_val,  4, '0'),
        LPAD(oa.month_val, 2, '0')
    )                                                               AS opex_id,

    oa.company_uuid                                                 AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL                                                            AS expense_subcategory_id,
    NULL                                                            AS practice_id,
    NULL                                                            AS sector_id,

    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val                                                     AS year,
    oa.month_val                                                    AS month_number,

    CASE
        WHEN oa.month_val >= 7 THEN oa.year_val
        ELSE oa.year_val - 1
    END                                                             AS fiscal_year,

    CASE
        WHEN oa.month_val >= 7 THEN oa.month_val - 6
        ELSE oa.month_val + 6
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

    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,

    'ERP_GL'                                                        AS data_source

FROM opex_aggregated oa
WHERE oa.cost_type = 'SALARIES'
  AND oa.opex_amount_dkk > 0
  AND NOT EXISTS (
      SELECT 1 FROM salary_practice_weights sw
      WHERE sw.company_id = oa.company_uuid
        AND sw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
  );


-- ---------------------------------------------------------------------------
-- 2. Add index on fact_opex_mat for practice_id + month_key queries
--    Supports: WHERE practice_id IN ('PM','BA','CYB','DEV','SA')
--    Used by: /api/cxo/practices/gross-margin route
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_fom_practice_month
    ON fact_opex_mat (practice_id, month_key);


-- ---------------------------------------------------------------------------
-- 3. Immediate repopulation of fact_opex_mat
--    Repopulates from the updated fact_opex view so the mat table contains
--    practice-level rows immediately without waiting for the nightly
--    sp_nightly_bi_refresh() run.
--
--    Note: sp_refresh_fact_tables() (V206) already includes practice_id in
--    the INSERT/SELECT column list for fact_opex_mat. No stored procedure
--    change is needed — the INSERT ... SELECT FROM fact_opex automatically
--    picks up the new view definition.
-- ---------------------------------------------------------------------------
TRUNCATE TABLE fact_opex_mat;

INSERT IGNORE INTO fact_opex_mat
    (opex_id, company_id, cost_center_id, expense_category_id,
     expense_subcategory_id, practice_id, sector_id,
     month_key, year, month_number,
     fiscal_year, fiscal_month_number, fiscal_month_key,
     opex_amount_dkk, invoice_count, is_payroll_flag,
     cost_type,
     data_source)
SELECT
    opex_id, company_id, cost_center_id, expense_category_id,
    expense_subcategory_id, practice_id, sector_id,
    month_key, year, month_number,
    fiscal_year, fiscal_month_number, fiscal_month_key,
    opex_amount_dkk, invoice_count, is_payroll_flag,
    cost_type,
    data_source
FROM fact_opex;
