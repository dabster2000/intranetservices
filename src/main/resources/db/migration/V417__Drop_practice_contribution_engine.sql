-- =====================================================================================================
-- V417: Drop the practice-contribution engine (forward-only teardown).
--
-- The practice-contribution engine created by V407-V416 is being removed from the Java code in an
-- earlier deploy (PR1). This migration ships in a later PR (PR2) and drops the engine's remaining
-- database objects. It is forward-only: there is no down migration.
--
-- WHAT IS REMOVED
--   - Additive practice-revenue/cost triggers on shared source tables
--     (accounting_accounts, user_practice_history, userstatus, currences, task, project,
--      contract_project, contracts) added by V411/V412/V416.
--   - Practice engine stored procedures (mark/enqueue/supersede/manifest/publication/completeness/opex).
--   - The v_practice_salary_completeness view.
--   - All practice_* and fact_practice_* engine tables created by V409/V411/V412.
--
-- WHAT IS DELIBERATELY KEPT (see "EXPLICITLY NOT IN V417" below)
--   - user_practice_history and its V407 write triggers on `user`
--     (trg_user_practice_history_after_insert/update/delete) -- prospective practice attribution.
--   - practice_settings, fact_opex (V408/V409 view) and fact_opex_mat semantics.
--   - The shared BI orchestrators sp_nightly_bi_refresh / sp_incremental_bi_refresh, which are
--     recreated below WITHOUT any practice plumbing (they retain the OPEX post-pass that fixes an
--     ordering-staleness bug in sp_refresh_fact_tables, renamed sp_refresh_opex_mat_post_pass).
--   - The V256 24-month contract_consultants fact_change_log fan-out triggers, recreated below
--     WITHOUT their practice dependency-marker calls (V412's NULL-safe update guard is preserved).
--   - All additive columns on shared tables (invoiceitems pricing_*/credit_copy_*,
--     invoice_item_attributions source_*/attribution_*, fact_opex_mat.materialized_at,
--     fact_employee_monthly_mat.materialized_at, bi_refresh_watermark.*_version). No ALTER ... DROP
--     COLUMN is performed here to keep the migration online-safe under ECS Express canary cutover.
--
-- IDEMPOTENCY
--   Every DROP uses IF EXISTS; every recreated object uses DROP-then-CREATE. Flyway repair-at-start
--   may re-run partial state and staging is periodically rebuilt from prod, so this migration must be
--   safe to re-apply. Table drops are ordered child -> parent so the remaining set is always
--   FK-consistent; SET FOREIGN_KEY_CHECKS is intentionally NOT used.
--
-- CONCURRENCY
--   ev_bi_incremental_refresh fires every 5 minutes and (until PR2 lands) still executes the OLD
--   orchestrator body that reads practice tables. Section 0 takes the session-level GET_LOCK('bi_refresh')
--   the orchestrators use, guaranteeing no orchestrator is mid-flight while we swap proc bodies and drop
--   objects. The lock is released in Section 6.
-- =====================================================================================================


-- =====================================================================================================
-- Section 0 -- serialize against BI runs.
-- Mirror V411's migration-lock pattern (GET_LOCK or SIGNAL), but wait up to 300s to match the nightly
-- orchestrator's own acquire timeout. Refuse to proceed if a FACT_USER_DAY refresh currently owns a
-- token. The lock is held for the remainder of this migration and released in Section 6.
-- =====================================================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_v417_acquire_migration_lock$$
CREATE PROCEDURE sp_v417_acquire_migration_lock()
BEGIN
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE v_active_owner CHAR(36) DEFAULT NULL;

    SELECT GET_LOCK('bi_refresh', 300) INTO v_lock_acquired;
    IF COALESCE(v_lock_acquired, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V417 could not acquire the bi_refresh migration lock';
    END IF;

    SELECT active_refresh_token INTO v_active_owner
      FROM bi_refresh_watermark
     WHERE pipeline_name = 'FACT_USER_DAY';
    IF v_active_owner IS NOT NULL THEN
        DO RELEASE_LOCK('bi_refresh');
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V417 refused an active FACT_USER_DAY owner';
    END IF;
END$$

DELIMITER ;

CALL sp_v417_acquire_migration_lock();
DROP PROCEDURE sp_v417_acquire_migration_lock;


-- =====================================================================================================
-- Section 1 -- recreate the shared orchestrators BEFORE dropping anything they used to reference.
-- =====================================================================================================

-- 1.1 -- The OPEX post-pass. Body copied EXACTLY from V409's sp_refresh_practice_opex_mat; only the
-- name changes. fact_opex allocates non-salary OPEX with fact_employee_monthly_mat weights, and the
-- legacy sp_refresh_fact_tables rebuilds OPEX before employee-monthly. The orchestrators must therefore
-- rebuild fact_opex_mat once more after the employee fact is current. This shared step MUST survive the
-- engine teardown, so it is preserved verbatim under an engine-neutral name.
DROP PROCEDURE IF EXISTS sp_refresh_opex_mat_post_pass;

DELIMITER $$

CREATE PROCEDURE sp_refresh_opex_mat_post_pass()
BEGIN
    TRUNCATE TABLE fact_opex_mat;

    INSERT IGNORE INTO fact_opex_mat
        (opex_id, company_id, cost_center_id, expense_category_id,
         expense_subcategory_id, practice_id, sector_id,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number, fiscal_month_key,
         opex_amount_dkk, invoice_count, is_payroll_flag,
         cost_type,
         data_source)
    SELECT
        opex_id, company_id, cost_center_id, expense_category_id,
        expense_subcategory_id, practice_id, sector_id,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number, fiscal_month_key,
        opex_amount_dkk, invoice_count, is_payroll_flag,
        cost_type,
        data_source
    FROM fact_opex;
END$$

DELIMITER ;

-- 1.2 -- Full nightly/weekly refresh. Recreated from the V413 body with all practice plumbing removed:
--   * CALL sp_refresh_practice_opex_mat()            -> CALL sp_refresh_opex_mat_post_pass()
--   * removed practice_revenue_source_watermark reads and their DECLAREs
--   * removed practice_cost_basis_refresh_request INSERT / LAST_INSERT_ID plumbing
--   * removed practice_operating_cost_publication UPDATE
--   * removed CALL sp_supersede_dominated_cost_requests
-- Everything else is byte-for-byte from V413: lock/token lifecycle incl. the `active_refresh_token IS NULL`
-- guard, the recalculate/aggregate/budget/fact-table calls, certified_complete_through_date logic,
-- full_refresh_version bump, FAILED exit handler, transaction boundaries, RELEASE_LOCK. Both parameters
-- are retained (ev_bi_weekly_full_rebuild calls it with (24,24); ev_bi_nightly_refresh with (3,24)).
DROP PROCEDURE IF EXISTS sp_nightly_bi_refresh;

DELIMITER $$

CREATE PROCEDURE sp_nightly_bi_refresh(
    IN p_lookback_months INT,
    IN p_forward_months INT
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
            UPDATE bi_refresh_watermark
               SET refresh_state = 'FAILED', active_refresh_token = NULL
             WHERE pipeline_name = 'FACT_USER_DAY'
               AND active_refresh_token = v_refresh_token;
            DO RELEASE_LOCK('bi_refresh');
        END IF;
        RESIGNAL;
    END;

    SELECT GET_LOCK('bi_refresh', 300) INTO v_lock_acquired;
    IF COALESCE(v_lock_acquired, 0) <> 1 THEN
        LEAVE nightly_body;
    END IF;

    SET v_refresh_token = UUID();
    UPDATE bi_refresh_watermark
       SET refresh_state = 'RUNNING', active_refresh_token = v_refresh_token
     WHERE pipeline_name = 'FACT_USER_DAY' AND active_refresh_token IS NULL;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY full refresh could not be started';
    END IF;

    SET v_copenhagen_today = DATE(CONVERT_TZ(UTC_TIMESTAMP(), 'UTC', 'Europe/Copenhagen'));
    SET v_start = DATE_FORMAT(DATE_SUB(v_copenhagen_today, INTERVAL p_lookback_months MONTH), '%Y-%m-01');
    SET v_end = DATE_FORMAT(DATE_ADD(v_copenhagen_today, INTERVAL p_forward_months MONTH), '%Y-%m-01');
    SET v_certified_date = DATE_SUB(v_copenhagen_today, INTERVAL 1 DAY);

    CALL sp_recalculate_availability(v_start, v_end, NULL);
    CALL sp_aggregate_work(v_start, v_end);
    CALL sp_recalculate_budgets(v_start, v_end, NULL);
    CALL sp_refresh_fact_tables();
    CALL sp_refresh_opex_mat_post_pass();

    START TRANSACTION;

    UPDATE bi_refresh_watermark
       SET certified_complete_through_date = CASE
               WHEN v_start <= v_certified_date AND v_end > v_certified_date
                   THEN v_certified_date
               ELSE certified_complete_through_date
           END,
           last_full_refresh_at = UTC_TIMESTAMP(6),
           full_refresh_version = full_refresh_version + 1,
           refresh_state = 'READY',
           active_refresh_token = NULL
     WHERE pipeline_name = 'FACT_USER_DAY'
       AND active_refresh_token = v_refresh_token;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY full refresh could not be certified';
    END IF;

    COMMIT;
    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;

-- 1.3 -- Incremental refresh. Recreated from the V413 body with the same treatment as 1.2. The
-- tmp_affected_ranges cursor loop, the fact_change_log processed_at marking, and the
-- incremental_refresh_version bump are all preserved; the MIN/MAX affected-range capture and the
-- version-vector variables existed solely to build the removed cost-basis request, so they are removed.
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
        DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;
        IF v_lock_acquired = 1 THEN
            UPDATE bi_refresh_watermark
               SET refresh_state = 'FAILED', active_refresh_token = NULL
             WHERE pipeline_name = 'FACT_USER_DAY'
               AND active_refresh_token = v_refresh_token;
            DO RELEASE_LOCK('bi_refresh');
        END IF;
        RESIGNAL;
    END;

    SELECT GET_LOCK('bi_refresh', 0) INTO v_lock_acquired;
    IF COALESCE(v_lock_acquired, 0) <> 1 THEN LEAVE proc_body; END IF;

    SELECT COUNT(*) INTO v_count FROM fact_change_log WHERE processed_at IS NULL;
    IF v_count = 0 THEN
        DO RELEASE_LOCK('bi_refresh');
        LEAVE proc_body;
    END IF;

    SELECT refresh_state INTO v_previous_refresh_state
      FROM bi_refresh_watermark WHERE pipeline_name = 'FACT_USER_DAY';
    SET v_refresh_token = UUID();
    UPDATE bi_refresh_watermark
       SET refresh_state = 'RUNNING', active_refresh_token = v_refresh_token
     WHERE pipeline_name = 'FACT_USER_DAY' AND active_refresh_token IS NULL;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY incremental refresh could not be started';
    END IF;

    CREATE TEMPORARY TABLE tmp_affected_ranges (
        useruuid VARCHAR(36) NOT NULL,
        month_start DATE NOT NULL,
        month_end DATE NOT NULL,
        PRIMARY KEY (useruuid, month_start)
    ) ENGINE=MEMORY;

    INSERT IGNORE INTO tmp_affected_ranges (useruuid, month_start, month_end)
    SELECT DISTINCT useruuid,
           DATE_FORMAT(affected_date, '%Y-%m-01'),
           DATE_FORMAT(affected_date + INTERVAL 1 MONTH, '%Y-%m-01')
      FROM fact_change_log WHERE processed_at IS NULL;

    BEGIN
        DECLARE done INT DEFAULT FALSE;
        DECLARE v_useruuid VARCHAR(36);
        DECLARE v_month_start DATE;
        DECLARE v_month_end DATE;
        DECLARE cur CURSOR FOR
            SELECT useruuid, month_start, month_end FROM tmp_affected_ranges;
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
        OPEN cur;
        read_loop: LOOP
            FETCH cur INTO v_useruuid, v_month_start, v_month_end;
            IF done THEN LEAVE read_loop; END IF;
            CALL sp_recalculate_availability(v_month_start, v_month_end, v_useruuid);
            CALL sp_aggregate_work(v_month_start, v_month_end);
            CALL sp_recalculate_budgets(v_month_start, v_month_end, v_useruuid);
        END LOOP;
        CLOSE cur;
    END;

    CALL sp_refresh_fact_tables();
    CALL sp_refresh_opex_mat_post_pass();

    START TRANSACTION;
    UPDATE fact_change_log SET processed_at = NOW() WHERE processed_at IS NULL;
    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;

    UPDATE bi_refresh_watermark
       SET last_incremental_refresh_at = UTC_TIMESTAMP(6),
           incremental_refresh_version = incremental_refresh_version + 1,
           refresh_state = v_previous_refresh_state,
           active_refresh_token = NULL
     WHERE pipeline_name = 'FACT_USER_DAY'
       AND active_refresh_token = v_refresh_token;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY incremental refresh could not be certified';
    END IF;

    COMMIT;
    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;


-- =====================================================================================================
-- Section 2 -- triggers.
-- 2a. Drop the additive practice-revenue/cost triggers on shared source tables. They call procedures
--     dropped in Section 3, so they must be removed first. The underlying tables (and the V407
--     user_practice_history write triggers on `user`) are untouched.
-- 2b. Recreate the contract_consultants triggers WITHOUT their practice dependency-marker calls. These
--     carry the V256 24-month fact_change_log fan-out that feeds incremental BI and must NOT be plain
--     dropped. V412's NULL-safe update guard (activeto compared with <=>) is preserved.
-- =====================================================================================================

-- accounting_accounts (V411)
DROP TRIGGER IF EXISTS trg_accounting_accounts_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_accounting_accounts_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_accounting_accounts_practice_revenue_ad;

-- user_practice_history (V411) -- table kept; only these engine triggers are removed
DROP TRIGGER IF EXISTS trg_user_practice_history_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_user_practice_history_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_user_practice_history_practice_revenue_ad;

-- userstatus (V411)
DROP TRIGGER IF EXISTS trg_userstatus_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_userstatus_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_userstatus_practice_revenue_ad;

-- currences (V412)
DROP TRIGGER IF EXISTS trg_currences_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_currences_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_currences_practice_revenue_ad;

-- task (V412)
DROP TRIGGER IF EXISTS trg_task_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_task_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_task_practice_revenue_ad;

-- project (V412 ai/ad, V416 au)
DROP TRIGGER IF EXISTS trg_project_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_project_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_project_practice_revenue_ad;

-- contract_project (V412)
DROP TRIGGER IF EXISTS trg_contract_project_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_contract_project_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_contract_project_practice_revenue_ad;

-- contracts (V412)
DROP TRIGGER IF EXISTS trg_contracts_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_contracts_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_contracts_practice_revenue_ad;

-- contract_consultants -- recreate the 24-month fan-out triggers minus the practice dependency calls.
DROP TRIGGER IF EXISTS trg_contract_consultants_after_insert;
DROP TRIGGER IF EXISTS trg_contract_consultants_after_update;
DROP TRIGGER IF EXISTS trg_contract_consultants_after_delete;

DELIMITER $$

CREATE TRIGGER trg_contract_consultants_after_insert
AFTER INSERT ON contract_consultants FOR EACH ROW
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
AFTER UPDATE ON contract_consultants FOR EACH ROW
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
    IF OLD.activefrom <> NEW.activefrom OR NOT (OLD.activeto <=> NEW.activeto) THEN
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
AFTER DELETE ON contract_consultants FOR EACH ROW
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


-- =====================================================================================================
-- Section 3 -- drop the practice engine stored procedures. Triggers that called them were removed in
-- Section 2; the shared orchestrators recreated in Section 1 no longer reference them.
-- =====================================================================================================
DROP PROCEDURE IF EXISTS sp_mark_practice_revenue_source_changed;
DROP PROCEDURE IF EXISTS sp_mark_practice_revenue_dependency_changed;
DROP PROCEDURE IF EXISTS sp_mark_practice_revenue_document_and_credit_dependents_changed;
DROP PROCEDURE IF EXISTS sp_enqueue_practice_cost_basis_refresh;
DROP PROCEDURE IF EXISTS sp_supersede_dominated_cost_requests;
DROP PROCEDURE IF EXISTS sp_advance_practice_dependency_manifest_input;
DROP PROCEDURE IF EXISTS sp_refresh_practice_opex_mat;
DROP PROCEDURE IF EXISTS sp_replace_practice_salary_completeness_mat;
DROP PROCEDURE IF EXISTS sp_refresh_practice_salary_completeness_mat;
DROP PROCEDURE IF EXISTS sp_begin_practice_operating_cost_publication;
DROP PROCEDURE IF EXISTS sp_stage_practice_operating_cost_publication;
DROP PROCEDURE IF EXISTS sp_finalize_practice_operating_cost_publication;
DROP PROCEDURE IF EXISTS sp_fail_practice_operating_cost_publication;
-- Hygiene: the V411 migration helper is dropped inside V411, but IF EXISTS keeps re-runs clean.
DROP PROCEDURE IF EXISTS sp_v411_acquire_migration_lock;


-- =====================================================================================================
-- Section 4 -- drop the completeness view (V409).
-- =====================================================================================================
DROP VIEW IF EXISTS v_practice_salary_completeness;


-- =====================================================================================================
-- Section 5 -- drop the engine tables, child -> parent so the FK graph stays consistent at every step.
--   Referencing (child) tables precede their referenced (parent) tables:
--     practice_basis_generation is a parent of the *_mat/basis/request/signal/publication tables;
--     practice_cost_basis_refresh_request is a parent of the signal + operating-cost publication;
--     fact_practice_net_revenue_item_mat is a parent of the allocation + dependency tables;
--     practice_user_effective_basis_mat is a parent of the daily-capacity table;
--     practice_revenue_source_watermark is a parent of the async-mutation-attempt table.
--   No SET FOREIGN_KEY_CHECKS. user_practice_history is NOT dropped.
-- =====================================================================================================
DROP TABLE IF EXISTS practice_cost_generation_signal;
DROP TABLE IF EXISTS practice_operating_cost_publication;
DROP TABLE IF EXISTS fact_practice_revenue_dependency_mat;
DROP TABLE IF EXISTS fact_practice_net_revenue_allocation_mat;
DROP TABLE IF EXISTS fact_practice_net_revenue_item_mat;
DROP TABLE IF EXISTS practice_revenue_publication;
DROP TABLE IF EXISTS practice_cost_basis_refresh_request;
DROP TABLE IF EXISTS practice_user_daily_capacity_basis_mat;
DROP TABLE IF EXISTS practice_user_effective_basis_mat;
DROP TABLE IF EXISTS practice_basis_dependency_manifest_mat;
DROP TABLE IF EXISTS fact_practice_cost_generation_mat;
DROP TABLE IF EXISTS fact_practice_fte_generation_mat;
DROP TABLE IF EXISTS fact_practice_cost_completeness_generation_mat;
DROP TABLE IF EXISTS practice_basis_generation;
DROP TABLE IF EXISTS practice_revenue_async_mutation_attempt;
DROP TABLE IF EXISTS practice_revenue_source_watermark;
DROP TABLE IF EXISTS practice_contribution_publication_control;
DROP TABLE IF EXISTS fact_practice_salary_completeness_mat;
DROP TABLE IF EXISTS practice_invoice_item_delivery_source;


-- =====================================================================================================
-- Section 6 -- release the migration lock acquired in Section 0.
-- =====================================================================================================
DO RELEASE_LOCK('bi_refresh');
