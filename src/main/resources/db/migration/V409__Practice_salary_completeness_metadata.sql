-- V409: make the Practices operating-cost source explicitly signed and expose
-- company x month x cost-source salary-completeness metadata.
--
-- The fact_opex definition below is the V408 signed/non-zero definition. It is
-- intentionally repeated in a forward migration so an already-applied
-- migration is never edited. The materialized table is rebuilt immediately.
-- fact_opex_mat.posting_status is a stored generated column (V354) derived from
-- the BOOKED-/DRAFT-prefixed opex_id, so this explicit column-list insert
-- preserves posting status without attempting to write a generated column.

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
  AND oa.opex_amount_dkk <> 0

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
  AND oa.opex_amount_dkk <> 0

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
  AND oa.opex_amount_dkk <> 0
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
  AND oa.opex_amount_dkk <> 0
  AND NOT EXISTS (
      SELECT 1 FROM salary_practice_weights sw
      WHERE sw.company_id = oa.company_uuid
        AND sw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
  );

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

-- A nullable UTC generation is deliberately added without a default. The
-- supported BI orchestrators stamp both cost sources with one identical
-- generation only after sp_refresh_fact_tables has finished. A legacy or
-- manual direct call to sp_refresh_fact_tables therefore leaves NULL stamps;
-- the protected API detects that unpublished state and fails closed instead
-- of treating an empty/partial refresh as zero cost.
ALTER TABLE fact_opex_mat
    ADD COLUMN IF NOT EXISTS materialized_at DATETIME(6) NULL COMMENT 'UTC publication generation';

ALTER TABLE fact_employee_monthly_mat
    ADD COLUMN IF NOT EXISTS materialized_at DATETIME(6) NULL COMMENT 'UTC publication generation';

-- fact_opex allocates non-salary OPEX with fact_employee_monthly_mat weights.
-- The legacy main refresh rebuilds OPEX before employee-monthly, so the
-- supported orchestrators must rebuild OPEX once more after the employee fact
-- is current. Keeping this as a small named step avoids publishing allocations
-- based on the preceding refresh's FTE weights.
DROP PROCEDURE IF EXISTS sp_refresh_practice_opex_mat;

DELIMITER $$

CREATE PROCEDURE sp_refresh_practice_opex_mat()
BEGIN
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
END$$

DELIMITER ;

-- The view below is the canonical aggregate recipe. A small materialized copy
-- is refreshed transactionally after fact_opex_mat so protected dashboard
-- reads stay inside the CXO timeout. Neither layer exposes employee-level
-- data: their only grain is production company x completed month x cost source
-- for the 60 months needed by the 36-anchor search and its prior comparison
-- window.
CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `v_practice_salary_completeness` AS
WITH RECURSIVE
reporting_clock AS (
    SELECT CAST(DATE_FORMAT(
               DATE(CONVERT_TZ(UTC_TIMESTAMP(), 'UTC', 'Europe/Copenhagen')),
               '%Y-%m-01') AS DATE) AS current_month_start
),
month_spine AS (
    SELECT
        0 AS month_offset,
        DATE_SUB(rc.current_month_start, INTERVAL 1 MONTH) AS month_start
    FROM reporting_clock rc
    UNION ALL
    SELECT
        month_offset + 1,
        DATE_SUB(month_start, INTERVAL 1 MONTH)
    FROM month_spine
    WHERE month_offset < 59
),
production_companies AS (
    SELECT 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AS company_id
    UNION ALL SELECT '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3'
    UNION ALL SELECT 'e4b0a2a4-0963-4153-b0a2-a409637153a2'
),
cost_sources AS (
    SELECT 'BOOKED' AS cost_source
    UNION ALL SELECT 'BOOKED_PLUS_DRAFT'
),
expected_salary_cells AS (
    SELECT
        fsm.companyuuid AS company_id,
        fsm.month_key,
        fsm.practice_id
    FROM fact_salary_monthly fsm
    WHERE fsm.month_key >= DATE_FORMAT(
              DATE_SUB((SELECT current_month_start FROM reporting_clock), INTERVAL 60 MONTH),
              '%Y%m')
      AND fsm.month_key < DATE_FORMAT((SELECT current_month_start FROM reporting_clock), '%Y%m')
      AND fsm.companyuuid IN (
          'd8894494-2fb4-4f72-9e05-e6032e6dd691',
          '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
          'e4b0a2a4-0963-4153-b0a2-a409637153a2')
      AND fsm.practice_id IN ('PM', 'BA', 'CYB', 'DEV', 'SA')
      AND fsm.salary_sum > 0
    GROUP BY fsm.companyuuid, fsm.month_key, fsm.practice_id
),
expected_salary_cell_counts AS (
    SELECT company_id, month_key, COUNT(*) AS expected_salary_cell_count
    FROM expected_salary_cells
    GROUP BY company_id, month_key
),
intended_salary AS (
    SELECT
        fsm.companyuuid AS company_id,
        fsm.month_key,
        SUM(fsm.salary_sum) AS intended_salary_dkk
    FROM fact_salary_monthly fsm
    WHERE fsm.month_key >= DATE_FORMAT(
              DATE_SUB((SELECT current_month_start FROM reporting_clock), INTERVAL 60 MONTH),
              '%Y%m')
      AND fsm.month_key < DATE_FORMAT((SELECT current_month_start FROM reporting_clock), '%Y%m')
      AND fsm.companyuuid IN (
          'd8894494-2fb4-4f72-9e05-e6032e6dd691',
          '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
          'e4b0a2a4-0963-4153-b0a2-a409637153a2')
    GROUP BY fsm.companyuuid, fsm.month_key
),
signed_gl_by_status AS (
    SELECT
        fd.companyuuid AS company_id,
        DATE_FORMAT(fd.expensedate, '%Y%m') AS month_key,
        COALESCE(fd.postingstatus, 'BOOKED') AS posting_status,
        SUM(fd.amount) AS signed_salary_gl_dkk
    FROM finance_details fd
    INNER JOIN accounting_accounts aa
        ON  aa.companyuuid = fd.companyuuid
        AND aa.account_code = fd.accountnumber
        AND aa.cost_type = 'SALARIES'
    WHERE fd.expensedate >= DATE_SUB(
              (SELECT current_month_start FROM reporting_clock), INTERVAL 60 MONTH)
      AND fd.expensedate < (SELECT current_month_start FROM reporting_clock)
      AND fd.companyuuid IN (
          'd8894494-2fb4-4f72-9e05-e6032e6dd691',
          '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
          'e4b0a2a4-0963-4153-b0a2-a409637153a2')
      AND COALESCE(fd.postingstatus, 'BOOKED') IN ('BOOKED', 'DRAFT')
    GROUP BY fd.companyuuid, DATE_FORMAT(fd.expensedate, '%Y%m'),
             COALESCE(fd.postingstatus, 'BOOKED')
),
selected_signed_gl AS (
    SELECT
        cs.cost_source,
        sg.company_id,
        sg.month_key,
        SUM(sg.signed_salary_gl_dkk) AS signed_salary_gl_dkk
    FROM cost_sources cs
    INNER JOIN signed_gl_by_status sg
        ON sg.posting_status = 'BOOKED'
        OR (cs.cost_source = 'BOOKED_PLUS_DRAFT' AND sg.posting_status = 'DRAFT')
    GROUP BY cs.cost_source, sg.company_id, sg.month_key
),
selected_allocated_salary_cells AS (
    SELECT
        cs.cost_source,
        fo.company_id,
        fo.month_key,
        COALESCE(fo.practice_id, '__NULL__') AS practice_id,
        SUM(fo.opex_amount_dkk) AS allocated_salary_dkk
    FROM cost_sources cs
    INNER JOIN fact_opex_mat fo
        ON fo.posting_status = 'BOOKED'
        OR (cs.cost_source = 'BOOKED_PLUS_DRAFT' AND fo.posting_status = 'DRAFT')
    WHERE fo.month_key >= DATE_FORMAT(
              DATE_SUB((SELECT current_month_start FROM reporting_clock), INTERVAL 60 MONTH),
              '%Y%m')
      AND fo.month_key < DATE_FORMAT((SELECT current_month_start FROM reporting_clock), '%Y%m')
      AND fo.company_id IN (
          'd8894494-2fb4-4f72-9e05-e6032e6dd691',
          '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
          'e4b0a2a4-0963-4153-b0a2-a409637153a2')
      AND fo.cost_type = 'SALARIES'
    GROUP BY cs.cost_source, fo.company_id, fo.month_key,
             COALESCE(fo.practice_id, '__NULL__')
),
selected_allocated_practice_salary_cells AS (
    -- Cell coverage is scoped to the five Practices cards. Other legitimate
    -- allocation codes (for example JK/UD) remain in the all-cell total below
    -- so the complete salary allocation still reconciles to signed GL.
    SELECT cost_source, company_id, month_key, practice_id, allocated_salary_dkk
    FROM selected_allocated_salary_cells
    WHERE practice_id IN ('PM', 'BA', 'CYB', 'DEV', 'SA')
),
allocated_salary_totals AS (
    SELECT cost_source, company_id, month_key,
           SUM(allocated_salary_dkk) AS allocated_salary_dkk
    FROM selected_allocated_salary_cells
    GROUP BY cost_source, company_id, month_key
),
actual_salary_cell_counts AS (
    SELECT cost_source, company_id, month_key,
           COUNT(*) AS actual_salary_cell_count
    FROM selected_allocated_practice_salary_cells
    GROUP BY cost_source, company_id, month_key
),
covered_salary_cell_counts AS (
    SELECT
        a.cost_source,
        a.company_id,
        a.month_key,
        COUNT(*) AS covered_salary_cell_count
    FROM selected_allocated_practice_salary_cells a
    INNER JOIN expected_salary_cells e
        ON  e.company_id = a.company_id
        AND e.month_key = a.month_key
        AND e.practice_id = a.practice_id
    GROUP BY a.cost_source, a.company_id, a.month_key
),
metadata_grid AS (
    SELECT
        pc.company_id,
        DATE_FORMAT(ms.month_start, '%Y%m') AS month_key,
        cs.cost_source
    FROM month_spine ms
    CROSS JOIN production_companies pc
    CROSS JOIN cost_sources cs
),
metrics AS (
    SELECT
        g.company_id,
        g.month_key,
        g.cost_source,
        COALESCE(i.intended_salary_dkk, 0.0) AS intended_salary_dkk,
        COALESCE(sg.signed_salary_gl_dkk, 0.0) AS signed_salary_gl_dkk,
        COALESCE(at.allocated_salary_dkk, 0.0) AS allocated_salary_dkk,
        CASE
            WHEN COALESCE(i.intended_salary_dkk, 0.0) > 0.0
                THEN ROUND(ABS(COALESCE(sg.signed_salary_gl_dkk, 0.0))
                           / i.intended_salary_dkk, 6)
            ELSE NULL
        END AS salary_completeness_ratio,
        COALESCE(ec.expected_salary_cell_count, 0) AS expected_salary_cell_count,
        COALESCE(ac.actual_salary_cell_count, 0) AS actual_salary_cell_count,
        COALESCE(cc.covered_salary_cell_count, 0) AS covered_salary_cell_count,
        COALESCE(ec.expected_salary_cell_count, 0)
            - COALESCE(cc.covered_salary_cell_count, 0) AS missing_salary_cell_count,
        COALESCE(ac.actual_salary_cell_count, 0)
            - COALESCE(cc.covered_salary_cell_count, 0) AS unexpected_salary_cell_count,
        ABS(COALESCE(at.allocated_salary_dkk, 0.0)
            - COALESCE(sg.signed_salary_gl_dkk, 0.0)) AS allocation_gap_dkk,
        GREATEST(1.00, ABS(COALESCE(sg.signed_salary_gl_dkk, 0.0)) * 0.0001)
            AS allowed_allocation_gap_dkk
    FROM metadata_grid g
    LEFT JOIN intended_salary i
        ON i.company_id = g.company_id AND i.month_key = g.month_key
    LEFT JOIN selected_signed_gl sg
        ON  sg.cost_source = g.cost_source
        AND sg.company_id = g.company_id
        AND sg.month_key = g.month_key
    LEFT JOIN expected_salary_cell_counts ec
        ON ec.company_id = g.company_id AND ec.month_key = g.month_key
    LEFT JOIN actual_salary_cell_counts ac
        ON  ac.cost_source = g.cost_source
        AND ac.company_id = g.company_id
        AND ac.month_key = g.month_key
    LEFT JOIN allocated_salary_totals at
        ON  at.cost_source = g.cost_source
        AND at.company_id = g.company_id
        AND at.month_key = g.month_key
    LEFT JOIN covered_salary_cell_counts cc
        ON  cc.cost_source = g.cost_source
        AND cc.company_id = g.company_id
        AND cc.month_key = g.month_key
),
evaluated AS (
    SELECT
        m.*,
        CASE
            WHEN m.intended_salary_dkk <= 0.0 THEN 'MISSING_INTENDED_SALARY'
            -- Compare unrounded decimal amounts. salary_completeness_ratio is
            -- rounded for presentation only; using it here would incorrectly
            -- accept values just outside the inclusive 85%-125% band.
            WHEN ABS(m.signed_salary_gl_dkk) * 100
                   < m.intended_salary_dkk * 85 THEN 'BELOW_85_PERCENT'
            WHEN ABS(m.signed_salary_gl_dkk) * 100
                   > m.intended_salary_dkk * 125 THEN 'ABOVE_125_PERCENT'
            WHEN m.missing_salary_cell_count <> 0
              OR m.unexpected_salary_cell_count <> 0 THEN 'ALLOCATION_CELL_MISMATCH'
            WHEN m.allocation_gap_dkk > m.allowed_allocation_gap_dkk
                THEN 'ALLOCATION_AMOUNT_MISMATCH'
            ELSE 'COMPLETE'
        END AS completeness_status
    FROM metrics m
)
SELECT
    company_id,
    month_key,
    cost_source,
    intended_salary_dkk,
    signed_salary_gl_dkk,
    allocated_salary_dkk,
    salary_completeness_ratio,
    expected_salary_cell_count,
    actual_salary_cell_count,
    covered_salary_cell_count,
    missing_salary_cell_count,
    unexpected_salary_cell_count,
    allocation_gap_dkk,
    allowed_allocation_gap_dkk,
    'PRACTICE_SALARY_V1' AS rule_version,
    completeness_status,
    completeness_status = 'COMPLETE' AS complete
FROM evaluated;

-- Persist the small aggregate result so protected dashboard reads do not have
-- to re-evaluate the salary timeline view inside the 15-second CXO timeout.
-- All rows remain at company x month x cost-source grain; there is no employee
-- identifier or employee-level amount in this table.
CREATE TABLE IF NOT EXISTS fact_practice_salary_completeness_mat (
    company_id VARCHAR(36) NOT NULL,
    month_key CHAR(6) NOT NULL,
    cost_source VARCHAR(32) NOT NULL,
    intended_salary_dkk DECIMAL(20,2) NOT NULL,
    signed_salary_gl_dkk DECIMAL(20,2) NOT NULL,
    allocated_salary_dkk DECIMAL(20,2) NOT NULL,
    salary_completeness_ratio DECIMAL(18,6) NULL,
    expected_salary_cell_count INT NOT NULL,
    actual_salary_cell_count INT NOT NULL,
    covered_salary_cell_count INT NOT NULL,
    missing_salary_cell_count INT NOT NULL,
    unexpected_salary_cell_count INT NOT NULL,
    allocation_gap_dkk DECIMAL(20,2) NOT NULL,
    allowed_allocation_gap_dkk DECIMAL(20,2) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    completeness_status VARCHAR(64) NOT NULL,
    complete BOOLEAN NOT NULL,
    refreshed_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (company_id, month_key, cost_source),
    INDEX idx_fpscm_source_rule_month (cost_source, rule_version, month_key)
) ENGINE=InnoDB;

-- Exactly one row identifies the only set of fact_opex_mat,
-- fact_employee_monthly_mat, and completeness rows that may be served
-- together. Source row counts plus the common UTC generation let readers
-- detect TRUNCATE/INSERT races and unsupported direct/manual fact refreshes.
CREATE TABLE IF NOT EXISTS practice_operating_cost_publication (
    publication_id TINYINT NOT NULL,
    refresh_state ENUM('UNINITIALIZED', 'RUNNING', 'READY', 'FAILED')
        NOT NULL DEFAULT 'UNINITIALIZED',
    active_refresh_token CHAR(36) NULL,
    generation_at DATETIME(6) NULL COMMENT 'UTC',
    published_at DATETIME(6) NULL COMMENT 'UTC',
    opex_row_count BIGINT NOT NULL DEFAULT 0,
    fte_row_count BIGINT NOT NULL DEFAULT 0,
    completeness_row_count BIGINT NOT NULL DEFAULT 0,
    last_failure_at DATETIME(6) NULL COMMENT 'UTC',
    PRIMARY KEY (publication_id),
    CONSTRAINT chk_practice_operating_cost_singleton CHECK (publication_id = 1)
) ENGINE=InnoDB;

INSERT IGNORE INTO practice_operating_cost_publication (
    publication_id,
    refresh_state,
    active_refresh_token,
    generation_at,
    published_at,
    opex_row_count,
    fte_row_count,
    completeness_row_count,
    last_failure_at
) VALUES (1, 'UNINITIALIZED', NULL, NULL, NULL, 0, 0, 0, NULL);

DROP PROCEDURE IF EXISTS sp_replace_practice_salary_completeness_mat;

DELIMITER $$

CREATE PROCEDURE sp_replace_practice_salary_completeness_mat(
    IN p_refreshed_at DATETIME(6)
)
BEGIN
    DELETE FROM fact_practice_salary_completeness_mat;

    INSERT INTO fact_practice_salary_completeness_mat (
        company_id,
        month_key,
        cost_source,
        intended_salary_dkk,
        signed_salary_gl_dkk,
        allocated_salary_dkk,
        salary_completeness_ratio,
        expected_salary_cell_count,
        actual_salary_cell_count,
        covered_salary_cell_count,
        missing_salary_cell_count,
        unexpected_salary_cell_count,
        allocation_gap_dkk,
        allowed_allocation_gap_dkk,
        rule_version,
        completeness_status,
        complete,
        refreshed_at
    )
    SELECT
        company_id,
        month_key,
        cost_source,
        intended_salary_dkk,
        signed_salary_gl_dkk,
        allocated_salary_dkk,
        salary_completeness_ratio,
        expected_salary_cell_count,
        actual_salary_cell_count,
        covered_salary_cell_count,
        missing_salary_cell_count,
        unexpected_salary_cell_count,
        allocation_gap_dkk,
        allowed_allocation_gap_dkk,
        rule_version,
        completeness_status,
        complete,
        p_refreshed_at
    FROM v_practice_salary_completeness;
END$$

DELIMITER ;

DROP PROCEDURE IF EXISTS sp_refresh_practice_salary_completeness_mat;

DELIMITER $$

CREATE PROCEDURE sp_refresh_practice_salary_completeness_mat()
BEGIN
    DECLARE v_refreshed_at DATETIME(6) DEFAULT UTC_TIMESTAMP(6);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    CALL sp_replace_practice_salary_completeness_mat(v_refreshed_at);

    COMMIT;
END$$

DELIMITER ;

-- The orchestrator marks the publication RUNNING before any TRUNCATE begins.
DROP PROCEDURE IF EXISTS sp_begin_practice_operating_cost_publication;

DELIMITER $$

CREATE PROCEDURE sp_begin_practice_operating_cost_publication(
    IN p_refresh_token CHAR(36)
)
BEGIN
    IF p_refresh_token IS NULL OR CHAR_LENGTH(TRIM(p_refresh_token)) <> 36 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A valid practice publication refresh token is required';
    END IF;

    UPDATE practice_operating_cost_publication
    SET refresh_state = 'RUNNING',
        active_refresh_token = p_refresh_token
    WHERE publication_id = 1;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Practice operating-cost publication row is unavailable';
    END IF;
END$$

DELIMITER ;

-- Stamp both source tables and replace completeness metadata in one InnoDB
-- transaction. READY is not visible to the API until the outer BI
-- orchestrator also succeeds and finalizes this token.
DROP PROCEDURE IF EXISTS sp_stage_practice_operating_cost_publication;

DELIMITER $$

CREATE PROCEDURE sp_stage_practice_operating_cost_publication(
    IN p_refresh_token CHAR(36)
)
BEGIN
    DECLARE v_generation_at DATETIME(6) DEFAULT UTC_TIMESTAMP(6);
    DECLARE v_owner_count INT DEFAULT 0;
    DECLARE v_opex_row_count BIGINT DEFAULT 0;
    DECLARE v_fte_row_count BIGINT DEFAULT 0;
    DECLARE v_completeness_row_count BIGINT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        UPDATE practice_operating_cost_publication
        SET refresh_state = 'FAILED',
            active_refresh_token = NULL,
            last_failure_at = UTC_TIMESTAMP(6)
        WHERE publication_id = 1
          AND active_refresh_token = p_refresh_token;
        RESIGNAL;
    END;

    START TRANSACTION;

    SELECT publication_id INTO v_owner_count
    FROM practice_operating_cost_publication
    WHERE publication_id = 1
      AND refresh_state = 'RUNNING'
      AND active_refresh_token = p_refresh_token
    FOR UPDATE;

    IF v_owner_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Practice publication token does not own the active refresh';
    END IF;

    UPDATE fact_opex_mat
    SET materialized_at = v_generation_at;

    UPDATE fact_employee_monthly_mat
    SET materialized_at = v_generation_at;

    SELECT COUNT(*) INTO v_opex_row_count FROM fact_opex_mat;
    SELECT COUNT(*) INTO v_fte_row_count FROM fact_employee_monthly_mat;

    IF v_opex_row_count = 0 OR v_fte_row_count = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Practice operating-cost source facts are empty';
    END IF;

    CALL sp_replace_practice_salary_completeness_mat(v_generation_at);
    SELECT COUNT(*) INTO v_completeness_row_count
    FROM fact_practice_salary_completeness_mat;

    IF v_completeness_row_count = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Practice operating-cost completeness metadata is empty';
    END IF;

    UPDATE practice_operating_cost_publication
    SET refresh_state = 'READY',
        generation_at = v_generation_at,
        published_at = UTC_TIMESTAMP(6),
        opex_row_count = v_opex_row_count,
        fte_row_count = v_fte_row_count,
        completeness_row_count = v_completeness_row_count,
        last_failure_at = NULL
    WHERE publication_id = 1
      AND active_refresh_token = p_refresh_token;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Practice operating-cost publication could not be staged';
    END IF;

    COMMIT;
END$$

DELIMITER ;

DROP PROCEDURE IF EXISTS sp_finalize_practice_operating_cost_publication;

DELIMITER $$

CREATE PROCEDURE sp_finalize_practice_operating_cost_publication(
    IN p_refresh_token CHAR(36)
)
BEGIN
    UPDATE practice_operating_cost_publication
    SET active_refresh_token = NULL
    WHERE publication_id = 1
      AND refresh_state = 'READY'
      AND active_refresh_token = p_refresh_token;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Practice operating-cost publication could not be finalized';
    END IF;
END$$

DELIMITER ;

DROP PROCEDURE IF EXISTS sp_fail_practice_operating_cost_publication;

DELIMITER $$

CREATE PROCEDURE sp_fail_practice_operating_cost_publication(
    IN p_refresh_token CHAR(36)
)
BEGIN
    UPDATE practice_operating_cost_publication
    SET refresh_state = 'FAILED',
        active_refresh_token = NULL,
        last_failure_at = UTC_TIMESTAMP(6)
    WHERE publication_id = 1
      AND active_refresh_token = p_refresh_token;
END$$

DELIMITER ;

CALL sp_refresh_practice_salary_completeness_mat();
