-- =============================================================================
-- Migration V155: Add utilization performance indexes
--
-- Purpose:
--   Improve query performance for team utilization endpoints and
--   company utilization period queries. These indexes support the
--   refactored single-query team utilization endpoints (Phase 1.3)
--   and will also benefit Phase 2 stored procedures.
--
-- Indexes added:
--   bi_data_per_day:   idx_bdd_user_year_month       (useruuid, year, month)
--   bi_data_per_day:   idx_bdd_company_date_type      (companyuuid, document_date, consultant_type, status_type)
--   bi_budget_per_day: idx_bbpd_user_year_month       (useruuid, year, month)
--
-- Note: work_full is a VIEW (not a base table), so it cannot be indexed.
--       The underlying `work` table already has idx_work_user_reg(useruuid, registered)
--       which covers the JOIN pattern used by team actual utilization queries.
--
-- Uses CREATE INDEX IF NOT EXISTS for idempotency.
-- =============================================================================

-- Optimize team utilization queries that filter by user + year + month
CREATE INDEX IF NOT EXISTS idx_bdd_user_year_month
    ON bi_data_per_day (useruuid, year, month);

CREATE INDEX IF NOT EXISTS idx_bbpd_user_year_month
    ON bi_budget_per_day (useruuid, year, month);

-- Optimize company utilization period queries (used by fiscal year endpoint)
CREATE INDEX IF NOT EXISTS idx_bdd_company_date_type
    ON bi_data_per_day (companyuuid, document_date, consultant_type, status_type);
