-- =============================================================================
-- Migration V206: Add cost_type to fact_opex_mat and update sp_refresh_fact_tables
--
-- Purpose:
--   fact_opex (V205) now outputs a cost_type column ('OPEX' or 'SALARIES').
--   The materialized table fact_opex_mat was created in V161 with an explicit
--   column list that does not include cost_type. The stored procedure
--   sp_refresh_fact_tables() also uses an explicit column list for the
--   fact_opex_mat INSERT — meaning cost_type would be silently skipped and
--   every row in fact_opex_mat would default to NULL for cost_type.
--
--   This migration:
--     1. Adds cost_type column to fact_opex_mat (ALTER TABLE)
--     2. Adds an index for cost_type queries on fact_opex_mat
--     3. Recreates sp_refresh_fact_tables() with cost_type included in the
--        fact_opex_mat INSERT/SELECT (full procedure must be recreated because
--        MariaDB does not support ALTER PROCEDURE body changes)
--     4. Immediately repopulates fact_opex_mat from the updated fact_opex view
--        so the mat table is current without waiting for the nightly refresh
--
-- sp_refresh_fact_tables() version history:
--   V161: created with 5 mat tables
--   V202: added fact_company_revenue_mat (6th mat table) — this is the
--         current version of the procedure before V206.
--   V206: adds cost_type to fact_opex_mat INSERT/SELECT (no other changes)
--
-- Backwards compatibility:
--   All other mat tables and their INSERT/SELECT lists are reproduced exactly
--   as they appear in V202 — no other changes.
--
-- Idempotent:
--   ALTER TABLE ... ADD COLUMN IF NOT EXISTS — safe to re-run.
--   CREATE INDEX IF NOT EXISTS              — safe to re-run.
--   DROP PROCEDURE IF EXISTS + CREATE       — safe to re-run.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Add cost_type column to fact_opex_mat
--    DEFAULT 'OTHER' gives existing rows a defined value until the next
--    sp_refresh_fact_tables() call repopulates from fact_opex (which only
--    emits 'OPEX' or 'SALARIES' rows — 'OTHER' rows are excluded by the view).
-- ---------------------------------------------------------------------------
ALTER TABLE fact_opex_mat
    ADD COLUMN IF NOT EXISTS cost_type VARCHAR(20) NOT NULL DEFAULT 'OTHER';

-- ---------------------------------------------------------------------------
-- 2. Index for cost_type filter queries on fact_opex_mat
--    Supports: WHERE cost_type = 'SALARIES' AND month_key BETWEEN ...
--    (CxoFinanceService SALARIES queries, BI tools, admin reporting)
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_fom_cost_type
    ON fact_opex_mat (cost_type, month_key);

-- ---------------------------------------------------------------------------
-- 3. Recreate sp_refresh_fact_tables() with cost_type in fact_opex_mat section
--    Full procedure body is reproduced from V202 with a single addition:
--    cost_type added to columns list and SELECT list for fact_opex_mat only.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_refresh_fact_tables;

DELIMITER //

CREATE PROCEDURE sp_refresh_fact_tables()
BEGIN
    -- 1. fact_user_utilization_mat
    TRUNCATE TABLE fact_user_utilization_mat;
    INSERT IGNORE INTO fact_user_utilization_mat
        (utilization_id, user_id, companyuuid, practice_id, client_id,
         sector_id, contract_type_id, month_key, year, month_number,
         gross_available_hours, unavailable_hours, vacation_hours,
         sick_hours, maternity_leave_hours, non_payd_leave_hours,
         paid_leave_hours, net_available_hours, billable_hours,
         utilization_ratio)
    SELECT
        utilization_id, user_id, companyuuid, practice_id, client_id,
        sector_id, contract_type_id, month_key, year, month_number,
        gross_available_hours, unavailable_hours, vacation_hours,
        sick_hours, maternity_leave_hours, non_payd_leave_hours,
        paid_leave_hours, net_available_hours, billable_hours,
        utilization_ratio
    FROM fact_user_utilization;

    -- 2. fact_revenue_budget_mat
    TRUNCATE TABLE fact_revenue_budget_mat;
    INSERT IGNORE INTO fact_revenue_budget_mat
        (revenue_budget_id, company_id, service_line_id, sector_id,
         contract_type_id, month_key, year, month_number,
         fiscal_year, fiscal_month_number, fiscal_month_key,
         budget_scenario, budget_revenue_dkk, budget_hours,
         contract_count, consultant_count)
    SELECT
        revenue_budget_id, company_id, service_line_id, sector_id,
        contract_type_id, month_key, year, month_number,
        fiscal_year, fiscal_month_number, fiscal_month_key,
        budget_scenario, budget_revenue_dkk, budget_hours,
        contract_count, consultant_count
    FROM fact_revenue_budget;

    -- 3. fact_project_financials_mat
    TRUNCATE TABLE fact_project_financials_mat;
    INSERT IGNORE INTO fact_project_financials_mat
        (project_financial_id, project_id, client_id, companyuuid,
         sector_id, service_line_id, contract_type_id,
         month_key, year, month_number,
         recognized_revenue_dkk, employee_salary_cost_dkk,
         external_consultant_cost_dkk, project_expense_cost_dkk,
         direct_delivery_cost_dkk, total_hours, consultant_count,
         data_source)
    SELECT
        project_financial_id, project_id, client_id, companyuuid,
        sector_id, service_line_id, contract_type_id,
        month_key, year, month_number,
        recognized_revenue_dkk, employee_salary_cost_dkk,
        external_consultant_cost_dkk, project_expense_cost_dkk,
        direct_delivery_cost_dkk, total_hours, consultant_count,
        data_source
    FROM fact_project_financials;

    -- 4. fact_opex_mat (V206: cost_type added to column list and SELECT)
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

    -- 5. fact_employee_monthly_mat
    TRUNCATE TABLE fact_employee_monthly_mat;
    INSERT IGNORE INTO fact_employee_monthly_mat
        (employee_month_id, company_id, practice_id, role_type,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number,
         headcount_start, headcount_end, average_headcount,
         billable_headcount, non_billable_headcount,
         fte_total, fte_billable, fte_non_billable,
         joiners_count, leavers_count,
         voluntary_leavers_count, data_source)
    SELECT
        employee_month_id, company_id, practice_id, role_type,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number,
        headcount_start, headcount_end, average_headcount,
        billable_headcount, non_billable_headcount,
        fte_total, fte_billable, fte_non_billable,
        joiners_count, leavers_count,
        voluntary_leavers_count, data_source
    FROM fact_employee_monthly;

    -- 6. fact_company_revenue_mat (added in V202 — unchanged)
    TRUNCATE TABLE fact_company_revenue_mat;
    INSERT IGNORE INTO fact_company_revenue_mat
        (revenue_id, company_id, month_key, year, month_number,
         fiscal_year, fiscal_month_number,
         invoice_phantom_dkk, internal_dkk, credit_note_dkk,
         net_revenue_dkk)
    SELECT
        revenue_id, company_id, month_key, year, month_number,
        fiscal_year, fiscal_month_number,
        invoice_phantom_dkk, internal_dkk, credit_note_dkk,
        net_revenue_dkk
    FROM fact_company_revenue;
END //

DELIMITER ;

-- ---------------------------------------------------------------------------
-- 4. Immediate repopulation of fact_opex_mat only
--    Repopulates from the updated fact_opex view (V205) so the mat table
--    contains correct cost_type values immediately without waiting for the
--    nightly sp_nightly_bi_refresh() run.
--    Other mat tables are not touched — they are unaffected by this migration.
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
