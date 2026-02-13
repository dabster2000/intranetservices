-- =============================================================================
-- Migration V161: Create materialized fact tables and refresh procedure
--
-- Purpose:
--   Create table (materialized) versions of the 5 most-queried fact views
--   with proper indexes for fast dashboard queries. The original views are
--   retained for backward compatibility and validation.
--
-- Tables created:
--   1. fact_user_utilization_mat    (from V119 view)
--   2. fact_revenue_budget_mat      (from V120 view)
--   3. fact_project_financials_mat  (from V118 view)
--   4. fact_opex_mat                (from V125 view)
--   5. fact_employee_monthly_mat    (from V123 view)
--
-- Stored procedure:
--   sp_refresh_fact_tables()  — truncates and repopulates all _mat tables
--
-- Called by sp_nightly_bi_refresh (updated in V161b) as step 5.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. fact_user_utilization_mat
-- Grain: user_id × month_key
-- Source: fact_user_utilization (V119)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_user_utilization_mat (
    utilization_id       VARCHAR(50)    NOT NULL,
    user_id              VARCHAR(36)    NOT NULL,
    companyuuid          VARCHAR(36),
    practice_id          VARCHAR(50),
    client_id            VARCHAR(36),
    sector_id            VARCHAR(50),
    contract_type_id     VARCHAR(50),
    month_key            VARCHAR(6)     NOT NULL,
    year                 SMALLINT,
    month_number         TINYINT,
    gross_available_hours  DECIMAL(12,4),
    unavailable_hours      DECIMAL(12,4),
    vacation_hours         DECIMAL(12,4),
    sick_hours             DECIMAL(12,4),
    maternity_leave_hours  DECIMAL(12,4),
    non_payd_leave_hours   DECIMAL(12,4),
    paid_leave_hours       DECIMAL(12,4),
    net_available_hours    DECIMAL(12,4),
    billable_hours         DECIMAL(12,4),
    utilization_ratio      DECIMAL(10,6),
    PRIMARY KEY (user_id, month_key),
    INDEX idx_fuum_company_month (companyuuid, month_key),
    INDEX idx_fuum_practice_month (practice_id, month_key),
    INDEX idx_fuum_month_key (month_key),
    INDEX idx_fuum_util_ratio (utilization_ratio)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 2. fact_revenue_budget_mat
-- Grain: company_id × service_line × sector × contract_type × month_key
-- Source: fact_revenue_budget (V120)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_revenue_budget_mat (
    revenue_budget_id    VARCHAR(200)   NOT NULL,
    company_id           VARCHAR(36),
    service_line_id      VARCHAR(50),
    sector_id            VARCHAR(50),
    contract_type_id     VARCHAR(50),
    month_key            VARCHAR(6)     NOT NULL,
    year                 SMALLINT,
    month_number         TINYINT,
    fiscal_year          SMALLINT,
    fiscal_month_number  TINYINT,
    fiscal_month_key     VARCHAR(10),
    budget_scenario      VARCHAR(20),
    budget_revenue_dkk   DECIMAL(14,2),
    budget_hours         DECIMAL(12,2),
    contract_count       INT,
    consultant_count     INT,
    PRIMARY KEY (revenue_budget_id),
    INDEX idx_frbm_company_month (company_id, month_key),
    INDEX idx_frbm_month_key (month_key),
    INDEX idx_frbm_fiscal (fiscal_year, fiscal_month_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 3. fact_project_financials_mat
-- Grain: project_id × company × month_key
-- Source: fact_project_financials (V118)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_project_financials_mat (
    project_financial_id         VARCHAR(80)    NOT NULL,
    project_id                   VARCHAR(36)    NOT NULL,
    client_id                    VARCHAR(36),
    companyuuid                  VARCHAR(36),
    sector_id                    VARCHAR(50),
    service_line_id              VARCHAR(50),
    contract_type_id             VARCHAR(50),
    month_key                    VARCHAR(6)     NOT NULL,
    year                         SMALLINT,
    month_number                 TINYINT,
    recognized_revenue_dkk       DECIMAL(14,2),
    employee_salary_cost_dkk     DECIMAL(14,2),
    external_consultant_cost_dkk DECIMAL(14,2),
    project_expense_cost_dkk     DECIMAL(14,2),
    direct_delivery_cost_dkk     DECIMAL(14,2),
    total_hours                  DECIMAL(12,2),
    consultant_count             INT,
    data_source                  VARCHAR(20),
    PRIMARY KEY (project_financial_id),
    INDEX idx_fpfm_month_key (month_key),
    INDEX idx_fpfm_company_month (companyuuid, month_key),
    INDEX idx_fpfm_project_month (project_id, month_key),
    INDEX idx_fpfm_client (client_id),
    INDEX idx_fpfm_sector (sector_id, month_key),
    INDEX idx_fpfm_service_line (service_line_id, month_key),
    INDEX idx_fpfm_contract_type (contract_type_id, month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 4. fact_opex_mat
-- Grain: company × cost_center × expense_category × month_key
-- Source: fact_opex (V125)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_opex_mat (
    opex_id                VARCHAR(200)   NOT NULL,
    company_id             VARCHAR(36),
    cost_center_id         VARCHAR(50),
    expense_category_id    VARCHAR(50),
    expense_subcategory_id VARCHAR(50),
    practice_id            VARCHAR(50),
    sector_id              VARCHAR(50),
    month_key              VARCHAR(6)     NOT NULL,
    year                   SMALLINT,
    month_number           TINYINT,
    fiscal_year            SMALLINT,
    fiscal_month_number    TINYINT,
    fiscal_month_key       VARCHAR(10),
    opex_amount_dkk        DECIMAL(14,2),
    invoice_count          INT,
    is_payroll_flag        TINYINT,
    data_source            VARCHAR(20),
    PRIMARY KEY (opex_id),
    INDEX idx_fom_company_month (company_id, month_key),
    INDEX idx_fom_month_key (month_key),
    INDEX idx_fom_payroll (is_payroll_flag, month_key),
    INDEX idx_fom_category (expense_category_id, month_key),
    INDEX idx_fom_cost_center (cost_center_id, month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 5. fact_employee_monthly_mat
-- Grain: company × practice × month_key
-- Source: fact_employee_monthly (V123)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_employee_monthly_mat (
    employee_month_id      VARCHAR(200)   NOT NULL,
    company_id             VARCHAR(36),
    practice_id            VARCHAR(50),
    role_type              VARCHAR(20),
    month_key              VARCHAR(6)     NOT NULL,
    year                   SMALLINT,
    month_number           TINYINT,
    fiscal_year            SMALLINT,
    fiscal_month_number    TINYINT,
    headcount_start        INT,
    headcount_end          INT,
    average_headcount      DECIMAL(10,2),
    billable_headcount     INT,
    non_billable_headcount INT,
    fte_total              DECIMAL(10,2),
    fte_billable           DECIMAL(10,2),
    fte_non_billable       DECIMAL(10,2),
    joiners_count          INT,
    leavers_count          INT,
    voluntary_leavers_count INT,
    data_source            VARCHAR(20),
    PRIMARY KEY (employee_month_id),
    INDEX idx_femm_company_month (company_id, month_key),
    INDEX idx_femm_month_key (month_key),
    INDEX idx_femm_practice (practice_id, month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------------
-- Stored Procedure: sp_refresh_fact_tables
-- Truncates and repopulates all materialized fact tables from their views.
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
END //

DELIMITER ;

-- Initial population
CALL sp_refresh_fact_tables();
