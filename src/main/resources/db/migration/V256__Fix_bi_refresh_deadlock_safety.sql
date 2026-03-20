-- V256: Fix BI refresh deadlock safety
--
-- Root cause: sp_recalculate_budgets does DELETE then INSERT as separate
-- autocommit transactions. When the INSERT deadlocks, the DELETE is already
-- committed — all budget rows are lost until the next successful nightly.
--
-- Fixes:
-- 1. Wrap DELETE + INSERT in sp_recalculate_budgets in explicit transaction
-- 2. Add GET_LOCK to sp_nightly_bi_refresh (coordinate with incremental)
-- 3. Unify lock name in sp_incremental_bi_refresh
-- 4. Expand contract triggers to cover full date range (not just activefrom)

-- ============================================================================
-- 1. sp_recalculate_budgets: wrap DELETE + INSERT in explicit transaction
-- ============================================================================
DROP PROCEDURE IF EXISTS sp_recalculate_budgets;

DELIMITER $$

CREATE PROCEDURE sp_recalculate_budgets(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    -- Wrap DELETE + INSERT in a single transaction so a deadlock on INSERT
    -- rolls back the DELETE too — no more orphaned deletes.
    START TRANSACTION;

    DELETE FROM fact_budget_day
    WHERE document_date >= p_start_date
      AND document_date < p_end_date
      AND (p_user_uuid IS NULL OR useruuid = p_user_uuid);

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

    COMMIT;

    -- Post-insert adjustments (outside the critical transaction)
    -- These UPDATE existing rows and are idempotent, so safe to run separately.

    -- Normalize over-allocated budgets
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

    -- Zero out budget when no availability
    UPDATE fact_budget_day fbd
    JOIN fact_user_day fud
        ON fud.useruuid = fbd.useruuid AND fud.document_date = fbd.document_date
    SET fbd.budgetHours = 0
    WHERE fud.net_available_hours = 0
      AND fbd.document_date >= p_start_date
      AND fbd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR fbd.useruuid = p_user_uuid);

    -- Set company from availability data
    UPDATE fact_budget_day fbd
    JOIN fact_user_day fud ON fud.useruuid = fbd.useruuid AND fud.document_date = fbd.document_date
    SET fbd.companyuuid = fud.companyuuid
    WHERE fbd.document_date >= p_start_date AND fbd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR fbd.useruuid = p_user_uuid);
END$$

DELIMITER ;


-- ============================================================================
-- 2. sp_nightly_bi_refresh: add GET_LOCK for coordination with incremental
-- ============================================================================
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

    -- Step 4: Monthly aggregation (refreshes company_work_per_month etc.)
    CALL sp_aggregate_monthly(v_start, v_end);

    -- Step 5: Refresh materialized fact tables (truncate + repopulate from views)
    CALL sp_refresh_fact_tables();

    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;


-- ============================================================================
-- 3. sp_incremental_bi_refresh: use same lock name 'bi_refresh'
-- ============================================================================
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

    BEGIN
        DECLARE v_global_start DATE;
        DECLARE v_global_end DATE;

        SELECT MIN(month_start), MAX(month_end)
        INTO v_global_start, v_global_end
        FROM tmp_affected_ranges;

        IF v_global_start IS NOT NULL THEN
            CALL sp_aggregate_monthly(v_global_start, v_global_end);
        END IF;
    END;

    CALL sp_refresh_fact_tables();

    UPDATE fact_change_log
    SET processed_at = NOW()
    WHERE processed_at IS NULL;

    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;


-- ============================================================================
-- 4. Update contract triggers to cover full date range
--    Instead of a single entry for activefrom, insert one entry per month
-- ============================================================================

DROP TRIGGER IF EXISTS trg_contract_consultants_after_insert;
DROP TRIGGER IF EXISTS trg_contract_consultants_after_update;
DROP TRIGGER IF EXISTS trg_contract_consultants_after_delete;

DELIMITER $$

CREATE TRIGGER trg_contract_consultants_after_insert
AFTER INSERT ON contract_consultants
FOR EACH ROW
BEGIN
    DECLARE v_month DATE;
    DECLARE v_end DATE;

    SET v_month = DATE_FORMAT(NEW.activefrom, '%Y-%m-01');
    SET v_end = COALESCE(NEW.activeto, DATE_ADD(NEW.activefrom, INTERVAL 24 MONTH));

    WHILE v_month < v_end DO
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (NEW.useruuid, v_month, 'CONTRACT', 'contract_consultants', NEW.uuid);
        SET v_month = DATE_ADD(v_month, INTERVAL 1 MONTH);
    END WHILE;
END$$

CREATE TRIGGER trg_contract_consultants_after_update
AFTER UPDATE ON contract_consultants
FOR EACH ROW
BEGIN
    DECLARE v_month DATE;
    DECLARE v_end DATE;

    -- Cover the NEW date range
    SET v_month = DATE_FORMAT(NEW.activefrom, '%Y-%m-01');
    SET v_end = COALESCE(NEW.activeto, DATE_ADD(NEW.activefrom, INTERVAL 24 MONTH));

    WHILE v_month < v_end DO
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (NEW.useruuid, v_month, 'CONTRACT', 'contract_consultants', NEW.uuid);
        SET v_month = DATE_ADD(v_month, INTERVAL 1 MONTH);
    END WHILE;

    -- If activefrom changed, also cover the OLD date range
    IF OLD.activefrom != NEW.activefrom OR OLD.activeto != NEW.activeto THEN
        SET v_month = DATE_FORMAT(OLD.activefrom, '%Y-%m-01');
        SET v_end = COALESCE(OLD.activeto, DATE_ADD(OLD.activefrom, INTERVAL 24 MONTH));

        WHILE v_month < v_end DO
            INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
            VALUES (OLD.useruuid, v_month, 'CONTRACT', 'contract_consultants', OLD.uuid);
            SET v_month = DATE_ADD(v_month, INTERVAL 1 MONTH);
        END WHILE;
    END IF;
END$$

CREATE TRIGGER trg_contract_consultants_after_delete
AFTER DELETE ON contract_consultants
FOR EACH ROW
BEGIN
    DECLARE v_month DATE;
    DECLARE v_end DATE;

    SET v_month = DATE_FORMAT(OLD.activefrom, '%Y-%m-01');
    SET v_end = COALESCE(OLD.activeto, DATE_ADD(OLD.activefrom, INTERVAL 24 MONTH));

    WHILE v_month < v_end DO
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, v_month, 'CONTRACT', 'contract_consultants', OLD.uuid);
        SET v_month = DATE_ADD(v_month, INTERVAL 1 MONTH);
    END WHILE;
END$$

DELIMITER ;


-- ============================================================================
-- 5. Disable legacy ev_refresh_facts_daily event
--    This event populates _mv tables (fact_project_financials_mv,
--    fact_employee_monthly_mv) which are no longer referenced by any code.
--    The _mat tables (populated by sp_refresh_fact_tables) have replaced them.
-- ============================================================================

ALTER EVENT ev_refresh_facts_daily DISABLE;
