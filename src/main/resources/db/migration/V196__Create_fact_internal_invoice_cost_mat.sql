-- =============================================================================
-- Migration V196: Materialize fact_internal_invoice_cost and register in
--                 sp_refresh_fact_tables
--
-- Purpose:
--   1. Create fact_internal_invoice_cost_mat — a materialized table (TRUNCATE
--      + INSERT strategy) backed by the V195 view.  This avoids running the
--      CTE-based view on every CXO Dashboard request.
--   2. Recreate sp_refresh_fact_tables to include the new table as block 8,
--      appended after the existing 7 blocks (preserved verbatim from V190).
--   3. Perform an initial population of the new mat table.
--
-- Grain: debtor_company_id × month_key  (same as the view)
--
-- Refresh strategy:
--   TRUNCATE + INSERT IGNORE — consistent with all other _mat tables.
--   Called nightly by sp_nightly_bi_refresh via sp_refresh_fact_tables.
--
-- Indexes:
--   PRIMARY KEY on (company_id, month_key) — the most common query pattern
--   is "give me all months for company X".
--   idx_fiicm_month_key — for cross-company monthly aggregations.
--   idx_fiicm_fiscal     — for fiscal year range scans.
--
-- Impact on Quarkus entities:
--   CxoFinanceService will query this table via native SQL (no entity/panache).
--   No existing entities are changed — this is a new table only.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Create materialized table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_internal_invoice_cost_mat (
    internal_invoice_cost_id  VARCHAR(50)     NOT NULL,
    company_id                VARCHAR(36)     NOT NULL,
    month_key                 VARCHAR(6)      NOT NULL,
    year                      SMALLINT        NOT NULL,
    month_number              TINYINT         NOT NULL,
    fiscal_year               SMALLINT        NOT NULL,
    fiscal_month_number       TINYINT         NOT NULL,
    internal_invoice_cost_dkk DECIMAL(16, 2),
    queued_cost_dkk           DECIMAL(16, 2),
    created_cost_dkk          DECIMAL(16, 2),
    entry_count               INT,
    data_sources              VARCHAR(50),
    PRIMARY KEY (company_id, month_key),
    UNIQUE KEY uq_fiicm_cost_id (internal_invoice_cost_id),
    INDEX idx_fiicm_month_key (month_key),
    INDEX idx_fiicm_fiscal (fiscal_year, fiscal_month_number),
    INDEX idx_fiicm_company_fiscal (company_id, fiscal_year)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'Materialized: intercompany (INTERNAL) invoice costs per debtor company per month. Refreshed nightly by sp_refresh_fact_tables.';

-- ---------------------------------------------------------------------------
-- 2. Recreate sp_refresh_fact_tables with block 8 appended
--    Blocks 1-7 are preserved verbatim from V190.
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

    -- 4. fact_opex_mat
    TRUNCATE TABLE fact_opex_mat;
    INSERT IGNORE INTO fact_opex_mat
        (opex_id, company_id, cost_center_id, expense_category_id,
         expense_subcategory_id, practice_id, sector_id,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number, fiscal_month_key,
         opex_amount_dkk, invoice_count, is_payroll_flag,
         data_source)
    SELECT
        opex_id, company_id, cost_center_id, expense_category_id,
        expense_subcategory_id, practice_id, sector_id,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number, fiscal_month_key,
        opex_amount_dkk, invoice_count, is_payroll_flag,
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

    -- 6. fact_tw_bonus_monthly_mat
    TRUNCATE TABLE fact_tw_bonus_monthly_mat;
    INSERT IGNORE INTO fact_tw_bonus_monthly_mat
        (useruuid, companyuuid, year, month,
         days_in_month, total_days, eligible_days,
         eligible_share, avg_salary, weighted_avg_salary,
         fiscal_year)
    SELECT
        useruuid, companyuuid, year, month,
        days_in_month, total_days, eligible_days,
        eligible_share, avg_salary, weighted_avg_salary,
        fiscal_year
    FROM fact_tw_bonus_monthly;

    -- 7. fact_tw_bonus_annual_mat
    TRUNCATE TABLE fact_tw_bonus_annual_mat;
    INSERT IGNORE INTO fact_tw_bonus_annual_mat
        (useruuid, companyuuid, fiscal_year,
         weight_sum, eligible_months, total_eligible_days,
         avg_monthly_salary)
    SELECT
        useruuid, companyuuid, fiscal_year,
        weight_sum, eligible_months, total_eligible_days,
        avg_monthly_salary
    FROM fact_tw_bonus_annual;

    -- 8. fact_internal_invoice_cost_mat
    --    Intercompany (INTERNAL) invoice costs per debtor company per month.
    --    Source: fact_internal_invoice_cost (V195).
    --    Status-based switch: QUEUED -> invoices+items; CREATED -> GL accounts.
    TRUNCATE TABLE fact_internal_invoice_cost_mat;
    INSERT IGNORE INTO fact_internal_invoice_cost_mat
        (internal_invoice_cost_id, company_id, month_key,
         year, month_number, fiscal_year, fiscal_month_number,
         internal_invoice_cost_dkk, queued_cost_dkk, created_cost_dkk,
         entry_count, data_sources)
    SELECT
        internal_invoice_cost_id, company_id, month_key,
        year, month_number, fiscal_year, fiscal_month_number,
        internal_invoice_cost_dkk, queued_cost_dkk, created_cost_dkk,
        entry_count, data_sources
    FROM fact_internal_invoice_cost;
END //

DELIMITER ;

-- ---------------------------------------------------------------------------
-- 3. Initial population
-- ---------------------------------------------------------------------------
CALL sp_refresh_fact_tables();
