-- =============================================================================
-- V184: Fix sp_recalculate_budgets — zero out budget on zero-availability days
--
-- Bug: The normalization step (Step 3) only scales down budget when
-- net_available > 0. On days where net_available = 0 (vacation, sick leave,
-- company shutdown), budget hours are left at their raw value. This inflates
-- company-level budget utilization because:
--   numerator = SUM(budgetHours) includes budget on absence days
--   denominator = SUM(net_available_hours) gets 0 from those same days
--
-- Impact: July 2025 showed 107.5% budget utilization instead of ~66%.
-- Every month is affected proportional to vacation/leave volume.
--
-- Fix: Add Step 3b to zero out budgetHours on days with no availability.
-- Per business rules: "Budget rows are still generated but will be
-- normalized down to 0 if net_available = 0."
-- =============================================================================

DROP PROCEDURE IF EXISTS sp_recalculate_budgets;

DELIMITER //
CREATE PROCEDURE sp_recalculate_budgets(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    -- Step 1: Delete existing budget data for the date range
    DELETE FROM fact_budget_day
    WHERE document_date >= p_start_date
      AND document_date < p_end_date
      AND (p_user_uuid IS NULL OR useruuid = p_user_uuid);

    -- Step 2: Insert raw budget data (before availability normalization).
    -- Contract date range is derived from MIN/MAX of contract_consultants dates
    -- instead of the deprecated contracts.activefrom/activeto columns (dropped in V183).
    -- Status filter: SIGNED (active), TIME (time-based), CLOSED (completed but within date range).
    -- Excludes BUDGET status (6 legacy contracts from 2018-2023, not real allocations).
    INSERT IGNORE INTO fact_budget_day (
        document_date, year, month, day,
        clientuuid, useruuid, companyuuid, contractuuid,
        budgetHours, budgetHoursWithNoAvailabilityAdjustment, rate
    )
    SELECT
        dd.date_key, dd.year, dd.month, dd.day,
        c.clientuuid, cc.useruuid, NULL, c.uuid AS contractuuid,
        cc.hours / 5.0 AS budgetHours,
        cc.hours / 5.0 AS budgetHoursRaw,
        cc.rate * (1 - COALESCE(CAST(NULLIF(cti.value, '') AS DECIMAL(10,4)), 0)) AS rate
    FROM contracts c
    JOIN contract_consultants cc ON cc.contractuuid = c.uuid
    JOIN (
        SELECT contractuuid,
               MIN(activefrom) AS min_activefrom,
               MAX(activeto)   AS max_activeto
        FROM contract_consultants
        GROUP BY contractuuid
    ) cp ON cp.contractuuid = c.uuid
    JOIN dim_date dd ON dd.date_key >= cc.activefrom
        AND dd.date_key < COALESCE(cc.activeto, '2035-01-01')
        AND dd.date_key >= COALESCE(cp.min_activefrom, '2014-01-01')
        AND dd.date_key < COALESCE(cp.max_activeto, '2035-01-01')
        AND dd.is_weekend = 0
    LEFT JOIN contract_type_items cti
        ON cti.contractuuid = c.uuid
        AND cti.name = 'DISCOUNT'
    WHERE dd.date_key >= p_start_date
      AND dd.date_key < p_end_date
      AND cc.hours > 0
      AND c.status IN ('SIGNED', 'TIME', 'CLOSED')
      AND (p_user_uuid IS NULL OR cc.useruuid = p_user_uuid);

    -- Step 3: Availability normalization.
    -- If a user's total daily budget exceeds their net available hours,
    -- proportionally reduce each contract's budget to fit.
    UPDATE fact_budget_day fbd
    JOIN (
        SELECT fbd2.useruuid, fbd2.document_date,
            SUM(fbd2.budgetHours) AS total_budget,
            COALESCE(fud.net_available_hours, 0) AS net_available
        FROM fact_budget_day fbd2
        LEFT JOIN fact_user_day fud
            ON fud.useruuid = fbd2.useruuid AND fud.document_date = fbd2.document_date
        WHERE fbd2.document_date >= p_start_date AND fbd2.document_date < p_end_date
          AND (p_user_uuid IS NULL OR fbd2.useruuid = p_user_uuid)
        GROUP BY fbd2.useruuid, fbd2.document_date
        HAVING total_budget > net_available AND net_available > 0
    ) overalloc ON fbd.useruuid = overalloc.useruuid AND fbd.document_date = overalloc.document_date
    SET fbd.budgetHours = fbd.budgetHours * (overalloc.net_available / overalloc.total_budget)
    WHERE fbd.document_date >= p_start_date AND fbd.document_date < p_end_date;

    -- Step 3b: Zero out budget on days with no availability.
    -- When a consultant is on vacation, sick leave, or company shutdown,
    -- net_available_hours = 0 and no billable work can be performed.
    -- Budget must be zeroed to prevent inflating budget utilization ratios.
    UPDATE fact_budget_day fbd
    JOIN fact_user_day fud
        ON fud.useruuid = fbd.useruuid AND fud.document_date = fbd.document_date
    SET fbd.budgetHours = 0
    WHERE fud.net_available_hours = 0
      AND fbd.document_date >= p_start_date
      AND fbd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR fbd.useruuid = p_user_uuid);

    -- Step 4: Update the company UUID on budget rows from fact_user_day
    UPDATE fact_budget_day fbd
    JOIN fact_user_day fud ON fud.useruuid = fbd.useruuid AND fud.document_date = fbd.document_date
    SET fbd.companyuuid = fud.companyuuid
    WHERE fbd.document_date >= p_start_date AND fbd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR fbd.useruuid = p_user_uuid);
END //
DELIMITER ;
