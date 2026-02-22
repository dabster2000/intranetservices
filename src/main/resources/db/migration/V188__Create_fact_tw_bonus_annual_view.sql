-- =============================================================================
-- fact_tw_bonus_annual: Annual TW Bonus weight per employee per company
-- Grain: one row per (useruuid, companyuuid, fiscal_year)
-- Sources: fact_tw_bonus_monthly
-- Usage: Server-side TW Bonus calculation
-- =============================================================================
CREATE OR REPLACE VIEW fact_tw_bonus_annual AS
SELECT
    m.useruuid,
    m.companyuuid,
    m.fiscal_year,
    SUM(m.weighted_avg_salary) AS weight_sum,
    COUNT(CASE WHEN m.eligible_days > 0 THEN 1 END) AS eligible_months,
    SUM(m.eligible_days) AS total_eligible_days,
    ROUND(AVG(m.avg_salary), 2) AS avg_monthly_salary
FROM fact_tw_bonus_monthly m
GROUP BY m.useruuid, m.companyuuid, m.fiscal_year;
