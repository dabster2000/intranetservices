-- =============================================================================
-- Covering index for fact_tw_bonus_monthly view query pattern
-- Columns match the WHERE, GROUP BY, and SELECT of the view's CTE
-- =============================================================================
CREATE INDEX idx_fact_user_day_tw_bonus
ON fact_user_day (consultant_type, useruuid, companyuuid, year, month, is_tw_bonus_eligible, status_type, salary);
