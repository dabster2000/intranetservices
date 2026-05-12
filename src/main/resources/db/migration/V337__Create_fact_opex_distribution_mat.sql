-- =============================================================================
-- V337: Create fact_opex_distribution_mat
--
-- Purpose:
--   Materialized table that stores the output of the Java intercompany OPEX
--   distribution algorithm (DistributionAwareOpexProvider.computeDistributionForMonth)
--   at the same grain as fact_opex_mat. Populated by a nightly Quarkus batchlet
--   (OpexDistributionRefreshBatchlet, 03:30 UTC). Read by DistributionAwareOpexProvider
--   for unsettled months so the CXO EBITDA forecast endpoint can serve requests
--   in <100ms instead of >30s.
--
-- Grain: payer_company × cost_center × expense_category × is_payroll_flag × month_key
--
-- Refresh strategy:
--   The batchlet uses DELETE + INSERT inside a single transaction over a window
--   of [current_FY - 1, current_FY + 1) (~24 months, ~400 rows total). Idempotent.
--
-- Companion:
--   fact_operating_cost_distribution_mat (V197/V220) exists at a different grain
--   (origin × payer × account × month) and explicitly omits salary cap + lumps.
--   This table replaces it for EBITDA forecasting only; the V197 table is kept
--   for audit reporting.
-- =============================================================================

CREATE TABLE IF NOT EXISTS fact_opex_distribution_mat (
    opex_distribution_id  VARCHAR(200) NOT NULL,

    company_id            VARCHAR(36)  NOT NULL,
    cost_center_id        VARCHAR(50)  NOT NULL,
    expense_category_id   VARCHAR(50)  NOT NULL,
    month_key             VARCHAR(6)   NOT NULL,
    year                  SMALLINT     NOT NULL,
    month_number          TINYINT      NOT NULL,
    fiscal_year           SMALLINT     NOT NULL,
    fiscal_month_number   TINYINT      NOT NULL,
    fiscal_month_key      VARCHAR(10)  NOT NULL,
    cost_type             VARCHAR(20)  NOT NULL,

    opex_amount_dkk       DECIMAL(14,2) NOT NULL,
    is_payroll_flag       TINYINT       NOT NULL,
    invoice_count         INT           NOT NULL DEFAULT 1,

    data_source           VARCHAR(20)   NOT NULL DEFAULT 'DISTRIBUTION',
    refreshed_at          DATETIME      NOT NULL,

    PRIMARY KEY (opex_distribution_id),
    KEY idx_fodm_company_month   (company_id, month_key),
    KEY idx_fodm_month_key       (month_key),
    KEY idx_fodm_payroll_month   (is_payroll_flag, month_key),
    KEY idx_fodm_category_month  (expense_category_id, month_key),
    KEY idx_fodm_cost_center     (cost_center_id, month_key),
    KEY idx_fodm_refreshed_at    (refreshed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
