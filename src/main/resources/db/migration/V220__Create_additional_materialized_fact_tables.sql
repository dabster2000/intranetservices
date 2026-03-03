-- =============================================================================
-- Migration V220: Create additional materialized fact tables and update refresh
--
-- Purpose:
--   Create table (materialized) versions of 4 slow fact views used by CXO
--   Dashboard endpoints. The original views are retained for validation and
--   ad-hoc queries.
--
-- Tables created:
--   8.  fact_client_revenue_mat              (from V209 view)
--   9.  fact_operating_cost_distribution_mat (from V197 view)
--   10. fact_intercompany_settlement_mat     (from V198 view)
--   11. fact_minimum_viable_rate_mat         (from V207 view)
--
-- Stored procedure:
--   sp_refresh_fact_tables() — updated to truncate and repopulate all 11 _mat
--   tables (7 existing from V190 + 4 new).
--
-- Called by sp_nightly_bi_refresh as part of the nightly BI pipeline.
--
-- Performance impact:
--   fact_minimum_viable_rate:          >10 min → <50ms
--   fact_intercompany_settlement:      9.3s    → <50ms
--   fact_operating_cost_distribution:  5.6s    → <50ms
--   fact_client_revenue:               1.7s    → <50ms
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 8. fact_client_revenue_mat
-- Grain: client_id × company_id × month_key
-- Source: fact_client_revenue (V209)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_client_revenue_mat (
    client_revenue_id       VARCHAR(200)   NOT NULL,
    client_id               VARCHAR(36),
    company_id              VARCHAR(36),
    month_key               VARCHAR(6)     NOT NULL,
    year                    SMALLINT,
    month_number            TINYINT,
    fiscal_year             SMALLINT,
    fiscal_month_number     TINYINT,
    invoice_phantom_dkk     DECIMAL(14,2),
    internal_dkk            DECIMAL(14,2),
    credit_note_dkk         DECIMAL(14,2),
    net_revenue_dkk         DECIMAL(14,2),
    PRIMARY KEY (client_revenue_id),
    INDEX idx_fcrm_client_month (client_id, month_key),
    INDEX idx_fcrm_company_month (company_id, month_key),
    INDEX idx_fcrm_month_key (month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 9. fact_operating_cost_distribution_mat
-- Grain: origin_company × payer_company × account_code × month
-- Source: fact_operating_cost_distribution (V197)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_operating_cost_distribution_mat (
    distribution_id         VARCHAR(200)   NOT NULL,
    origin_company          VARCHAR(36),
    payer_company           VARCHAR(36),
    account_code            VARCHAR(20),
    category_uuid           VARCHAR(36),
    shared                  TINYINT,
    salary                  TINYINT,
    year_val                SMALLINT,
    month_val               TINYINT,
    month_key               VARCHAR(6)     NOT NULL,
    fiscal_year             SMALLINT,
    fiscal_month_number     TINYINT,
    origin_gl_amount        DECIMAL(14,2),
    payer_ratio             DECIMAL(10,6),
    payer_consultants       INT,
    allocated_amount        DECIMAL(14,2),
    intercompany_owe        DECIMAL(14,2),
    PRIMARY KEY (distribution_id),
    INDEX idx_focdm_origin_month (origin_company, month_key),
    INDEX idx_focdm_payer_month (payer_company, month_key),
    INDEX idx_focdm_fiscal_year (fiscal_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 10. fact_intercompany_settlement_mat
-- Grain: payer_company × receiver_company × month_key
-- Source: fact_intercompany_settlement (V198)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_intercompany_settlement_mat (
    settlement_id           VARCHAR(200)   NOT NULL,
    payer_company           VARCHAR(36),
    receiver_company        VARCHAR(36),
    year_val                SMALLINT,
    month_val               TINYINT,
    month_key               VARCHAR(6)     NOT NULL,
    fiscal_year             SMALLINT,
    fiscal_month_number     TINYINT,
    expected_amount         DECIMAL(14,2),
    actual_amount           DECIMAL(14,2),
    settlement_gap          DECIMAL(14,2),
    invoice_count           INT,
    settlement_status       VARCHAR(20),
    PRIMARY KEY (settlement_id),
    INDEX idx_fism_payer_receiver_month (payer_company, receiver_company, month_key),
    INDEX idx_fism_fiscal_year (fiscal_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 11. fact_minimum_viable_rate_mat
-- Grain: company_id × career_level
-- Source: fact_minimum_viable_rate (V207)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fact_minimum_viable_rate_mat (
    rate_id                       VARCHAR(200)   NOT NULL,
    company_id                    VARCHAR(36),
    company_name                  VARCHAR(50),
    career_level                  VARCHAR(50),
    consultant_count              INT,
    avg_monthly_salary_dkk        DECIMAL(14,2),
    employer_pension_pct          DECIMAL(10,4),
    employer_pension_dkk          DECIMAL(14,2),
    atp_per_person_dkk            DECIMAL(14,2),
    am_bidrag_per_person_dkk      DECIMAL(14,2),
    benefit_per_person_dkk        DECIMAL(14,2),
    staff_allocation_dkk          DECIMAL(14,2),
    overhead_allocation_dkk       DECIMAL(14,2),
    total_monthly_cost_dkk        DECIMAL(14,2),
    avg_net_available_hours       DECIMAL(12,2),
    actual_utilization_ratio      DECIMAL(10,4),
    actual_billable_hours         DECIMAL(12,2),
    target_utilization_ratio      DECIMAL(10,2),
    target_billable_hours         DECIMAL(12,2),
    break_even_rate_actual        DECIMAL(14,2),
    break_even_rate_target        DECIMAL(14,2),
    min_rate_15pct_margin         DECIMAL(14,2),
    min_rate_20pct_margin         DECIMAL(14,2),
    avg_actual_billing_rate       DECIMAL(14,2),
    rate_buffer_dkk               DECIMAL(14,2),
    data_source                   VARCHAR(20),
    break_even_utilization_pct    DECIMAL(10,4),
    break_even_utilization_15pct  DECIMAL(10,4),
    break_even_utilization_20pct  DECIMAL(10,4),
    PRIMARY KEY (rate_id),
    INDEX idx_fmvrm_company_career (company_id, career_level),
    INDEX idx_fmvrm_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------------
-- Stored Procedure: sp_refresh_fact_tables (updated)
-- Truncates and repopulates all 11 materialized fact tables from their views.
-- Blocks 1-7 are preserved verbatim from V190.
-- Blocks 8-11 are new for this migration.
--
-- Ordering: fact_operating_cost_distribution_mat (block 9) is refreshed
-- before fact_intercompany_settlement_mat (block 10) because the settlement
-- view depends on the distribution view.
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

    -- 8. fact_client_revenue_mat
    TRUNCATE TABLE fact_client_revenue_mat;
    INSERT IGNORE INTO fact_client_revenue_mat
        (client_revenue_id, client_id, company_id,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number,
         invoice_phantom_dkk, internal_dkk,
         credit_note_dkk, net_revenue_dkk)
    SELECT
        client_revenue_id, client_id, company_id,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number,
        invoice_phantom_dkk, internal_dkk,
        credit_note_dkk, net_revenue_dkk
    FROM fact_client_revenue;

    -- 9. fact_operating_cost_distribution_mat
    --    Refreshed before fact_intercompany_settlement_mat (dependency order)
    TRUNCATE TABLE fact_operating_cost_distribution_mat;
    INSERT IGNORE INTO fact_operating_cost_distribution_mat
        (distribution_id, origin_company, payer_company,
         account_code, category_uuid, shared, salary,
         year_val, month_val, month_key,
         fiscal_year, fiscal_month_number,
         origin_gl_amount, payer_ratio, payer_consultants,
         allocated_amount, intercompany_owe)
    SELECT
        distribution_id, origin_company, payer_company,
        account_code, category_uuid, shared, salary,
        year_val, month_val, month_key,
        fiscal_year, fiscal_month_number,
        origin_gl_amount, payer_ratio, payer_consultants,
        allocated_amount, intercompany_owe
    FROM fact_operating_cost_distribution;

    -- 10. fact_intercompany_settlement_mat
    TRUNCATE TABLE fact_intercompany_settlement_mat;
    INSERT IGNORE INTO fact_intercompany_settlement_mat
        (settlement_id, payer_company, receiver_company,
         year_val, month_val, month_key,
         fiscal_year, fiscal_month_number,
         expected_amount, actual_amount, settlement_gap,
         invoice_count, settlement_status)
    SELECT
        CONCAT(payer_company, '-', receiver_company, '-', month_key),
        payer_company, receiver_company,
        year_val, month_val, month_key,
        fiscal_year, fiscal_month_number,
        expected_amount, actual_amount, settlement_gap,
        invoice_count, settlement_status
    FROM fact_intercompany_settlement;

    -- 11. fact_minimum_viable_rate_mat
    TRUNCATE TABLE fact_minimum_viable_rate_mat;
    INSERT IGNORE INTO fact_minimum_viable_rate_mat
        (rate_id, company_id, company_name, career_level,
         consultant_count, avg_monthly_salary_dkk,
         employer_pension_pct, employer_pension_dkk,
         atp_per_person_dkk, am_bidrag_per_person_dkk,
         benefit_per_person_dkk, staff_allocation_dkk,
         overhead_allocation_dkk, total_monthly_cost_dkk,
         avg_net_available_hours, actual_utilization_ratio,
         actual_billable_hours, target_utilization_ratio,
         target_billable_hours, break_even_rate_actual,
         break_even_rate_target, min_rate_15pct_margin,
         min_rate_20pct_margin, avg_actual_billing_rate,
         rate_buffer_dkk, data_source,
         break_even_utilization_pct, break_even_utilization_15pct,
         break_even_utilization_20pct)
    SELECT
        rate_id, company_id, company_name, career_level,
        consultant_count, avg_monthly_salary_dkk,
        employer_pension_pct, employer_pension_dkk,
        atp_per_person_dkk, am_bidrag_per_person_dkk,
        benefit_per_person_dkk, staff_allocation_dkk,
        overhead_allocation_dkk, total_monthly_cost_dkk,
        avg_net_available_hours, actual_utilization_ratio,
        actual_billable_hours, target_utilization_ratio,
        target_billable_hours, break_even_rate_actual,
        break_even_rate_target, min_rate_15pct_margin,
        min_rate_20pct_margin, avg_actual_billing_rate,
        rate_buffer_dkk, data_source,
        break_even_utilization_pct, break_even_utilization_15pct,
        break_even_utilization_20pct
    FROM fact_minimum_viable_rate;
END //

DELIMITER ;

-- ---------------------------------------------------------------------------
-- Initial population of the 4 NEW tables only.
--
-- We do NOT call sp_refresh_fact_tables() here because it re-populates all 11
-- tables (including the 7 existing ones) and the nested view expansions for
-- fact_employee_monthly and fact_minimum_viable_rate can take 30+ minutes on
-- production, blocking Flyway migration and server startup.
--
-- The existing 7 _mat tables retain their data from previous refreshes.
-- The nightly sp_nightly_bi_refresh job will refresh all 11 tables.
-- ---------------------------------------------------------------------------

-- 8. fact_client_revenue_mat (source view: ~2s)
INSERT IGNORE INTO fact_client_revenue_mat
    (client_revenue_id, client_id, company_id,
     month_key, year, month_number,
     fiscal_year, fiscal_month_number,
     invoice_phantom_dkk, internal_dkk,
     credit_note_dkk, net_revenue_dkk)
SELECT
    client_revenue_id, client_id, company_id,
    month_key, year, month_number,
    fiscal_year, fiscal_month_number,
    invoice_phantom_dkk, internal_dkk,
    credit_note_dkk, net_revenue_dkk
FROM fact_client_revenue;

-- 9. fact_operating_cost_distribution_mat (source view: ~6s)
INSERT IGNORE INTO fact_operating_cost_distribution_mat
    (distribution_id, origin_company, payer_company,
     account_code, category_uuid, shared, salary,
     year_val, month_val, month_key,
     fiscal_year, fiscal_month_number,
     origin_gl_amount, payer_ratio, payer_consultants,
     allocated_amount, intercompany_owe)
SELECT
    distribution_id, origin_company, payer_company,
    account_code, category_uuid, shared, salary,
    year_val, month_val, month_key,
    fiscal_year, fiscal_month_number,
    origin_gl_amount, payer_ratio, payer_consultants,
    allocated_amount, intercompany_owe
FROM fact_operating_cost_distribution;

-- 10. fact_intercompany_settlement_mat (source view: ~10s, depends on #9 view)
INSERT IGNORE INTO fact_intercompany_settlement_mat
    (settlement_id, payer_company, receiver_company,
     year_val, month_val, month_key,
     fiscal_year, fiscal_month_number,
     expected_amount, actual_amount, settlement_gap,
     invoice_count, settlement_status)
SELECT
    CONCAT(payer_company, '-', receiver_company, '-', month_key),
    payer_company, receiver_company,
    year_val, month_val, month_key,
    fiscal_year, fiscal_month_number,
    expected_amount, actual_amount, settlement_gap,
    invoice_count, settlement_status
FROM fact_intercompany_settlement;

-- 11. fact_minimum_viable_rate_mat
--     NOT populated here — the source view takes 10-15+ minutes because it
--     expands fact_opex and fact_employee_monthly as nested views with
--     correlated subqueries. Populated by the nightly sp_refresh_fact_tables()
--     call, or manually via:
--       INSERT IGNORE INTO fact_minimum_viable_rate_mat (...) SELECT ... FROM fact_minimum_viable_rate;
