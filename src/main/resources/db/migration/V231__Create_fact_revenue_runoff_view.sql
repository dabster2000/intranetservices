-- =============================================================================
-- Migration V231: Create fact_revenue_runoff view
--
-- Purpose:
--   Projects revenue from active contracts over the next 12 months forward.
--   Uses fact_budget_day (renamed from bi_budget_per_day in V168) which already
--   contains pre-calculated daily budget entries with:
--   - Weekend exclusion (Mon-Fri only)
--   - Availability adjustment (vacation, parental leave, sick leave)
--   - Rate adjusted for contract discounts
--
--   Enables:
--   - Revenue cliff detection (when does contracted revenue drop?)
--   - Forward revenue coverage analysis
--   - Contract expiration impact forecasting
--
-- Grain: Contract-FutureMonth (one row per contract per future month)
--
-- Key design decisions:
--   - Aggregates fact_budget_day rather than recalculating from contracts
--     (leverages existing budget pipeline with all adjustments)
--   - Contract active dates derived from contract_consultants (V183 removed
--     activefrom/activeto from contracts table)
--   - Active contracts: status IN ('SIGNED', 'TIME', 'BUDGET')
--   - Practice from dominant consultant (user.practice, post-V213 rename)
--   - is_expired flag marks months where contract has ended
--   - 12-month forward window from current month
--
-- Dependencies:
--   - fact_budget_day table (V168 rename from bi_budget_per_day)
--   - contracts table
--   - contract_consultants table (for date range and practice)
--   - user table (practice column)
--
-- Rollback: DROP VIEW IF EXISTS fact_revenue_runoff;
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_revenue_runoff` AS

WITH
    -- 1) Current month start for the 12-month window
    current_month AS (
        SELECT DATE(CONCAT(YEAR(CURDATE()), '-', LPAD(MONTH(CURDATE()), 2, '0'), '-01')) AS month_start
    ),

    -- 2) Contract date ranges derived from contract_consultants
    contract_dates AS (
        SELECT
            contractuuid,
            MIN(activefrom) AS activefrom,
            MAX(activeto)   AS activeto
        FROM contract_consultants
        GROUP BY contractuuid
    ),

    -- 3) Dominant practice per contract
    contract_practice AS (
        SELECT
            cc.contractuuid AS contract_uuid,
            COALESCE(
                (SELECT u.practice
                 FROM contract_consultants cc2
                 JOIN user u ON cc2.useruuid = u.uuid
                 WHERE cc2.contractuuid = cc.contractuuid
                   AND u.practice IS NOT NULL
                 GROUP BY u.practice
                 ORDER BY COUNT(*) DESC, SUM(cc2.hours) DESC
                 LIMIT 1),
                'UD'
            ) AS practice
        FROM contract_consultants cc
        GROUP BY cc.contractuuid
    ),

    -- 4) Aggregate fact_budget_day by contract-month for the next 12 months
    budget_by_month AS (
        SELECT
            fbd.contractuuid,
            fbd.clientuuid,
            fbd.companyuuid,
            fbd.year,
            fbd.month,
            SUM(fbd.budgetHours * fbd.rate) AS monthly_revenue_dkk
        FROM fact_budget_day fbd
        CROSS JOIN current_month cm
        WHERE fbd.budgetHours > 0
          AND DATE(CONCAT(fbd.year, '-', LPAD(fbd.month, 2, '0'), '-01')) >= cm.month_start
          AND DATE(CONCAT(fbd.year, '-', LPAD(fbd.month, 2, '0'), '-01')) < DATE_ADD(cm.month_start, INTERVAL 12 MONTH)
        GROUP BY fbd.contractuuid, fbd.clientuuid, fbd.companyuuid, fbd.year, fbd.month
    )

SELECT
    bm.contractuuid AS contract_uuid,
    bm.clientuuid AS client_uuid,
    COALESCE(bm.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_uuid,
    CONCAT(LPAD(bm.year, 4, '0'), LPAD(bm.month, 2, '0')) AS future_month,
    DATE(CONCAT(bm.year, '-', LPAD(bm.month, 2, '0'), '-01')) AS future_month_date,
    bm.monthly_revenue_dkk,
    -- is_expired: contract end date is before the end of this future month
    CASE
        WHEN cd.activeto < LAST_DAY(DATE(CONCAT(bm.year, '-', LPAD(bm.month, 2, '0'), '-01')))
            THEN 1
        ELSE 0
    END AS is_expired,
    COALESCE(cp.practice, 'UD') AS practice,
    CASE WHEN c.parentuuid IS NOT NULL THEN 1 ELSE 0 END AS is_extension,
    cd.activeto AS contract_end_date

FROM budget_by_month bm
JOIN contracts c ON bm.contractuuid = c.uuid
LEFT JOIN contract_dates cd ON bm.contractuuid = cd.contractuuid
LEFT JOIN contract_practice cp ON bm.contractuuid = cp.contract_uuid
WHERE c.status IN ('SIGNED', 'TIME', 'BUDGET')
  AND bm.monthly_revenue_dkk > 0
ORDER BY bm.year, bm.month, bm.contractuuid;
