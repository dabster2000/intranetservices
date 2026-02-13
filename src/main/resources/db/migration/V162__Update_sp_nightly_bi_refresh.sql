-- =============================================================================
-- Migration V161b: Update sp_nightly_bi_refresh to include fact table refresh
--
-- Purpose:
--   Adds Step 5 (sp_refresh_fact_tables) to the master orchestration procedure.
--   This ensures materialized fact tables are refreshed after all daily data
--   has been recalculated.
--
-- Execution order (updated):
--   1. sp_recalculate_availability
--   2. sp_aggregate_work
--   3. sp_recalculate_budgets
--   4. sp_aggregate_monthly
--   5. sp_refresh_fact_tables  <<< NEW
--
-- Prerequisites:
--   - sp_refresh_fact_tables must exist (V161)
-- =============================================================================

DROP PROCEDURE IF EXISTS sp_nightly_bi_refresh;

DELIMITER //

CREATE PROCEDURE sp_nightly_bi_refresh(
    IN p_lookback_months INT,
    IN p_forward_months  INT
)
BEGIN
    DECLARE v_start DATE;
    DECLARE v_end   DATE;

    SET v_start = DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL p_lookback_months MONTH), '%Y-%m-01');
    SET v_end   = DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL p_forward_months MONTH), '%Y-%m-01');

    -- Step 1: Availability (creates/updates rows in bi_data_per_day)
    CALL sp_recalculate_availability(v_start, v_end, NULL);

    -- Step 2: Work aggregation (updates registered_billable_hours, registered_amount)
    CALL sp_aggregate_work(v_start, v_end);

    -- Step 3: Budget calculation (populates bi_budget_per_day)
    CALL sp_recalculate_budgets(v_start, v_end, NULL);

    -- Step 4: Monthly aggregation (refreshes company_work_per_month etc.)
    CALL sp_aggregate_monthly(v_start, v_end);

    -- Step 5: Refresh materialized fact tables (truncate + repopulate from views)
    CALL sp_refresh_fact_tables();
END //

DELIMITER ;
