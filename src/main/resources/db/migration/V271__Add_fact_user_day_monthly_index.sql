-- V271: Add composite index to speed up monthly utilization aggregation from fact_user_day.
--
-- Context: TeamDashboardService and ConsultantInsightsService are being migrated
-- from fact_user_utilization_mat (nightly refresh, monthly grain) to fact_user_day
-- (5-min refresh, daily grain). Queries aggregate daily rows into monthly totals
-- using GROUP BY useruuid, year, month. This index covers the canonical filter
-- (consultant_type + status_type) and the GROUP BY columns.
--
-- Impact: ~370K CONSULTANT rows in fact_user_day. Index size ~15MB.

ALTER TABLE fact_user_day
    ADD INDEX idx_fact_user_day_util_monthly (consultant_type, status_type, year, month, useruuid);
