-- =============================================================================
-- Migration V163: Create bi_change_log table, triggers, and sp_incremental_bi_refresh
--
-- Purpose:
--   Phase 4 of the utilization optimization plan. Introduces change tracking
--   for near-real-time BI data updates (5-minute refresh) instead of relying
--   solely on the nightly batch.
--
-- Components:
--   1. bi_change_log table — tracks source data changes
--   2. Triggers on 5 source tables (work, vacation, userstatus,
--      contract_consultants, salary)
--   3. sp_incremental_bi_refresh() — processes change log entries
--
-- How it works:
--   - Triggers fire on INSERT/UPDATE/DELETE of source tables
--   - Each trigger inserts a row into bi_change_log with (useruuid, affected_date)
--   - sp_incremental_bi_refresh() picks up unprocessed entries, expands to
--     full month ranges, and calls the existing stored procedures for targeted
--     recalculation
--   - A MariaDB event (V164) runs sp_incremental_bi_refresh() every 5 minutes
--
-- Prerequisites:
--   - sp_recalculate_availability (V156)
--   - sp_aggregate_work (V157)
--   - sp_recalculate_budgets (V158)
--   - sp_aggregate_monthly (V159)
--   - sp_refresh_fact_tables (V161)
-- =============================================================================


-- ---------------------------------------------------------------------------
-- 1. bi_change_log table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bi_change_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    useruuid      VARCHAR(36)  NOT NULL,
    affected_date DATE         NOT NULL,
    change_type   ENUM('WORK', 'VACATION', 'CONTRACT', 'STATUS', 'SALARY') NOT NULL,
    source_table  VARCHAR(50),
    source_id     VARCHAR(36),
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    processed_at  TIMESTAMP    NULL,
    INDEX idx_unprocessed (processed_at, affected_date, useruuid),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------------
-- 2. Triggers on source tables
-- ---------------------------------------------------------------------------

-- ----- work table -----

DELIMITER //

CREATE TRIGGER trg_work_after_insert AFTER INSERT ON work
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.registered, 'WORK', 'work', NEW.uuid);
END //

CREATE TRIGGER trg_work_after_update AFTER UPDATE ON work
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.registered, 'WORK', 'work', NEW.uuid);
    -- If the date changed, also log the old date
    IF OLD.registered != NEW.registered THEN
        INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.registered, 'WORK', 'work', OLD.uuid);
    END IF;
    -- If the user changed, also log the old user
    IF OLD.useruuid != NEW.useruuid THEN
        INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.registered, 'WORK', 'work', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_work_after_delete AFTER DELETE ON work
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.registered, 'WORK', 'work', OLD.uuid);
END //


-- ----- vacation table -----

CREATE TRIGGER trg_vacation_after_insert AFTER INSERT ON vacation
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.date, 'VACATION', 'vacation', NEW.uuid);
END //

CREATE TRIGGER trg_vacation_after_update AFTER UPDATE ON vacation
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.date, 'VACATION', 'vacation', NEW.uuid);
    IF OLD.date != NEW.date THEN
        INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.date, 'VACATION', 'vacation', OLD.uuid);
    END IF;
    IF OLD.useruuid != NEW.useruuid THEN
        INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.date, 'VACATION', 'vacation', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_vacation_after_delete AFTER DELETE ON vacation
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.date, 'VACATION', 'vacation', OLD.uuid);
END //


-- ----- userstatus table -----

CREATE TRIGGER trg_userstatus_after_insert AFTER INSERT ON userstatus
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.statusdate, 'STATUS', 'userstatus', NEW.uuid);
END //

CREATE TRIGGER trg_userstatus_after_update AFTER UPDATE ON userstatus
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.statusdate, 'STATUS', 'userstatus', NEW.uuid);
    IF OLD.statusdate != NEW.statusdate THEN
        INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.statusdate, 'STATUS', 'userstatus', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_userstatus_after_delete AFTER DELETE ON userstatus
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.statusdate, 'STATUS', 'userstatus', OLD.uuid);
END //


-- ----- contract_consultants table -----

CREATE TRIGGER trg_contract_consultants_after_insert AFTER INSERT ON contract_consultants
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'CONTRACT', 'contract_consultants', NEW.uuid);
END //

CREATE TRIGGER trg_contract_consultants_after_update AFTER UPDATE ON contract_consultants
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'CONTRACT', 'contract_consultants', NEW.uuid);
    IF OLD.activefrom != NEW.activefrom THEN
        INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.activefrom, 'CONTRACT', 'contract_consultants', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_contract_consultants_after_delete AFTER DELETE ON contract_consultants
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.activefrom, 'CONTRACT', 'contract_consultants', OLD.uuid);
END //


-- ----- salary table -----

CREATE TRIGGER trg_salary_after_insert AFTER INSERT ON salary
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'SALARY', 'salary', NEW.uuid);
END //

CREATE TRIGGER trg_salary_after_update AFTER UPDATE ON salary
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'SALARY', 'salary', NEW.uuid);
    IF OLD.activefrom != NEW.activefrom THEN
        INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.activefrom, 'SALARY', 'salary', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_salary_after_delete AFTER DELETE ON salary
FOR EACH ROW
BEGIN
    INSERT INTO bi_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.activefrom, 'SALARY', 'salary', OLD.uuid);
END //

DELIMITER ;


-- ---------------------------------------------------------------------------
-- 3. sp_incremental_bi_refresh()
-- ---------------------------------------------------------------------------
DELIMITER //

CREATE PROCEDURE sp_incremental_bi_refresh()
proc_body: BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_lock_acquired INT DEFAULT 0;

    -- Acquire advisory lock to prevent concurrent executions
    -- (e.g., overlapping 5-min events or concurrent nightly/weekly runs)
    SELECT GET_LOCK('bi_incremental_refresh', 0) INTO v_lock_acquired;
    IF v_lock_acquired = 0 THEN
        -- Another instance is running, skip this execution
        LEAVE proc_body;
    END IF;

    -- Check if there are unprocessed changes
    SELECT COUNT(*) INTO v_count
    FROM bi_change_log
    WHERE processed_at IS NULL;

    IF v_count = 0 THEN
        DO RELEASE_LOCK('bi_incremental_refresh');
        LEAVE proc_body;
    END IF;

    -- Collect distinct affected user-months from unprocessed changes.
    -- We expand each affected_date to its full month range because the
    -- stored procedures work at month granularity.
    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    CREATE TEMPORARY TABLE tmp_affected_ranges (
        useruuid   VARCHAR(36) NOT NULL,
        month_start DATE       NOT NULL,
        month_end   DATE       NOT NULL,
        PRIMARY KEY (useruuid, month_start)
    ) ENGINE=MEMORY;

    INSERT IGNORE INTO tmp_affected_ranges (useruuid, month_start, month_end)
    SELECT DISTINCT
        useruuid,
        DATE_FORMAT(affected_date, '%Y-%m-01') AS month_start,
        DATE_FORMAT(affected_date + INTERVAL 1 MONTH, '%Y-%m-01') AS month_end
    FROM bi_change_log
    WHERE processed_at IS NULL;

    -- Process each affected user-month by calling the existing SPs
    -- with user-specific parameters for targeted recalculation.
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

            -- Step 1: Recalculate availability for this user-month
            CALL sp_recalculate_availability(v_month_start, v_month_end, v_useruuid);

            -- Step 2: Aggregate work for the full month (not user-specific)
            -- sp_aggregate_work operates on date range, not per-user
            CALL sp_aggregate_work(v_month_start, v_month_end);

            -- Step 3: Recalculate budgets for this user-month
            CALL sp_recalculate_budgets(v_month_start, v_month_end, v_useruuid);
        END LOOP;

        CLOSE cur;
    END;

    -- Step 4: Monthly aggregation for all affected months
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

    -- Step 5: Refresh materialized fact tables
    CALL sp_refresh_fact_tables();

    -- Mark all processed entries
    UPDATE bi_change_log
    SET processed_at = NOW()
    WHERE processed_at IS NULL;

    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    -- Release advisory lock
    DO RELEASE_LOCK('bi_incremental_refresh');
END //

DELIMITER ;
