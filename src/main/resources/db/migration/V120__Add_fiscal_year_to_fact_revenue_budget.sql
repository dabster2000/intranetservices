-- Migration V120: Add Fiscal Year Columns to fact_revenue_budget
--
-- Purpose:
-- - Add fiscal_year, fiscal_month_number, and fiscal_month_key columns
-- - Enable fiscal year queries without complex WHERE clauses
-- - Align with Java DateUtils.getCurrentFiscalStartDate() logic (July-June)
--
-- Fiscal Year Logic (Trustworks):
-- - Fiscal year starts July 1st (month 7)
-- - FY2024 runs from July 1, 2024 through June 30, 2025
-- - fiscal_month_number: 1=July, 2=Aug, ..., 6=Dec, 7=Jan, ..., 12=June
--
-- Backward Compatibility:
-- - Existing calendar year columns (year, month_number) remain unchanged
-- - No breaking changes for existing queries

CREATE OR REPLACE ALGORITHM=UNDEFINED
SQL SECURITY DEFINER
VIEW `fact_revenue_budget` AS
WITH budget_with_dimensions AS (
    SELECT
        b.companyuuid,
        b.clientuuid,
        b.contractuuid,
        b.useruuid,
        b.year AS year_val,
        b.month AS month_val,
        b.budgetHours,
        b.rate,
        COALESCE(u.primaryskilltype, 'UD') AS service_line_id,
        COALESCE(c.segment, 'OTHER') AS sector_id,
        COALESCE(ct.contracttype, 'PERIOD') AS contract_type_id
    FROM bi_budget_per_day b
    LEFT JOIN user u ON b.useruuid = u.uuid
    LEFT JOIN client c ON b.clientuuid = c.uuid
    LEFT JOIN contracts ct ON b.contractuuid = ct.uuid
    WHERE b.budgetHours > 0
      AND b.document_date IS NOT NULL
      AND b.companyuuid IS NOT NULL
),
budget_aggregated AS (
    SELECT
        bd.companyuuid,
        bd.service_line_id,
        bd.sector_id,
        bd.contract_type_id,
        bd.year_val,
        bd.month_val,
        SUM(bd.budgetHours * bd.rate) AS budget_revenue_dkk,
        SUM(bd.budgetHours) AS budget_hours,
        COUNT(DISTINCT bd.contractuuid) AS contract_count,
        COUNT(DISTINCT bd.useruuid) AS consultant_count
    FROM budget_with_dimensions bd
    GROUP BY bd.companyuuid, bd.service_line_id, bd.sector_id, bd.contract_type_id, bd.year_val, bd.month_val
)
SELECT
    -- Primary Key
    CONCAT(
        ba.companyuuid, '-',
        ba.service_line_id, '-',
        ba.sector_id, '-',
        ba.contract_type_id, '-',
        LPAD(ba.year_val, 4, '0'),
        LPAD(ba.month_val, 2, '0')
    ) AS revenue_budget_id,

    -- Dimension Columns
    ba.companyuuid AS company_id,
    ba.service_line_id,
    ba.sector_id,
    ba.contract_type_id,

    -- Calendar Time Dimensions (existing)
    CONCAT(LPAD(ba.year_val, 4, '0'), LPAD(ba.month_val, 2, '0')) AS month_key,
    ba.year_val AS year,
    ba.month_val AS month_number,

    -- NEW: Fiscal Year Dimensions
    CASE
        WHEN ba.month_val >= 7 THEN ba.year_val     -- Jul-Dec: same year
        ELSE ba.year_val - 1                        -- Jan-Jun: previous year
    END AS fiscal_year,

    CASE
        WHEN ba.month_val >= 7 THEN ba.month_val - 6  -- Jul=1, Aug=2, ..., Dec=6
        ELSE ba.month_val + 6                         -- Jan=7, Feb=8, ..., Jun=12
    END AS fiscal_month_number,

    CONCAT(
        'FY',
        CASE WHEN ba.month_val >= 7 THEN ba.year_val ELSE ba.year_val - 1 END,
        '-',
        LPAD(
            CASE WHEN ba.month_val >= 7 THEN ba.month_val - 6 ELSE ba.month_val + 6 END,
            2, '0'
        )
    ) AS fiscal_month_key,

    -- Budget Scenario
    'ORIGINAL' AS budget_scenario,

    -- Metrics
    ba.budget_revenue_dkk,
    ba.budget_hours,
    ba.contract_count,
    ba.consultant_count
FROM budget_aggregated ba
ORDER BY ba.year_val DESC, ba.month_val DESC, ba.companyuuid, ba.service_line_id;
