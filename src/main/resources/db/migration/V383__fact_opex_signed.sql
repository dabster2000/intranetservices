-- ============================================================================
-- V383: fact_opex — switch GL amount aggregation from ABS to signed (F6 + F7)
-- ============================================================================
-- Purpose
--   The `fact_opex` view aggregated General Ledger amounts with
--   `SUM(ABS(gl.amount))`. ABS() flips the sign of refunds / recharges /
--   credit corrections so a negative refund (which should REDUCE cost) is
--   counted as positive cost — double-counting it. This overstates OPEX and
--   SALARIES on every consumer that reads `fact_opex` / `fact_opex_mat`:
--     - Consultant-Insights / Unprofitable cost tab
--     - Career-Level cost tab
--     - CXO Cost-to-Revenue tab
--
--   The signed distribution path `fact_opex_distribution_mat` (built by
--   OpexDistributionRefreshService and read by the EBITDA chart) already
--   uses signed amounts and is correct. This migration aligns `fact_opex`
--   onto the SAME signed basis so all cost charts share one cost figure.
--   This also resolves F7 (cross-tab divergence: EBITDA path vs. cost tabs).
--
-- The change (the ONLY functional change to the view)
--   In CTE `opex_aggregated`:
--       BEFORE:  SUM(ABS(gl.amount)) AS opex_amount_dkk
--       AFTER:   SUM(gl.amount)      AS opex_amount_dkk
--   The column expression aggregated is `gl.amount` (alias `gl` =
--   `gl_opex_transactions`, sourced from `finance_details.amount`).
--   This is the only ABS() wrapping the GL amount; every downstream SELECT
--   already references `oa.opex_amount_dkk` (no further ABS), so removing it
--   here makes the whole view signed.
--
--   Everything else is preserved byte-for-byte from the prior definition
--   (V354): the accounting_accounts join, `cost_type IN ('OPEX','SALARIES')`,
--   posting-status / DRAFT handling (`COALESCE(fd.postingstatus,'BOOKED')`),
--   practice_id weighting (FTE + salary), the four UNION ALL branches, the
--   `WHERE oa.opex_amount_dkk > 0` guards, GROUP BY, and the full column
--   list / order / names.
--
-- Sign convention note (intentional, retained from V354)
--   The per-group `WHERE oa.opex_amount_dkk > 0` guards are kept unchanged.
--   With signed aggregation, refunds now NET against costs WITHIN the same
--   (company, posting_status, cost_center, expense_category, cost_type,
--   year, month) group — this is the intended de-duplication. If a group's
--   signed monthly total nets to <= 0 (a fully/over-refunded bucket), the
--   guard drops that row rather than surfacing a negative cost line, which
--   matches the cost-reporting semantics of the consuming tabs. The dominant
--   effect is netting within positive groups.
--
-- Oracle (prod FY25/26) — expected impact after refresh
--   signed-minus-ABS gap:  OPEX     -7,044,281
--                          SALARIES   -609,587
--   => fact_opex total cost DECREASES ~7,653,868, converging on the signed
--      fact_opex_distribution_mat basis used by the EBITDA chart.
--
-- fact_opex_mat (materialized consumer)
--   fact_opex_mat does NOT carry its own ABS(). It is repopulated verbatim
--   by `sp_refresh_fact_tables` (current def V336, block 3) via:
--       TRUNCATE TABLE fact_opex_mat;
--       INSERT IGNORE INTO fact_opex_mat (...) SELECT ... opex_amount_dkk ...
--       FROM fact_opex;
--   so it inherits the signed basis automatically on the next refresh. No
--   stored-procedure change is required. To make the corrected figures
--   visible immediately (rather than waiting for the nightly / change-log
--   refresh), this migration also re-materializes fact_opex_mat below using
--   the exact column list from V336 block 3 / V354.
--
-- Out of scope (deliberately untouched)
--   - fact_opex_distribution_mat (already signed; EBITDA path)
--   - OpexDistributionRefreshService (EBITDA path)
--   - sp_refresh_fact_tables block 10 (fact_minimum_viable_rate_mat)
--     gl_statutory_costs / gl_benefit_costs ABS() — a different,
--     account-specific payroll-cost recipe, not the fact_opex GL amount.
--
-- Idempotency / recovery
--   CREATE OR REPLACE VIEW + TRUNCATE/INSERT — safe to re-run. Flyway runs this
--   pre-traffic at startup. If the run is interrupted between the TRUNCATE and the
--   INSERT, fact_opex_mat is left empty — but Flyway then marks V383 failed and the
--   app will not serve traffic until V383 is re-run (which idempotently rebuilds the
--   mat). Manual recovery if ever needed: CALL sp_refresh_fact_tables();  (or re-run
--   just the TRUNCATE + INSERT block below).
--
-- Rollback
--   Re-apply the ABS form of the view (single line) and re-materialize:
--       -- restore line: SUM(gl.amount) -> SUM(ABS(gl.amount)) in
--       -- CTE opex_aggregated of fact_opex, e.g. re-run V354's
--       -- `CREATE OR REPLACE ... VIEW `fact_opex` AS ...` block verbatim,
--       -- then:
--       --   TRUNCATE TABLE fact_opex_mat;
--       --   INSERT IGNORE INTO fact_opex_mat (...) SELECT ... FROM fact_opex;
--   (Or simply re-apply V354's view + re-materialize.) The change is a pure
--   view redefinition + table reload; no schema/columns change, so rollback
--   carries no data-loss risk.
--
-- Validation (run after deploy, READ-ONLY)
--   1. Well-formedness / shape parity (column count + names unchanged):
--        SELECT COUNT(*) FROM fact_opex;            -- view resolves
--        SHOW COLUMNS FROM fact_opex;               -- 20 cols, names as V354
--   2. Signed vs. prior ABS total for FY25/26 (expect ~ -7,653,868 delta):
--        SELECT cost_type, ROUND(SUM(opex_amount_dkk)) AS signed_total
--        FROM fact_opex
--        WHERE fiscal_year = 2025
--        GROUP BY cost_type;
--   3. Convergence with the EBITDA (signed) distribution basis:
--        SELECT ROUND(SUM(fo.opex_amount_dkk)) AS opex_signed
--        FROM fact_opex_mat fo WHERE fo.fiscal_year = 2025;
--        -- compare against fact_opex_distribution_mat for the same window.
-- ============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_opex` AS
WITH
category_mapping AS (
    SELECT
        ac.uuid AS category_uuid,
        ac.groupname,
        CASE
            WHEN ac.groupname = 'Delte services' THEN 'PEOPLE_NON_BILLABLE'
            WHEN ac.groupname = 'Salgsfremmende omkostninger' THEN 'SALES_MARKETING'
            WHEN ac.groupname = 'Lokaleomkostninger' THEN 'OFFICE_FACILITIES'
            WHEN ac.groupname = 'Variable omkostninger' THEN 'TOOLS_SOFTWARE'
            WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'OTHER_OPEX'
            ELSE 'OTHER_OPEX'
        END AS expense_category_id,
        CASE
            WHEN ac.groupname = 'Delte services' THEN 'HR_ADMIN'
            WHEN ac.groupname = 'Salgsfremmende omkostninger' THEN 'SALES'
            WHEN ac.groupname = 'Lokaleomkostninger' THEN 'FACILITIES'
            WHEN ac.groupname = 'Variable omkostninger' THEN 'INTERNAL_IT'
            WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'ADMIN'
            ELSE 'GENERAL'
        END AS cost_center_id
    FROM accounting_categories ac
    WHERE ac.groupname IS NOT NULL
),
gl_opex_transactions AS (
    SELECT
        fd.companyuuid AS company_uuid,
        COALESCE(fd.postingstatus, 'BOOKED') AS posting_status,
        fd.accountnumber,
        fd.amount,
        fd.expensedate,
        aa.categoryuuid,
        aa.salary AS is_salary_account,
        aa.cost_type,
        YEAR(fd.expensedate) AS year_val,
        MONTH(fd.expensedate) AS month_val
    FROM finance_details fd
    INNER JOIN accounting_accounts aa
        ON  fd.accountnumber = aa.account_code
        AND fd.companyuuid   = aa.companyuuid
    WHERE fd.expensedate IS NOT NULL
      AND fd.amount != 0
      AND aa.cost_type IN ('OPEX', 'SALARIES')
),
opex_aggregated AS (
    SELECT
        gl.company_uuid,
        gl.posting_status,
        COALESCE(cm.cost_center_id, 'GENERAL') AS cost_center_id,
        COALESCE(cm.expense_category_id, 'OTHER_OPEX') AS expense_category_id,
        gl.cost_type,
        gl.year_val,
        gl.month_val,
        SUM(gl.amount) AS opex_amount_dkk,
        COUNT(*) AS invoice_count,
        MAX(CAST(gl.is_salary_account AS UNSIGNED)) AS is_payroll_flag
    FROM gl_opex_transactions gl
    LEFT JOIN category_mapping cm ON gl.categoryuuid = cm.category_uuid
    GROUP BY
        gl.company_uuid,
        gl.posting_status,
        cm.cost_center_id,
        cm.expense_category_id,
        gl.cost_type,
        gl.year_val,
        gl.month_val
),
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
salary_practice_weights AS (
    SELECT
        companyuuid AS company_id,
        practice_id,
        month_key,
        SUM(salary_sum) AS practice_salary_sum,
        SUM(SUM(salary_sum)) OVER (PARTITION BY companyuuid, month_key) AS company_total_salary
    FROM fact_salary_monthly
    WHERE salary_sum > 0
    GROUP BY companyuuid, practice_id, month_key
)
SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-', fw.practice_id, '-',
           oa.cost_center_id, '-', oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    fw.practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    ROUND(oa.opex_amount_dkk * (fw.fte_billable / fw.company_total_fte), 2) AS opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
JOIN fte_practice_weights fw
    ON  fw.company_id = oa.company_uuid
    AND fw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
WHERE oa.cost_type = 'OPEX'
  AND oa.opex_amount_dkk > 0

UNION ALL

SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-', sw.practice_id, '-',
           oa.cost_center_id, '-', oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    sw.practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    ROUND(oa.opex_amount_dkk * (sw.practice_salary_sum / sw.company_total_salary), 2) AS opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
JOIN salary_practice_weights sw
    ON  sw.company_id = oa.company_uuid
    AND sw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
WHERE oa.cost_type = 'SALARIES'
  AND oa.opex_amount_dkk > 0

UNION ALL

SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-ALL-', oa.cost_center_id, '-',
           oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    NULL AS practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
WHERE oa.cost_type = 'OPEX'
  AND oa.opex_amount_dkk > 0
  AND NOT EXISTS (
      SELECT 1 FROM fte_practice_weights fw
      WHERE fw.company_id = oa.company_uuid
        AND fw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
  )

UNION ALL

SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-ALL-', oa.cost_center_id, '-',
           oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    NULL AS practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
WHERE oa.cost_type = 'SALARIES'
  AND oa.opex_amount_dkk > 0
  AND NOT EXISTS (
      SELECT 1 FROM salary_practice_weights sw
      WHERE sw.company_id = oa.company_uuid
        AND sw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
  );

-- ----------------------------------------------------------------------------
-- Re-materialize fact_opex_mat from the now-signed view so the cost tabs
-- reflect the fix immediately (mirrors V354 / sp_refresh_fact_tables block 3).
-- Column list is identical to V336 block 3.
-- ----------------------------------------------------------------------------
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
