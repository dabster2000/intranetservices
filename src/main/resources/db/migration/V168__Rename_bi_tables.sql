-- =============================================================================
-- Migration V168: Rename bi_* tables to fact_* convention
--
-- Purpose:
--   Phase 5.8 — Standardize table naming to the fact_* convention used by
--   the materialized tables (fact_*_mat). Creates compatibility views with
--   the old names so that any code not yet updated continues to work.
--
-- Steps:
--   1. Drop existing triggers that reference the old table names
--   2. Rename tables
--   3. Create compatibility views with old names
--   4. Recreate triggers on the new table names
--   5. Recreate stored procedures referencing new table names
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Drop triggers that reference old table names
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_work_after_insert;
DROP TRIGGER IF EXISTS trg_work_after_update;
DROP TRIGGER IF EXISTS trg_work_after_delete;
DROP TRIGGER IF EXISTS trg_vacation_after_insert;
DROP TRIGGER IF EXISTS trg_vacation_after_update;
DROP TRIGGER IF EXISTS trg_vacation_after_delete;
DROP TRIGGER IF EXISTS trg_userstatus_after_insert;
DROP TRIGGER IF EXISTS trg_userstatus_after_update;
DROP TRIGGER IF EXISTS trg_userstatus_after_delete;
DROP TRIGGER IF EXISTS trg_contract_consultants_after_insert;
DROP TRIGGER IF EXISTS trg_contract_consultants_after_update;
DROP TRIGGER IF EXISTS trg_contract_consultants_after_delete;
DROP TRIGGER IF EXISTS trg_salary_after_insert;
DROP TRIGGER IF EXISTS trg_salary_after_update;
DROP TRIGGER IF EXISTS trg_salary_after_delete;

-- ---------------------------------------------------------------------------
-- 2. Rename tables
-- ---------------------------------------------------------------------------
RENAME TABLE bi_data_per_day TO fact_user_day;
RENAME TABLE bi_budget_per_day TO fact_budget_day;
RENAME TABLE bi_change_log TO fact_change_log;

-- ---------------------------------------------------------------------------
-- 3. Create compatibility views with old names
-- ---------------------------------------------------------------------------
CREATE VIEW bi_data_per_day AS SELECT * FROM fact_user_day;
CREATE VIEW bi_budget_per_day AS SELECT * FROM fact_budget_day;
CREATE VIEW bi_change_log AS SELECT * FROM fact_change_log;

-- ---------------------------------------------------------------------------
-- 4. Recreate triggers on new table names (write to fact_change_log)
-- ---------------------------------------------------------------------------
DELIMITER //

-- ----- work table -----
CREATE TRIGGER trg_work_after_insert AFTER INSERT ON work
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.registered, 'WORK', 'work', NEW.uuid);
END //

CREATE TRIGGER trg_work_after_update AFTER UPDATE ON work
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.registered, 'WORK', 'work', NEW.uuid);
    IF OLD.registered != NEW.registered THEN
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.registered, 'WORK', 'work', OLD.uuid);
    END IF;
    IF OLD.useruuid != NEW.useruuid THEN
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.registered, 'WORK', 'work', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_work_after_delete AFTER DELETE ON work
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.registered, 'WORK', 'work', OLD.uuid);
END //

-- ----- vacation table -----
CREATE TRIGGER trg_vacation_after_insert AFTER INSERT ON vacation
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.date, 'VACATION', 'vacation', NEW.uuid);
END //

CREATE TRIGGER trg_vacation_after_update AFTER UPDATE ON vacation
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.date, 'VACATION', 'vacation', NEW.uuid);
    IF OLD.date != NEW.date THEN
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.date, 'VACATION', 'vacation', OLD.uuid);
    END IF;
    IF OLD.useruuid != NEW.useruuid THEN
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.date, 'VACATION', 'vacation', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_vacation_after_delete AFTER DELETE ON vacation
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.date, 'VACATION', 'vacation', OLD.uuid);
END //

-- ----- userstatus table -----
CREATE TRIGGER trg_userstatus_after_insert AFTER INSERT ON userstatus
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.statusdate, 'STATUS', 'userstatus', NEW.uuid);
END //

CREATE TRIGGER trg_userstatus_after_update AFTER UPDATE ON userstatus
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.statusdate, 'STATUS', 'userstatus', NEW.uuid);
    IF OLD.statusdate != NEW.statusdate THEN
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.statusdate, 'STATUS', 'userstatus', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_userstatus_after_delete AFTER DELETE ON userstatus
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.statusdate, 'STATUS', 'userstatus', OLD.uuid);
END //

-- ----- contract_consultants table -----
CREATE TRIGGER trg_contract_consultants_after_insert AFTER INSERT ON contract_consultants
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'CONTRACT', 'contract_consultants', NEW.uuid);
END //

CREATE TRIGGER trg_contract_consultants_after_update AFTER UPDATE ON contract_consultants
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'CONTRACT', 'contract_consultants', NEW.uuid);
    IF OLD.activefrom != NEW.activefrom THEN
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.activefrom, 'CONTRACT', 'contract_consultants', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_contract_consultants_after_delete AFTER DELETE ON contract_consultants
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.activefrom, 'CONTRACT', 'contract_consultants', OLD.uuid);
END //

-- ----- salary table -----
CREATE TRIGGER trg_salary_after_insert AFTER INSERT ON salary
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'SALARY', 'salary', NEW.uuid);
END //

CREATE TRIGGER trg_salary_after_update AFTER UPDATE ON salary
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (NEW.useruuid, NEW.activefrom, 'SALARY', 'salary', NEW.uuid);
    IF OLD.activefrom != NEW.activefrom THEN
        INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
        VALUES (OLD.useruuid, OLD.activefrom, 'SALARY', 'salary', OLD.uuid);
    END IF;
END //

CREATE TRIGGER trg_salary_after_delete AFTER DELETE ON salary
FOR EACH ROW
BEGIN
    INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id)
    VALUES (OLD.useruuid, OLD.activefrom, 'SALARY', 'salary', OLD.uuid);
END //

DELIMITER ;

-- ---------------------------------------------------------------------------
-- 5. Recreate stored procedures to use new table names
--    (sp_recalculate_availability already updated in V165)
-- ---------------------------------------------------------------------------

-- sp_aggregate_work
DROP PROCEDURE IF EXISTS sp_aggregate_work;

DELIMITER //
CREATE PROCEDURE sp_aggregate_work(
    IN p_start_date DATE,
    IN p_end_date   DATE
)
BEGIN
    -- Reset billable hours and revenue for the period
    UPDATE fact_user_day
    SET registered_billable_hours = 0,
        registered_amount = 0,
        last_update = NOW()
    WHERE document_date >= p_start_date
      AND document_date < p_end_date;

    -- Update from work_full
    UPDATE fact_user_day bdd
    JOIN (
        SELECT useruuid, registered,
            SUM(CASE WHEN rate > 0 THEN workduration ELSE 0 END) AS billable_hours,
            SUM(CASE WHEN rate > 0 THEN workduration * rate ELSE 0 END) AS revenue
        FROM work_full
        WHERE registered >= p_start_date AND registered < p_end_date
        GROUP BY useruuid, registered
    ) w ON bdd.useruuid = w.useruuid AND bdd.document_date = w.registered
    SET bdd.registered_billable_hours = w.billable_hours,
        bdd.registered_amount = w.revenue,
        bdd.last_update = NOW()
    WHERE bdd.document_date >= p_start_date
      AND bdd.document_date < p_end_date;
END //
DELIMITER ;

-- sp_recalculate_budgets
DROP PROCEDURE IF EXISTS sp_recalculate_budgets;

DELIMITER //
CREATE PROCEDURE sp_recalculate_budgets(
    IN p_start_date DATE,
    IN p_end_date   DATE,
    IN p_user_uuid  VARCHAR(36)
)
BEGIN
    -- Delete existing budget data for the period
    DELETE FROM fact_budget_day
    WHERE document_date >= p_start_date
      AND document_date < p_end_date
      AND (p_user_uuid IS NULL OR useruuid = p_user_uuid);

    -- Insert raw budgets from contracts
    INSERT INTO fact_budget_day (
        document_date, year, month, day,
        clientuuid, useruuid, companyuuid, contractuuid,
        budgetHours, budgetHoursWithNoAvailabilityAdjustment, rate
    )
    SELECT
        dd.date_key,
        dd.year,
        dd.month,
        dd.day,
        c.clientuuid,
        cc.useruuid,
        NULL,
        c.uuid AS contractuuid,
        cc.hours / 5.0 AS budgetHours,
        cc.hours / 5.0 AS budgetHoursRaw,
        cc.rate * (1 - COALESCE(cti.value, 0)) AS rate
    FROM contracts c
    JOIN contract_consultants cc ON cc.contractuuid = c.uuid
    JOIN dim_date dd ON dd.date_key >= cc.activefrom
        AND dd.date_key < COALESCE(cc.activeto, '2035-01-01')
        AND dd.date_key >= c.activefrom
        AND dd.date_key < COALESCE(c.activeto, '2035-01-01')
        AND dd.is_weekend = 0
    LEFT JOIN contract_type_items cti
        ON cti.contractuuid = c.uuid
        AND cti.item_type = 'DISCOUNT'
    WHERE dd.date_key >= p_start_date
      AND dd.date_key < p_end_date
      AND c.status IN ('BUDGET', 'TIME_AND_MATERIAL', 'FIXED_PRICE')
      AND (p_user_uuid IS NULL OR cc.useruuid = p_user_uuid);

    -- Availability normalization: cap total daily budget at net available hours
    UPDATE fact_budget_day fbd
    JOIN (
        SELECT
            fbd2.useruuid,
            fbd2.document_date,
            SUM(fbd2.budgetHours) AS total_budget,
            COALESCE(fud.net_available_hours, 0) AS net_available
        FROM fact_budget_day fbd2
        LEFT JOIN fact_user_day fud
            ON fud.useruuid = fbd2.useruuid
            AND fud.document_date = fbd2.document_date
        WHERE fbd2.document_date >= p_start_date
          AND fbd2.document_date < p_end_date
          AND (p_user_uuid IS NULL OR fbd2.useruuid = p_user_uuid)
        GROUP BY fbd2.useruuid, fbd2.document_date
        HAVING total_budget > net_available AND net_available > 0
    ) overalloc ON fbd.useruuid = overalloc.useruuid
        AND fbd.document_date = overalloc.document_date
    SET fbd.budgetHours = fbd.budgetHours * (overalloc.net_available / overalloc.total_budget)
    WHERE fbd.document_date >= p_start_date
      AND fbd.document_date < p_end_date;

    -- Update company UUID from fact_user_day
    UPDATE fact_budget_day fbd
    JOIN fact_user_day fud ON fud.useruuid = fbd.useruuid AND fud.document_date = fbd.document_date
    SET fbd.companyuuid = fud.companyuuid
    WHERE fbd.document_date >= p_start_date
      AND fbd.document_date < p_end_date
      AND (p_user_uuid IS NULL OR fbd.useruuid = p_user_uuid);
END //
DELIMITER ;

-- sp_aggregate_monthly
DROP PROCEDURE IF EXISTS sp_aggregate_monthly;

DELIMITER //
CREATE PROCEDURE sp_aggregate_monthly(
    IN p_start_date DATE,
    IN p_end_date   DATE
)
BEGIN
    DELETE FROM company_work_per_month
    WHERE (year > YEAR(p_start_date) OR (year = YEAR(p_start_date) AND month >= MONTH(p_start_date)))
      AND (year < YEAR(p_end_date) OR (year = YEAR(p_end_date) AND month <= MONTH(p_end_date)));

    INSERT INTO company_work_per_month (uuid, year, month, consultant_company_uuid, hours, billed)
    SELECT
        UUID(), fud.year, fud.month, fud.companyuuid,
        SUM(COALESCE(fud.registered_billable_hours, 0)),
        SUM(COALESCE(fud.registered_amount, 0))
    FROM fact_user_day fud
    WHERE fud.document_date >= p_start_date AND fud.document_date < p_end_date
      AND fud.consultant_type IN ('CONSULTANT', 'STUDENT')
      AND fud.status_type = 'ACTIVE'
    GROUP BY fud.year, fud.month, fud.companyuuid;
END //
DELIMITER ;

-- sp_nightly_bi_refresh (master orchestration - no table references to change)
-- Already calls sub-procedures by name, no direct table references.
-- No changes needed.

-- sp_incremental_bi_refresh
DROP PROCEDURE IF EXISTS sp_incremental_bi_refresh;

DELIMITER //
CREATE PROCEDURE sp_incremental_bi_refresh()
proc_body: BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_lock_acquired INT DEFAULT 0;

    SELECT GET_LOCK('bi_incremental_refresh', 0) INTO v_lock_acquired;
    IF v_lock_acquired = 0 THEN
        LEAVE proc_body;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM fact_change_log
    WHERE processed_at IS NULL;

    IF v_count = 0 THEN
        DO RELEASE_LOCK('bi_incremental_refresh');
        LEAVE proc_body;
    END IF;

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
    FROM fact_change_log
    WHERE processed_at IS NULL;

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

    DO RELEASE_LOCK('bi_incremental_refresh');
END //

DELIMITER ;
