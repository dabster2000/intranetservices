-- ==========================================================================
-- V410: Certify fact_user_day completeness after successful BI refreshes
-- ==========================================================================
-- Row-level fact_user_day.last_update is not a completion watermark because
-- sp_recalculate_availability writes it before sp_aggregate_work and the fact
-- materializations finish. This table is advanced only after the complete
-- orchestrator succeeds. Existing data is intentionally not inferred or
-- bootstrapped; the first successful full refresh establishes certification.

CREATE TABLE IF NOT EXISTS bi_refresh_watermark (
    pipeline_name VARCHAR(64) NOT NULL,
    certified_complete_through_date DATE NULL,
    last_full_refresh_at DATETIME(6) NULL COMMENT 'UTC',
    last_incremental_refresh_at DATETIME(6) NULL COMMENT 'UTC',
    refresh_state ENUM('UNINITIALIZED', 'RUNNING', 'READY', 'FAILED')
        NOT NULL DEFAULT 'UNINITIALIZED',
    active_refresh_token CHAR(36) NULL,
    PRIMARY KEY (pipeline_name)
) ENGINE=InnoDB;

INSERT IGNORE INTO bi_refresh_watermark (
    pipeline_name,
    certified_complete_through_date,
    last_full_refresh_at,
    last_incremental_refresh_at,
    refresh_state,
    active_refresh_token
) VALUES ('FACT_USER_DAY', NULL, NULL, NULL, 'UNINITIALIZED', NULL);


-- --------------------------------------------------------------------------
-- Full refresh: certify Copenhagen yesterday only after every pipeline step.
-- --------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_nightly_bi_refresh;

DELIMITER $$

CREATE PROCEDURE sp_nightly_bi_refresh(
    IN p_lookback_months INT,
    IN p_forward_months  INT
)
nightly_body: BEGIN
    DECLARE v_start DATE;
    DECLARE v_end DATE;
    DECLARE v_copenhagen_today DATE;
    DECLARE v_certified_date DATE;
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE v_refresh_token CHAR(36);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF v_lock_acquired = 1 THEN
            CALL sp_fail_practice_operating_cost_publication(v_refresh_token);
            UPDATE bi_refresh_watermark
            SET refresh_state = 'FAILED',
                active_refresh_token = NULL
            WHERE pipeline_name = 'FACT_USER_DAY'
              AND active_refresh_token = v_refresh_token;
            DO RELEASE_LOCK('bi_refresh');
            SET v_lock_acquired = 0;
        END IF;
        RESIGNAL;
    END;

    SELECT GET_LOCK('bi_refresh', 300) INTO v_lock_acquired;
    IF COALESCE(v_lock_acquired, 0) <> 1 THEN
        LEAVE nightly_body;
    END IF;

    SET v_refresh_token = UUID();
    UPDATE bi_refresh_watermark
    SET refresh_state = 'RUNNING',
        active_refresh_token = v_refresh_token
    WHERE pipeline_name = 'FACT_USER_DAY';

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY full refresh could not be started';
    END IF;

    SET v_copenhagen_today = DATE(CONVERT_TZ(
        UTC_TIMESTAMP(), 'UTC', 'Europe/Copenhagen'));
    SET v_start = DATE_FORMAT(
        DATE_SUB(v_copenhagen_today, INTERVAL p_lookback_months MONTH), '%Y-%m-01');
    SET v_end = DATE_FORMAT(
        DATE_ADD(v_copenhagen_today, INTERVAL p_forward_months MONTH), '%Y-%m-01');
    SET v_certified_date = DATE_SUB(v_copenhagen_today, INTERVAL 1 DAY);

    CALL sp_recalculate_availability(v_start, v_end, NULL);
    CALL sp_aggregate_work(v_start, v_end);
    CALL sp_recalculate_budgets(v_start, v_end, NULL);
    CALL sp_begin_practice_operating_cost_publication(v_refresh_token);
    CALL sp_refresh_fact_tables();
    CALL sp_refresh_practice_opex_mat();
    CALL sp_stage_practice_operating_cost_publication(v_refresh_token);

    START TRANSACTION;

    UPDATE bi_refresh_watermark
    SET certified_complete_through_date = CASE
            WHEN v_start <= v_certified_date AND v_end > v_certified_date
                THEN v_certified_date
            ELSE certified_complete_through_date
        END,
        last_full_refresh_at = UTC_TIMESTAMP(6),
        refresh_state = 'READY',
        active_refresh_token = NULL
    WHERE pipeline_name = 'FACT_USER_DAY'
      AND active_refresh_token = v_refresh_token;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY full-refresh publication could not be certified';
    END IF;

    CALL sp_finalize_practice_operating_cost_publication(v_refresh_token);

    COMMIT;

    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;


-- --------------------------------------------------------------------------
-- Incremental refresh: record successful source refresh time, but never
-- advance full-date certification.
-- --------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_incremental_bi_refresh;

DELIMITER $$

CREATE PROCEDURE sp_incremental_bi_refresh()
proc_body: BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE v_previous_refresh_state VARCHAR(16) DEFAULT 'UNINITIALIZED';
    DECLARE v_refresh_token CHAR(36);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF v_lock_acquired = 1 THEN
            CALL sp_fail_practice_operating_cost_publication(v_refresh_token);
            UPDATE bi_refresh_watermark
            SET refresh_state = 'FAILED',
                active_refresh_token = NULL
            WHERE pipeline_name = 'FACT_USER_DAY'
              AND active_refresh_token = v_refresh_token;
            DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;
            DO RELEASE_LOCK('bi_refresh');
            SET v_lock_acquired = 0;
        END IF;
        RESIGNAL;
    END;

    SELECT GET_LOCK('bi_refresh', 0) INTO v_lock_acquired;
    IF COALESCE(v_lock_acquired, 0) <> 1 THEN
        LEAVE proc_body;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM fact_change_log
    WHERE processed_at IS NULL;

    IF v_count = 0 THEN
        DO RELEASE_LOCK('bi_refresh');
        LEAVE proc_body;
    END IF;

    SELECT refresh_state INTO v_previous_refresh_state
    FROM bi_refresh_watermark
    WHERE pipeline_name = 'FACT_USER_DAY';

    SET v_refresh_token = UUID();
    UPDATE bi_refresh_watermark
    SET refresh_state = 'RUNNING',
        active_refresh_token = v_refresh_token
    WHERE pipeline_name = 'FACT_USER_DAY';

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY incremental refresh could not be started';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    CREATE TEMPORARY TABLE tmp_affected_ranges (
        useruuid VARCHAR(36) NOT NULL,
        month_start DATE NOT NULL,
        month_end DATE NOT NULL,
        PRIMARY KEY (useruuid, month_start)
    ) ENGINE=MEMORY;

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

    CALL sp_begin_practice_operating_cost_publication(v_refresh_token);
    CALL sp_refresh_fact_tables();
    CALL sp_refresh_practice_opex_mat();
    CALL sp_stage_practice_operating_cost_publication(v_refresh_token);

    START TRANSACTION;

    UPDATE fact_change_log
    SET processed_at = NOW()
    WHERE processed_at IS NULL;

    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    UPDATE bi_refresh_watermark
    SET last_incremental_refresh_at = UTC_TIMESTAMP(6),
        refresh_state = v_previous_refresh_state,
        active_refresh_token = NULL
    WHERE pipeline_name = 'FACT_USER_DAY'
      AND active_refresh_token = v_refresh_token;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY incremental refresh state could not be restored';
    END IF;

    CALL sp_finalize_practice_operating_cost_publication(v_refresh_token);

    COMMIT;

    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;
