-- ============================================================================
-- V334: Fix sp_recalculate_budgets discount unit (percent 0-100)
-- ============================================================================
-- Purpose
--   The contract_type_items.value column stores DISCOUNT as a percent in the
--   range 0-100 (e.g. value='10' = 10% off). This is the convention used by
--   the live `work_full` view, which casts the value as an unsigned integer
--   and divides by 100 before applying it to the rate.
--
--   `sp_recalculate_budgets` was written with a different (incorrect)
--   assumption: that the value is already a fraction in the range 0-1
--   (e.g. value='0.10' = 10%). It applies the value directly without the
--   /100 divisor:
--       cc.rate * (1 - COALESCE(CAST(NULLIF(cti.value, '') AS DECIMAL(10,4)), 0))
--   For a stored value of '10' this would compute rate * (1 - 10) = -9*rate,
--   producing a negative budget rate. For a stored value of '0.10' it would
--   compute rate * 0.90, which "looks correct" only by accident of how the
--   data is stored today.
--
-- Background
--   - V279 (2026) recreated `sp_recalculate_budgets` to fix a silent failure
--     bug in the post-transaction UPDATE pipeline. The body has not been
--     modified since.
--   - The current `work_full` view applies the discount as:
--         cast(nullif(cti.value, '') as unsigned) / 100
--     so the budget procedure must use the same divisor to stay consistent.
--
-- Production impact today
--   ZERO. As of 2026-05-10 there are 0 rows in contract_type_items with
--   `name = 'DISCOUNT' AND value <> ''`. This is a preventive fix: the
--   first contract that adds a DISCOUNT after this migration deploys will
--   compute the correct budget rate; without this fix it would silently
--   produce wrong budget revenue numbers.
--
-- Scope of body changes
--   ONE-LINE change in the INSERT IGNORE INTO fact_budget_day SELECT:
--     BEFORE:
--       cc.rate * (1 - COALESCE(CAST(NULLIF(cti.value, '') AS DECIMAL(10,4)), 0)) AS rate
--     AFTER:
--       cc.rate * (1 - COALESCE(CAST(NULLIF(cti.value, '') AS DECIMAL(10,4)), 0) / 100) AS rate
--   All other lines (signature, error handler, transaction wrapping,
--   normalization UPDATE, zero-out UPDATE) are byte-identical to V279.
--
-- Idempotency
--   DROP PROCEDURE IF EXISTS guards re-runs in dev. In production it runs
--   once.
--
-- Rollback
--   Re-apply V279 verbatim. The procedure body is fully self-contained in
--   that file. Both V279 and this V334 use the same DROP+CREATE pattern,
--   so the rollback is a single-shot replay.
--
-- Verification (run after deploy)
--   -- 1. Force a refresh so any future-discount contracts pick up the new
--   --    formula.
--   CALL sp_recalculate_budgets(
--       DATE_SUB(CURDATE(), INTERVAL 3 MONTH),
--       DATE_ADD(CURDATE(), INTERVAL 24 MONTH),
--       NULL);
--
--   -- 2. Sanity: budget rows are unchanged for current month (since 0
--   --    contracts have a non-empty DISCOUNT today).
--   SELECT YEAR(document_date) AS y, MONTH(document_date) AS m,
--          COUNT(*) AS c,
--          ROUND(SUM(budgetHours * rate), 0) AS revenue
--   FROM fact_budget_day
--   WHERE document_date BETWEEN '2026-05-01' AND '2026-05-31'
--   GROUP BY y, m;
--   -- compare against the same rollup before the migration; values must match.
--
--   -- 3. Confirm the new procedure body contains the /100 divisor.
--   SELECT routine_definition
--   FROM information_schema.routines
--   WHERE routine_schema = DATABASE()
--     AND routine_name = 'sp_recalculate_budgets'
--     AND routine_definition LIKE '%/ 100)%';
--   -- expected: 1 row
-- ============================================================================

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
    --
    -- V334: discount value is a percent in 0-100 (matches `work_full`).
    -- The /100 divisor below was missing in V279 and earlier.
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
        cc.rate * (1 - COALESCE(CAST(NULLIF(cti.value, '') AS DECIMAL(10,4)), 0) / 100) AS rate
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
    -- Vacation, sick days, weekends, maternity leave etc. -> net available = 0
    -- -> budget must also be 0 even though the contract allocates hours.
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
