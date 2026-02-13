-- =============================================================================
-- Migration V160: Create sp_nightly_bi_refresh
--
-- Purpose:
--   Master orchestration procedure that calls all BI recalculation
--   stored procedures in the correct order. Replaces the Java
--   BatchScheduler.trigger() -> partitioned batch job pipeline.
--
-- Parameters:
--   p_lookback_months  - Months to look back from today (typically 3 nightly, 24 full rebuild)
--   p_forward_months   - Months to look forward (typically 24)
--
-- Execution order:
--   1. sp_recalculate_availability (populate bi_data_per_day availability columns)
--   2. sp_aggregate_work           (update bi_data_per_day work/revenue columns)
--   3. sp_recalculate_budgets      (populate bi_budget_per_day with normalization)
--   4. sp_aggregate_monthly        (refresh monthly aggregation tables)
--
-- Note: The order matters because:
--   - Work aggregation updates existing bi_data_per_day rows created by availability
--   - Budget calculation reads net_available from bi_data_per_day (set by availability)
--   - Monthly aggregation reads from completed daily data
-- =============================================================================

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
END //

DELIMITER ;
