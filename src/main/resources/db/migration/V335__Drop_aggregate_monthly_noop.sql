-- ============================================================================
-- V335: Drop sp_aggregate_monthly (no-op) and remove its callers
-- ============================================================================
-- Purpose
--   `sp_aggregate_monthly` has been a no-op (`DO 0;`) ever since
--   `company_work_per_month` became a live VIEW. Both BI orchestrators
--   (`sp_nightly_bi_refresh` and `sp_incremental_bi_refresh`) still call
--   it, wasting an open/close round-trip and adding noise to the refresh
--   pipeline. This migration removes the dead orchestration step.
--
-- Scope of changes
--   1. Recreate `sp_nightly_bi_refresh` WITHOUT the `CALL sp_aggregate_monthly`
--      line. The "Step 4" block is removed and "Step 5" (refresh fact tables)
--      is renumbered to "Step 4" with a comment noting the removal.
--   2. Recreate `sp_incremental_bi_refresh` WITHOUT the inner BEGIN..END
--      block that called `sp_aggregate_monthly(v_global_start, v_global_end)`.
--      Everything else (lock acquisition, change-log scan, per-user cursor
--      loop, fact-tables refresh, change-log marking, temp-table cleanup,
--      lock release) is byte-identical to the captured production body.
--   3. `DROP PROCEDURE IF EXISTS sp_aggregate_monthly` as the FINAL step.
--
-- Order matters
--   The two orchestrators MUST stop calling `sp_aggregate_monthly` BEFORE
--   the procedure is dropped. Otherwise the next refresh tick (nightly at
--   ~02:00 UTC, incremental every 5 minutes) would error out trying to
--   call a non-existent procedure. This file enforces the order: orchestrators
--   first, then DROP.
--
-- Background
--   - `sp_aggregate_monthly` was originally responsible for refreshing
--     `company_work_per_month` as a materialized table. When that object
--     became a regular VIEW the procedure body was replaced with `DO 0;`
--     but the call sites were never removed.
--   - Production already runs both orchestrators every cycle, so the cost
--     of the no-op is real (one cross-procedure call per cycle for the
--     incremental case, one per nightly run).
--
-- Idempotency
--   - CREATE OR REPLACE on both orchestrators makes the call-site removal
--     idempotent.
--   - DROP PROCEDURE IF EXISTS guards the final step.
--
-- Rollback
--   1. Recreate `sp_aggregate_monthly` with `BEGIN DO 0; END`.
--   2. Restore the prior orchestrator bodies (captured production bodies
--      kept in the deployment runbook): re-add the `CALL sp_aggregate_monthly`
--      line in `sp_nightly_bi_refresh` Step 4, and re-add the inner
--      BEGIN..END in `sp_incremental_bi_refresh` after the cursor loop.
--   Both procedures are pure DDL on stored objects -- no data is touched.
--
-- Verification (run after deploy)
--   -- 1. Confirm sp_aggregate_monthly is gone.
--   SELECT routine_name FROM information_schema.routines
--   WHERE routine_schema = DATABASE()
--     AND routine_name = 'sp_aggregate_monthly';
--   -- expected: 0 rows
--
--   -- 2. Confirm orchestrators don't reference it.
--   SELECT routine_name FROM information_schema.routines
--   WHERE routine_schema = DATABASE()
--     AND routine_definition LIKE '%sp_aggregate_monthly%';
--   -- expected: 0 rows
--
--   -- 3. Confirm the next nightly run completes.
--   SELECT event_name, status, last_executed
--   FROM information_schema.events
--   WHERE event_schema = DATABASE()
--     AND event_name = 'ev_bi_nightly_refresh';
--   -- last_executed should be today/tomorrow.
--
--   -- 4. Manually call the nightly orchestrator (staging only) to make
--   --    sure the body parses and executes end-to-end.
--   --    CALL sp_nightly_bi_refresh(3, 24);
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. sp_nightly_bi_refresh -- remove CALL sp_aggregate_monthly
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_nightly_bi_refresh;

DELIMITER $$

CREATE PROCEDURE sp_nightly_bi_refresh(
    IN p_lookback_months INT,
    IN p_forward_months  INT
)
nightly_body: BEGIN
    DECLARE v_start DATE;
    DECLARE v_end   DATE;
    DECLARE v_lock_acquired INT DEFAULT 0;

    -- Acquire shared lock with incremental refresh (wait up to 5 min)
    SELECT GET_LOCK('bi_refresh', 300) INTO v_lock_acquired;
    IF v_lock_acquired = 0 THEN
        LEAVE nightly_body;
    END IF;

    SET v_start = DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL p_lookback_months MONTH), '%Y-%m-01');
    SET v_end   = DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL p_forward_months MONTH), '%Y-%m-01');

    -- Step 1: Availability (creates/updates rows in bi_data_per_day)
    CALL sp_recalculate_availability(v_start, v_end, NULL);

    -- Step 2: Work aggregation (updates registered_billable_hours, registered_amount)
    CALL sp_aggregate_work(v_start, v_end);

    -- Step 3: Budget calculation (populates bi_budget_per_day)
    CALL sp_recalculate_budgets(v_start, v_end, NULL);

    -- Step 4: Refresh materialized fact tables (truncate + repopulate from views)
    -- This includes block 14 which calls sp_refresh_sick_day_rolling()
    --
    -- V335: removed the previous "Step 4: Monthly aggregation" call to
    -- sp_aggregate_monthly. That procedure was a no-op (`DO 0;`) since
    -- company_work_per_month became a live VIEW; sp_aggregate_monthly
    -- itself is dropped at the end of this migration.
    CALL sp_refresh_fact_tables();

    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;


-- ----------------------------------------------------------------------------
-- 2. sp_incremental_bi_refresh -- remove inner CALL sp_aggregate_monthly block
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_incremental_bi_refresh;

DELIMITER $$

CREATE PROCEDURE sp_incremental_bi_refresh()
proc_body: BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_lock_acquired INT DEFAULT 0;

    -- Use same lock as nightly to prevent concurrent execution
    SELECT GET_LOCK('bi_refresh', 0) INTO v_lock_acquired;
    IF v_lock_acquired = 0 THEN
        LEAVE proc_body;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM fact_change_log
    WHERE processed_at IS NULL;

    IF v_count = 0 THEN
        DO RELEASE_LOCK('bi_refresh');
        LEAVE proc_body;
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    CREATE TEMPORARY TABLE tmp_affected_ranges (
        useruuid   VARCHAR(36) NOT NULL,
        month_start DATE       NOT NULL,
        month_end   DATE       NOT NULL,
        PRIMARY KEY (useruuid, month_start)
    ) ENGINE=MEMORY;

    -- For CONTRACT changes, expand to cover the full contract date range
    -- For other changes, use the single affected month
    INSERT IGNORE INTO tmp_affected_ranges (useruuid, month_start, month_end)
    SELECT DISTINCT
        fcl.useruuid,
        DATE_FORMAT(fcl.affected_date, '%Y-%m-01') AS month_start,
        DATE_FORMAT(fcl.affected_date + INTERVAL 1 MONTH, '%Y-%m-01') AS month_end
    FROM fact_change_log fcl
    WHERE fcl.processed_at IS NULL;

    BEGIN
        DECLARE done INT DEFAULT FALSE;
        DECLARE v_useruuid VARCHAR(36);
        DECLARE v_month_start DATE;
        DECLARE v_month_end DATE;

        DECLARE cur CURSOR FOR
            SELECT useruuid, month_start, month_end
            FROM tmp_affected_ranges;

        DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

        OPEN cur;

        read_loop: LOOP
            FETCH cur INTO v_useruuid, v_month_start, v_month_end;
            IF done THEN
                LEAVE read_loop;
            END IF;

            CALL sp_recalculate_availability(v_month_start, v_month_end, v_useruuid);
            CALL sp_aggregate_work(v_month_start, v_month_end);
            CALL sp_recalculate_budgets(v_month_start, v_month_end, v_useruuid);
        END LOOP;

        CLOSE cur;
    END;

    -- V335: removed the global sp_aggregate_monthly inner BEGIN..END block.
    -- That procedure was a no-op (`DO 0;`) since company_work_per_month
    -- became a live VIEW; sp_aggregate_monthly itself is dropped at the
    -- end of this migration.

    CALL sp_refresh_fact_tables();

    UPDATE fact_change_log
    SET processed_at = NOW()
    WHERE processed_at IS NULL;

    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;


-- ----------------------------------------------------------------------------
-- 3. Drop sp_aggregate_monthly
--
-- Both orchestrators above no longer reference it; the next refresh tick
-- will not attempt to call the dropped procedure.
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_aggregate_monthly;
