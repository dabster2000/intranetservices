-- =============================================================================
-- Migration V158: Create sp_recalculate_budgets
--
-- Purpose:
--   Set-based budget calculation that replaces the Java
--   BudgetCalculatingExecutor row-by-row logic.
--   Populates bi_budget_per_day with budget allocations per user per
--   contract per day, with discount modifiers and availability normalization.
--
-- Parameters:
--   p_start_date  - Start of recalculation window (inclusive)
--   p_end_date    - End of recalculation window (exclusive)
--   p_user_uuid   - Specific user UUID, or NULL for all users
--
-- Business rules replicated:
--   - Daily budget = contract_consultant.hours / 5.0
--   - Skip weekends (no budget rows)
--   - Apply discount modifiers from contract_type_items
--   - Effective rate = base_rate * (1 - SUM(discounts/100))
--   - Availability normalization: if total budget > net_available,
--     proportionally reduce each contract's budget
--   - Uses delete-then-insert pattern for idempotency
-- =============================================================================

DELIMITER //

CREATE PROCEDURE sp_recalculate_budgets(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    -- Step 1: Delete existing budget data for the date range
    IF p_user_uuid IS NULL THEN
        DELETE FROM bi_budget_per_day
        WHERE document_date >= p_start_date
          AND document_date < p_end_date;
    ELSE
        DELETE FROM bi_budget_per_day
        WHERE document_date >= p_start_date
          AND document_date < p_end_date
          AND useruuid = p_user_uuid;
    END IF;

    -- Step 2: Insert raw budget data (before availability normalization).
    -- For each user-contract-day combination, calculate daily budget hours
    -- and effective rate (with discount modifiers applied).
    INSERT INTO bi_budget_per_day (
        document_date, year, month, day,
        useruuid, clientuuid, companyuuid, contractuuid,
        budgetHours, budgetHoursWithNoAvailabilityAdjustment, rate
    )
    SELECT
        dd.date_key,
        dd.year,
        dd.month,
        dd.day,
        cc.useruuid,
        c.clientuuid,
        -- Company comes from bi_data_per_day (already populated by availability SP)
        COALESCE(bdd.companyuuid, ''),
        c.uuid AS contractuuid,
        -- Daily budget = weekly hours / 5
        cc.hours / 5.0 AS budgetHours,
        cc.hours / 5.0 AS budgetHoursWithNoAvailabilityAdjustment,
        -- Effective rate = base rate * discount modifier
        cc.rate * COALESCE(dm.discount_modifier, 1.0) AS rate
    FROM dim_date dd
    JOIN contracts c
        ON dd.date_key >= c.activefrom
        AND dd.date_key < c.activeto
    JOIN contract_consultants cc
        ON cc.contractuuid = c.uuid
        AND cc.hours > 0
        AND (cc.activefrom IS NULL OR dd.date_key >= cc.activefrom)
        AND (cc.activeto IS NULL OR dd.date_key < cc.activeto)
    LEFT JOIN bi_data_per_day bdd
        ON bdd.useruuid = cc.useruuid
        AND bdd.document_date = dd.date_key
    LEFT JOIN (
        -- Aggregate discount modifiers per contract
        SELECT
            cti.contractuuid,
            GREATEST(0.0, 1.0 - COALESCE(SUM(
                CASE
                    WHEN cti.value IS NOT NULL AND cti.value != ''
                    THEN CAST(cti.value AS DECIMAL(10,4)) / 100.0
                    ELSE 0.0
                END
            ), 0.0)) AS discount_modifier
        FROM contract_type_items cti
        GROUP BY cti.contractuuid
    ) dm ON dm.contractuuid = c.uuid
    WHERE dd.date_key >= p_start_date
      AND dd.date_key < p_end_date
      AND dd.is_weekend = 0
      AND (p_user_uuid IS NULL OR cc.useruuid = p_user_uuid);

    -- Step 3: Availability normalization.
    -- If a user's total daily budget exceeds their net available hours,
    -- proportionally reduce each contract's budget to fit.
    -- Uses a temporary table to hold the normalization factors.
    DROP TEMPORARY TABLE IF EXISTS tmp_budget_normalization;

    CREATE TEMPORARY TABLE tmp_budget_normalization (
        useruuid     VARCHAR(36) NOT NULL,
        document_date DATE       NOT NULL,
        total_budget  DOUBLE     NOT NULL,
        net_available DOUBLE     NOT NULL,
        factor        DOUBLE     NOT NULL,
        PRIMARY KEY (useruuid, document_date)
    ) ENGINE=MEMORY;

    INSERT INTO tmp_budget_normalization (useruuid, document_date, total_budget, net_available, factor)
    SELECT
        bbpd.useruuid,
        bbpd.document_date,
        SUM(bbpd.budgetHours) AS total_budget,
        COALESCE(GREATEST(
            bdd.gross_available_hours
            - COALESCE(bdd.unavailable_hours, 0)
            - COALESCE(bdd.vacation_hours, 0)
            - COALESCE(bdd.sick_hours, 0)
            - COALESCE(bdd.maternity_leave_hours, 0)
            - COALESCE(bdd.non_payd_leave_hours, 0)
            - COALESCE(bdd.paid_leave_hours, 0)
        , 0), 0) AS net_available,
        -- Factor: if total > available, scale down; otherwise 1.0
        CASE
            WHEN SUM(bbpd.budgetHours) > COALESCE(GREATEST(
                bdd.gross_available_hours
                - COALESCE(bdd.unavailable_hours, 0)
                - COALESCE(bdd.vacation_hours, 0)
                - COALESCE(bdd.sick_hours, 0)
                - COALESCE(bdd.maternity_leave_hours, 0)
                - COALESCE(bdd.non_payd_leave_hours, 0)
                - COALESCE(bdd.paid_leave_hours, 0)
            , 0), 0)
            AND SUM(bbpd.budgetHours) > 0
            THEN COALESCE(GREATEST(
                bdd.gross_available_hours
                - COALESCE(bdd.unavailable_hours, 0)
                - COALESCE(bdd.vacation_hours, 0)
                - COALESCE(bdd.sick_hours, 0)
                - COALESCE(bdd.maternity_leave_hours, 0)
                - COALESCE(bdd.non_payd_leave_hours, 0)
                - COALESCE(bdd.paid_leave_hours, 0)
            , 0), 0) / SUM(bbpd.budgetHours)
            ELSE 1.0
        END AS factor
    FROM bi_budget_per_day bbpd
    JOIN bi_data_per_day bdd
        ON bdd.useruuid = bbpd.useruuid
        AND bdd.document_date = bbpd.document_date
    WHERE bbpd.document_date >= p_start_date
      AND bbpd.document_date < p_end_date
      AND bdd.consultant_type IN ('CONSULTANT', 'STUDENT')
      AND (p_user_uuid IS NULL OR bbpd.useruuid = p_user_uuid)
    GROUP BY bbpd.useruuid, bbpd.document_date,
             bdd.gross_available_hours, bdd.unavailable_hours,
             bdd.vacation_hours, bdd.sick_hours,
             bdd.maternity_leave_hours, bdd.non_payd_leave_hours,
             bdd.paid_leave_hours
    HAVING SUM(bbpd.budgetHours) > COALESCE(GREATEST(
                bdd.gross_available_hours
                - COALESCE(bdd.unavailable_hours, 0)
                - COALESCE(bdd.vacation_hours, 0)
                - COALESCE(bdd.sick_hours, 0)
                - COALESCE(bdd.maternity_leave_hours, 0)
                - COALESCE(bdd.non_payd_leave_hours, 0)
                - COALESCE(bdd.paid_leave_hours, 0)
            , 0), 0);

    -- Apply normalization: reduce budget hours proportionally
    UPDATE bi_budget_per_day bbpd
    JOIN tmp_budget_normalization tn
        ON tn.useruuid = bbpd.useruuid
        AND tn.document_date = bbpd.document_date
    SET bbpd.budgetHours = bbpd.budgetHours * tn.factor
    WHERE bbpd.document_date >= p_start_date
      AND bbpd.document_date < p_end_date;

    -- Step 4: Update the company UUID on budget rows from bi_data_per_day
    UPDATE bi_budget_per_day bbpd
    JOIN bi_data_per_day bdd
        ON bdd.useruuid = bbpd.useruuid
        AND bdd.document_date = bbpd.document_date
    SET bbpd.companyuuid = bdd.companyuuid
    WHERE bbpd.document_date >= p_start_date
      AND bbpd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR bbpd.useruuid = p_user_uuid);

    DROP TEMPORARY TABLE IF EXISTS tmp_budget_normalization;
END //

DELIMITER ;
