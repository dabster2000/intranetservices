-- =============================================================================
-- Migration V202: Create materialized table for fact_company_revenue
--                 and extend sp_refresh_fact_tables to include it.
--
-- Source view: fact_company_revenue (V201)
-- Grain: company_id × month_key (YYYYMM)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Materialized table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_company_revenue_mat (
    revenue_id            VARCHAR(50)    NOT NULL,
    company_id            VARCHAR(36)    NOT NULL,
    month_key             VARCHAR(6)     NOT NULL,
    year                  SMALLINT       NOT NULL,
    month_number          TINYINT        NOT NULL,
    fiscal_year           SMALLINT       NOT NULL,
    fiscal_month_number   TINYINT        NOT NULL,
    invoice_phantom_dkk   DECIMAL(14,2),
    internal_dkk          DECIMAL(14,2),
    credit_note_dkk       DECIMAL(14,2),
    net_revenue_dkk       DECIMAL(14,2),
    PRIMARY KEY (revenue_id),
    INDEX idx_fcrm_company_month (company_id, month_key),
    INDEX idx_fcrm_month_key (month_key),
    INDEX idx_fcrm_fiscal (fiscal_year, fiscal_month_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 2. Drop and recreate sp_refresh_fact_tables with fact_company_revenue_mat
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

    -- 6. fact_company_revenue_mat (NEW — V202)
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
-- 3. Initial population of the new mat table only
--    (full refresh happens nightly; just populate the new table now)
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 4. Clean up the orphaned fact_cr_base view left by the development agent
-- ---------------------------------------------------------------------------
DROP VIEW IF EXISTS fact_cr_base;
