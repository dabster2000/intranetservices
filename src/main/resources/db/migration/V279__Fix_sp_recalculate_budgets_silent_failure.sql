-- V279: Fix sp_recalculate_budgets silent failure on post-transaction UPDATEs
--
-- Root cause: V256 introduced three post-transaction UPDATE steps after the
-- DELETE+INSERT transaction commits:
--   1. Normalize over-allocated budgets
--   2. Zero out budget when no availability
--   3. Set companyuuid from fact_user_day
--
-- These UPDATEs run outside the transaction and have no error handling.
-- Under lock contention, query timeouts, or transient DB load they fail
-- silently, leaving fact_budget_day rows with:
--   - NULL companyuuid  (→ filtered out by fact_revenue_budget view
--                          → excluded from fact_revenue_budget_mat
--                          → executive dashboard budget line is wrong)
--   - Un-normalized budgetHours (over-allocated contracts not capped)
--   - Non-zero budgets on unavailable days (vacation/sick)
--
-- Observed impact: 2026 data saw the failure on 2026-04-09 nightly
-- (99% of rows had NULL companyuuid, ~4× less budget than actual) but
-- 2026-04-10 nightly succeeded. Intermittent → affects users at random.
--
-- Fix:
--   1. Set companyuuid directly in the INSERT via LEFT JOIN fact_user_day.
--      Eliminates the post-transaction UPDATE for the most impactful field.
--      sp_recalculate_availability runs before sp_recalculate_budgets in
--      both nightly and incremental paths, so fact_user_day.companyuuid is
--      guaranteed to be populated for the same date range.
--
--   2. Move normalize and zero-out UPDATEs INSIDE the transaction so they
--      either all succeed or all fail atomically.
--
--   3. Add DECLARE EXIT HANDLER FOR SQLEXCEPTION that rolls back and
--      re-raises the error (RESIGNAL). Failures now surface loudly in the
--      parent refresh procedures instead of being silently swallowed.

DROP PROCEDURE IF EXISTS sp_recalculate_budgets;

DELIMITER $$

CREATE PROCEDURE sp_recalculate_budgets(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    -- Any SQL error rolls back the entire procedure and re-raises the error.
    -- This ensures we never commit a partial state (NULL companyuuid,
    -- un-normalized budgets, etc.) that silently breaks downstream views.
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    -- Delete existing rows in the target range
    DELETE FROM fact_budget_day
    WHERE document_date >= p_start_date
      AND document_date < p_end_date
      AND (p_user_uuid IS NULL OR useruuid = p_user_uuid);

    -- Insert new rows with companyuuid resolved via fact_user_day.
    -- sp_recalculate_availability runs before this procedure in both the
    -- nightly and incremental paths, so fact_user_day is guaranteed to have
    -- rows for this date range with valid companyuuid.
    INSERT IGNORE INTO fact_budget_day (
        document_date, year, month, day,
        clientuuid, useruuid, companyuuid, contractuuid,
        budgetHours, budgetHoursWithNoAvailabilityAdjustment, rate
    )
    SELECT
        dd.date_key, dd.year, dd.month, dd.day,
        c.clientuuid, cc.useruuid, fud.companyuuid, c.uuid AS contractuuid,
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
    LEFT JOIN fact_user_day fud
        ON fud.useruuid = cc.useruuid
        AND fud.document_date = dd.date_key
    WHERE dd.date_key >= p_start_date
      AND dd.date_key < p_end_date
      AND cc.hours > 0
      AND c.status IN ('SIGNED', 'TIME', 'CLOSED')
      AND (p_user_uuid IS NULL OR cc.useruuid = p_user_uuid);

    -- Normalize over-allocated budgets (inside transaction).
    -- If a consultant has multiple contracts whose combined daily hours
    -- exceed their net available hours, proportionally reduce each contract
    -- so the total fits within availability.
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

    -- Zero out budget when no availability (inside transaction).
    -- Vacation, sick days, weekends, maternity leave etc. → net available = 0
    -- → budget must also be 0 even though the contract allocates hours.
    UPDATE fact_budget_day fbd
    JOIN fact_user_day fud
        ON fud.useruuid = fbd.useruuid AND fud.document_date = fbd.document_date
    SET fbd.budgetHours = 0
    WHERE fud.net_available_hours = 0
      AND fbd.document_date >= p_start_date
      AND fbd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR fbd.useruuid = p_user_uuid);

    COMMIT;
END$$

DELIMITER ;
