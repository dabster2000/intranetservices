-- =============================================================================
-- Migration V190: TW Bonus materialized tables and updated refresh procedure
--
-- Purpose:
--   1. Create fact_tw_bonus_monthly_mat  (materialized from V187 view)
--   2. Create fact_tw_bonus_annual_mat   (materialized from V188 view)
--   3. Recreate sp_refresh_fact_tables to include all 7 tables (5 existing + 2 new)
--
-- The existing 5 table refresh blocks are preserved verbatim from V161.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. fact_tw_bonus_monthly_mat
-- Grain: useruuid × companyuuid × year × month
-- Source: fact_tw_bonus_monthly (V187)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_tw_bonus_monthly_mat (
    useruuid             VARCHAR(36)    NOT NULL,
    companyuuid          VARCHAR(36)    NOT NULL,
    year                 SMALLINT       NOT NULL,
    month                TINYINT        NOT NULL,
    days_in_month        TINYINT,
    total_days           INT,
    eligible_days        INT,
    eligible_share       DECIMAL(10,6),
    avg_salary           DECIMAL(14,2),
    weighted_avg_salary  DECIMAL(14,2),
    fiscal_year          SMALLINT,
    PRIMARY KEY (useruuid, companyuuid, year, month),
    INDEX idx_ftbmm_company_fy (companyuuid, fiscal_year),
    INDEX idx_ftbmm_fy (fiscal_year),
    INDEX idx_ftbmm_user_fy (useruuid, fiscal_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 2. fact_tw_bonus_annual_mat
-- Grain: useruuid × companyuuid × fiscal_year
-- Source: fact_tw_bonus_annual (V188)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_tw_bonus_annual_mat (
    useruuid               VARCHAR(36)    NOT NULL,
    companyuuid            VARCHAR(36)    NOT NULL,
    fiscal_year            SMALLINT       NOT NULL,
    weight_sum             DECIMAL(16,2),
    eligible_months        INT,
    total_eligible_days    INT,
    avg_monthly_salary     DECIMAL(14,2),
    PRIMARY KEY (useruuid, companyuuid, fiscal_year),
    INDEX idx_ftbam_company_fy (companyuuid, fiscal_year),
    INDEX idx_ftbam_fy (fiscal_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Stored Procedure: sp_refresh_fact_tables (updated)
-- Truncates and repopulates all 7 materialized fact tables from their views.
-- Blocks 1-5 are preserved verbatim from V161.
-- Blocks 6-7 are new for TW Bonus.
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
END //

DELIMITER ;

-- Initial population of the two new tables
CALL sp_refresh_fact_tables();
