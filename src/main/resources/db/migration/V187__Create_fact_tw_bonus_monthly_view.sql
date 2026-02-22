-- =============================================================================
-- fact_tw_bonus_monthly: Monthly TW Bonus salary and eligibility metrics
-- Grain: one row per (useruuid, companyuuid, year, month)
-- Sources: fact_user_day
-- Usage: YourPartOfTrustworksResource /basis endpoint, TW Bonus calculation
-- =============================================================================
CREATE OR REPLACE VIEW fact_tw_bonus_monthly AS
WITH eligible_days AS (
    SELECT
        f.useruuid,
        f.companyuuid,
        f.year,
        f.month,
        COUNT(*) AS total_days,
        SUM(CASE
            WHEN f.is_tw_bonus_eligible = 1
             AND f.status_type NOT IN ('PREBOARDING', 'TERMINATED', 'NON_PAY_LEAVE')
            THEN 1 ELSE 0
        END) AS eligible_days,
        SUM(COALESCE(f.salary, 0)) AS total_salary,
        SUM(CASE
            WHEN f.is_tw_bonus_eligible = 1
             AND f.status_type NOT IN ('PREBOARDING', 'TERMINATED', 'NON_PAY_LEAVE')
            THEN COALESCE(f.salary, 0) ELSE 0
        END) AS eligible_salary,
        DAY(LAST_DAY(CONCAT(f.year, '-', LPAD(f.month, 2, '0'), '-01'))) AS days_in_month
    FROM fact_user_day f
    WHERE f.consultant_type IN ('CONSULTANT', 'STAFF', 'STUDENT')
    GROUP BY f.useruuid, f.companyuuid, f.year, f.month
)
SELECT
    e.useruuid,
    e.companyuuid,
    e.year,
    e.month,
    e.days_in_month,
    e.total_days,
    e.eligible_days,
    ROUND(e.eligible_days / e.days_in_month, 6) AS eligible_share,
    ROUND(e.total_salary / e.days_in_month, 2) AS avg_salary,
    ROUND(e.eligible_salary / e.days_in_month, 2) AS weighted_avg_salary,
    CASE WHEN e.month >= 7 THEN e.year ELSE e.year - 1 END AS fiscal_year
FROM eligible_days e;
