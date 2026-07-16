-- Forward-only repair for the first practice-contribution rollout.
-- V411/V412 may already be applied and are deliberately left unchanged.

ALTER TABLE fact_practice_net_revenue_item_mat
    MODIFY COLUMN item_cent_adjustment_dkk DECIMAL(48,4) NULL;

ALTER TABLE fact_practice_cost_completeness_generation_mat
    ADD COLUMN cost_month_end_practice_fallback_employee_month_count
        INT UNSIGNED NOT NULL DEFAULT 0;

ALTER TABLE practice_operating_cost_publication
    ADD COLUMN booked_available BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN booked_reason VARCHAR(64) NULL,
    ADD COLUMN booked_anchor_month DATE NULL,
    ADD COLUMN booked_current_start_month DATE NULL,
    ADD COLUMN booked_current_end_month DATE NULL,
    ADD COLUMN booked_prior_start_month DATE NULL,
    ADD COLUMN booked_prior_end_month DATE NULL,
    ADD COLUMN booked_plus_draft_available BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN booked_plus_draft_reason VARCHAR(64) NULL,
    ADD COLUMN booked_plus_draft_anchor_month DATE NULL,
    ADD COLUMN booked_plus_draft_current_start_month DATE NULL,
    ADD COLUMN booked_plus_draft_current_end_month DATE NULL,
    ADD COLUMN booked_plus_draft_prior_start_month DATE NULL,
    ADD COLUMN booked_plus_draft_prior_end_month DATE NULL;

-- Existing publications predate window certification. Keep them explicitly unavailable
-- until the Java cost owner writes a complete certified window.
UPDATE practice_operating_cost_publication
   SET booked_reason = 'SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW',
       booked_plus_draft_reason = 'SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW'
 WHERE publication_id = 1;

ALTER TABLE practice_operating_cost_publication
    ADD CONSTRAINT chk_pocp_booked_window_shape CHECK (
        (booked_available = TRUE
         AND booked_reason IS NULL
         AND booked_anchor_month IS NOT NULL
         AND booked_current_start_month IS NOT NULL
         AND booked_current_end_month IS NOT NULL
         AND booked_prior_start_month IS NOT NULL
         AND booked_prior_end_month IS NOT NULL)
        OR
        (booked_available = FALSE
         AND booked_reason IS NOT NULL
         AND booked_anchor_month IS NULL
         AND booked_current_start_month IS NULL
         AND booked_current_end_month IS NULL
         AND booked_prior_start_month IS NULL
         AND booked_prior_end_month IS NULL)
    ),
    ADD CONSTRAINT chk_pocp_booked_plus_draft_window_shape CHECK (
        (booked_plus_draft_available = TRUE
         AND booked_plus_draft_reason IS NULL
         AND booked_plus_draft_anchor_month IS NOT NULL
         AND booked_plus_draft_current_start_month IS NOT NULL
         AND booked_plus_draft_current_end_month IS NOT NULL
         AND booked_plus_draft_prior_start_month IS NOT NULL
         AND booked_plus_draft_prior_end_month IS NOT NULL)
        OR
        (booked_plus_draft_available = FALSE
         AND booked_plus_draft_reason IS NOT NULL
         AND booked_plus_draft_anchor_month IS NULL
         AND booked_plus_draft_current_start_month IS NULL
         AND booked_plus_draft_current_end_month IS NULL
         AND booked_plus_draft_prior_start_month IS NULL
         AND booked_plus_draft_prior_end_month IS NULL)
    );

-- V412 intentionally seeded at the then-current high-water. If no revenue generation has
-- ever been built or served, the next full build reads delivery sources directly, so rows
-- accumulated while the feature remained dark may be skipped exactly once. Never perform
-- this catch-up after a generation exists or while either build/serving gate is enabled.
UPDATE practice_revenue_source_watermark
   SET source_state = 'FAILED',
       attempt_token = NULL,
       started_at = NULL,
       completed_at = UTC_TIMESTAMP(6),
       last_observed_at = UTC_TIMESTAMP(6),
       safe_reason = 'FACT_CHANGE_LOG_RETENTION_GAP',
       retention_gap_reason = 'FACT_CHANGE_LOG_RETENTION_GAP',
       optimistic_version = optimistic_version + 1
 WHERE source_name = 'DELIVERY_EVIDENCE'
   AND last_pruned_fact_change_log_id > last_fact_change_log_id;

UPDATE practice_revenue_source_watermark w
JOIN practice_revenue_publication p
  ON p.publication_key = 'PRACTICE_CONTRIBUTION'
JOIN practice_contribution_publication_control c
  ON c.control_id = 1
   SET w.last_fact_change_log_id = GREATEST(
           w.last_fact_change_log_id,
           COALESCE((SELECT MAX(f.id) FROM fact_change_log f), 0)),
       w.last_observed_at = UTC_TIMESTAMP(6),
       w.safe_reason = 'INITIAL_DELIVERY_CURSOR_CATCH_UP',
       w.optimistic_version = w.optimistic_version + 1
 WHERE w.source_name = 'DELIVERY_EVIDENCE'
   AND w.source_state = 'READY'
   AND w.recovery_token IS NULL
   AND w.retention_gap_reason IS NULL
   AND w.last_pruned_fact_change_log_id <= w.last_fact_change_log_id
   AND p.status = 'UNINITIALIZED'
   AND p.published_generation_id IS NULL
   AND p.previous_generation_id IS NULL
   AND p.attempt_generation_id IS NULL
   AND c.refresh_enabled = FALSE
   AND c.contribution_serving_enabled = FALSE
   AND NOT EXISTS (SELECT 1 FROM fact_practice_net_revenue_item_mat);

-- Previous generations are retained for audit only. They must not make a current source
-- mutation relevant; only the published generation and the owned attempt are active.
DROP PROCEDURE IF EXISTS sp_mark_practice_revenue_dependency_changed;

DELIMITER $$

CREATE PROCEDURE sp_mark_practice_revenue_dependency_changed(
    IN p_source_name VARCHAR(40),
    IN p_dependency_kind VARCHAR(40),
    IN p_dependency_key VARCHAR(160)
)
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE v_month DATE;
    DECLARE v_status VARCHAR(20) DEFAULT 'UNINITIALIZED';
    DECLARE dep CURSOR FOR
        SELECT DISTINCT d.dependent_recognized_month
          FROM fact_practice_revenue_dependency_mat d
          JOIN practice_revenue_publication p
            ON d.generation_id IN (
                p.published_generation_id, p.attempt_generation_id
            )
         WHERE p.publication_key = 'PRACTICE_CONTRIBUTION'
           AND d.dependency_source_category = p_source_name
           AND d.dependency_kind = p_dependency_kind
           AND d.dependency_key = p_dependency_key;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    SELECT status INTO v_status
      FROM practice_revenue_publication
     WHERE publication_key = 'PRACTICE_CONTRIBUTION';

    IF v_status IN ('UNINITIALIZED', 'RUNNING') THEN
        CALL sp_mark_practice_revenue_source_changed(p_source_name, NULL);
    ELSE
        OPEN dep;
        dependency_loop: LOOP
            FETCH dep INTO v_month;
            IF done THEN LEAVE dependency_loop; END IF;
            CALL sp_mark_practice_revenue_source_changed(p_source_name, v_month);
        END LOOP;
        CLOSE dep;
        UPDATE practice_revenue_source_watermark
           SET last_observed_at = UTC_TIMESTAMP(6)
         WHERE source_name = p_source_name;
    END IF;
END$$

DELIMITER ;

-- =====================================================================================================
-- Defect 9 (cost-request supersession lifecycle) and Defect 10 (dependency-manifest lifecycle).
-- Forward-only. V411 is immutable, so the enqueue producers are re-created here to (1) retire dominated
-- older PENDING requests to SUPERSEDED with a valid successor pointer and (2) carry the manifest lifecycle.
-- =====================================================================================================

DELIMITER $$

-- Retire every dominated older PENDING request to SUPERSEDED pointing at a newer covering request.
-- Only strictly-older PENDING rows are touched: the newest is never superseded and a request can never
-- supersede itself. RUNNING and terminal rows are left untouched — their owners handle supersession.
DROP PROCEDURE IF EXISTS sp_supersede_dominated_cost_requests$$
CREATE PROCEDURE sp_supersede_dominated_cost_requests(IN p_new_request_id BIGINT UNSIGNED)
BEGIN
    IF p_new_request_id IS NOT NULL THEN
        UPDATE practice_cost_basis_refresh_request
           SET status = 'SUPERSEDED',
               superseded_by_request_id = p_new_request_id,
               owner_token = NULL,
               completed_at = UTC_TIMESTAMP(6),
               safe_reason = 'SUPERSEDED_BY_NEWER_INPUT',
               optimistic_version = optimistic_version + 1
         WHERE status = 'PENDING' AND request_id < p_new_request_id;
    END IF;
END$$

-- Re-created from V411 with the supersession compare-and-set appended after the latest-pointer advance.
DROP PROCEDURE IF EXISTS sp_enqueue_practice_cost_basis_refresh$$
CREATE PROCEDURE sp_enqueue_practice_cost_basis_refresh(
    IN p_cause VARCHAR(40),
    IN p_trigger_origin VARCHAR(20),
    IN p_affected_start DATE,
    IN p_affected_end DATE
)
BEGIN
    DECLARE v_full_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_incremental_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_basis_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_finance_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_classification_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_cause_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_vector CHAR(64);
    DECLARE v_request_key CHAR(64);
    DECLARE v_request_id BIGINT UNSIGNED;

    IF p_cause NOT IN ('PRACTICE_BASIS_INPUT', 'COST_GL_INPUT', 'DEPENDENCY_MANIFEST_INPUT')
       OR p_trigger_origin NOT IN ('DIRTY_MARKER', 'SCHEDULER', 'OPERATOR') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid practice cost refresh cause/origin';
    END IF;

    SELECT full_refresh_version, incremental_refresh_version
      INTO v_full_version, v_incremental_version
      FROM bi_refresh_watermark WHERE pipeline_name='FACT_USER_DAY';
    SELECT source_version INTO v_basis_version FROM practice_revenue_source_watermark
     WHERE source_name='PRACTICE_BASIS_INPUT';
    SELECT source_version INTO v_finance_version FROM practice_revenue_source_watermark
     WHERE source_name='FINANCE_GL';
    SELECT source_version INTO v_classification_version FROM practice_revenue_source_watermark
     WHERE source_name='ACCOUNT_CLASSIFICATION';

    SET v_cause_version = CASE p_cause
        WHEN 'PRACTICE_BASIS_INPUT' THEN v_basis_version
        WHEN 'COST_GL_INPUT' THEN GREATEST(v_finance_version, v_classification_version)
        ELSE (SELECT dependency_manifest_input_version FROM bi_refresh_watermark
               WHERE pipeline_name='FACT_USER_DAY')
    END;
    SET v_vector = SHA2(CONCAT_WS('|', v_full_version, v_incremental_version,
                                  v_basis_version, v_finance_version,
                                  v_classification_version,
                                  COALESCE(DATE_FORMAT(p_affected_start, '%Y-%m-%d'), 'GLOBAL'),
                                  COALESCE(DATE_FORMAT(p_affected_end, '%Y-%m-%d'), 'GLOBAL')), 256);
    SET v_request_key = SHA2(CONCAT_WS('|', p_cause, v_cause_version, v_vector), 256);

    INSERT INTO practice_cost_basis_refresh_request (
        request_key, cause, trigger_origin, cause_input_version,
        expected_full_refresh_version, expected_incremental_refresh_version,
        expected_practice_basis_input_version, expected_finance_gl_version,
        expected_account_classification_version, input_vector_fingerprint,
        affected_start_date, affected_end_date
    ) VALUES (
        v_request_key, p_cause, p_trigger_origin, v_cause_version,
        v_full_version, v_incremental_version, v_basis_version, v_finance_version,
        v_classification_version, v_vector, p_affected_start, p_affected_end
    ) ON DUPLICATE KEY UPDATE request_id=LAST_INSERT_ID(request_id);
    SET v_request_id=LAST_INSERT_ID();

    UPDATE practice_operating_cost_publication
       SET latest_cost_basis_request_id=v_request_id,
           latest_cost_basis_request_vector=v_vector,
           publication_version=publication_version+1
     WHERE publication_id=1
       AND (latest_cost_basis_request_id IS NULL OR latest_cost_basis_request_id <= v_request_id);

    CALL sp_supersede_dominated_cost_requests(v_request_id);
END$$

-- Accepted-manifest-change / BASIS_COVERAGE_MISS escalation. Advances the monotonic dependency-manifest
-- input version (even when the fingerprint reverts to a historically seen value) and enqueues one
-- DEPENDENCY_MANIFEST_INPUT request carrying the recomputed manifest fingerprint and affected bounds.
-- An identical still-PENDING miss for the same fingerprint+vector is idempotent (no version advance).
DROP PROCEDURE IF EXISTS sp_advance_practice_dependency_manifest_input$$
CREATE PROCEDURE sp_advance_practice_dependency_manifest_input(
    IN p_affected_start DATE,
    IN p_affected_end DATE,
    IN p_dependency_fingerprint CHAR(64)
)
BEGIN
    DECLARE v_full BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_incremental BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_basis BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_finance BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_classification BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_dep BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_vector CHAR(64);
    DECLARE v_request_key CHAR(64);
    DECLARE v_request_id BIGINT UNSIGNED;
    DECLARE v_existing BIGINT UNSIGNED DEFAULT NULL;

    SELECT full_refresh_version, incremental_refresh_version, dependency_manifest_input_version
      INTO v_full, v_incremental, v_dep
      FROM bi_refresh_watermark WHERE pipeline_name='FACT_USER_DAY';
    SELECT source_version INTO v_basis FROM practice_revenue_source_watermark
     WHERE source_name='PRACTICE_BASIS_INPUT';
    SELECT source_version INTO v_finance FROM practice_revenue_source_watermark
     WHERE source_name='FINANCE_GL';
    SELECT source_version INTO v_classification FROM practice_revenue_source_watermark
     WHERE source_name='ACCOUNT_CLASSIFICATION';

    SET v_vector = SHA2(CONCAT_WS('|', v_full, v_incremental, v_basis, v_finance, v_classification,
                                  COALESCE(DATE_FORMAT(p_affected_start, '%Y-%m-%d'), 'GLOBAL'),
                                  COALESCE(DATE_FORMAT(p_affected_end, '%Y-%m-%d'), 'GLOBAL')), 256);

    SELECT request_id INTO v_existing FROM practice_cost_basis_refresh_request
     WHERE cause='DEPENDENCY_MANIFEST_INPUT' AND status='PENDING'
       AND dependency_fingerprint <=> p_dependency_fingerprint
       AND input_vector_fingerprint = v_vector
     ORDER BY request_id DESC LIMIT 1;

    IF v_existing IS NOT NULL THEN
        DO LAST_INSERT_ID(v_existing);
    ELSE
        UPDATE bi_refresh_watermark
           SET dependency_manifest_input_version = dependency_manifest_input_version + 1
         WHERE pipeline_name='FACT_USER_DAY';
        SET v_dep = v_dep + 1;
        SET v_request_key = SHA2(CONCAT_WS('|', 'DEPENDENCY_MANIFEST_INPUT', v_dep, v_vector), 256);

        INSERT INTO practice_cost_basis_refresh_request (
            request_key, cause, trigger_origin, cause_input_version,
            expected_full_refresh_version, expected_incremental_refresh_version,
            expected_practice_basis_input_version, expected_finance_gl_version,
            expected_account_classification_version, input_vector_fingerprint,
            dependency_fingerprint, affected_start_date, affected_end_date
        ) VALUES (
            v_request_key, 'DEPENDENCY_MANIFEST_INPUT', 'SCHEDULER', v_dep,
            v_full, v_incremental, v_basis, v_finance, v_classification, v_vector,
            p_dependency_fingerprint, p_affected_start, p_affected_end
        ) ON DUPLICATE KEY UPDATE request_id=LAST_INSERT_ID(request_id);
        SET v_request_id=LAST_INSERT_ID();

        UPDATE practice_operating_cost_publication
           SET latest_cost_basis_request_id=v_request_id,
               latest_cost_basis_request_vector=v_vector,
               publication_version=publication_version+1
         WHERE publication_id=1
           AND (latest_cost_basis_request_id IS NULL OR latest_cost_basis_request_id <= v_request_id);

        CALL sp_supersede_dominated_cost_requests(v_request_id);
    END IF;
END$$

DELIMITER ;

-- Re-created from V411 with the supersession compare-and-set appended after the latest-pointer advance.
-- Behaviour is otherwise byte-identical to V411's full-refresh contract.
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
    DECLARE v_full_version BIGINT UNSIGNED;
    DECLARE v_incremental_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_basis_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_finance_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_classification_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_vector CHAR(64);
    DECLARE v_request_key CHAR(64);
    DECLARE v_request_id BIGINT UNSIGNED;

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
    CALL sp_refresh_practice_opex_mat();

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

    -- B4 repair: a full refresh does not reset the incremental version, so the enqueued FULL_BI request
    -- must capture the LIVE incremental_refresh_version (not a hardcoded 0). Otherwise, once any incremental
    -- ever ran, the request's expected_incremental_refresh_version=0 can never equal the live monotonic
    -- version and the FULL_BI row is permanently unclaimable (the stuck-PENDING production symptom).
    SELECT full_refresh_version, incremental_refresh_version
      INTO v_full_version, v_incremental_version
      FROM bi_refresh_watermark WHERE pipeline_name = 'FACT_USER_DAY';
    SELECT source_version INTO v_basis_version FROM practice_revenue_source_watermark
     WHERE source_name = 'PRACTICE_BASIS_INPUT';
    SELECT source_version INTO v_finance_version FROM practice_revenue_source_watermark
     WHERE source_name = 'FINANCE_GL';
    SELECT source_version INTO v_classification_version FROM practice_revenue_source_watermark
     WHERE source_name = 'ACCOUNT_CLASSIFICATION';

    SET v_vector = SHA2(CONCAT_WS('|', v_full_version, v_incremental_version, v_basis_version,
                                  v_finance_version, v_classification_version,
                                  DATE_FORMAT(v_start, '%Y-%m-%d'), DATE_FORMAT(v_end, '%Y-%m-%d')), 256);
    SET v_request_key = SHA2(CONCAT('FULL_BI|', v_full_version, '|', v_vector), 256);

    INSERT INTO practice_cost_basis_refresh_request (
        request_key, cause, trigger_origin, cause_input_version,
        expected_full_refresh_version, expected_incremental_refresh_version,
        expected_practice_basis_input_version, expected_finance_gl_version,
        expected_account_classification_version, input_vector_fingerprint,
        affected_start_date, affected_end_date
    ) VALUES (
        v_request_key, 'FULL_BI', 'PROCEDURE', v_full_version,
        v_full_version, v_incremental_version, v_basis_version, v_finance_version,
        v_classification_version, v_vector, v_start, v_end
    ) ON DUPLICATE KEY UPDATE request_id = LAST_INSERT_ID(request_id);
    SET v_request_id = LAST_INSERT_ID();

    UPDATE practice_operating_cost_publication
       SET latest_cost_basis_request_id = v_request_id,
           latest_cost_basis_request_vector = v_vector,
           publication_version = publication_version + 1
     WHERE publication_id = 1;

    CALL sp_supersede_dominated_cost_requests(v_request_id);

    COMMIT;
    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;

-- Re-created from V411 with the supersession compare-and-set appended after the latest-pointer advance.
DROP PROCEDURE IF EXISTS sp_incremental_bi_refresh;

DELIMITER $$

CREATE PROCEDURE sp_incremental_bi_refresh()
proc_body: BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE v_previous_refresh_state VARCHAR(16) DEFAULT 'UNINITIALIZED';
    DECLARE v_refresh_token CHAR(36);
    DECLARE v_global_start DATE;
    DECLARE v_global_end DATE;
    DECLARE v_full_version BIGINT UNSIGNED;
    DECLARE v_incremental_version BIGINT UNSIGNED;
    DECLARE v_basis_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_finance_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_classification_version BIGINT UNSIGNED DEFAULT 0;
    DECLARE v_vector CHAR(64);
    DECLARE v_request_key CHAR(64);
    DECLARE v_request_id BIGINT UNSIGNED;

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

    SELECT MIN(month_start), MAX(month_end) INTO v_global_start, v_global_end
      FROM tmp_affected_ranges;

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
    CALL sp_refresh_practice_opex_mat();

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

    SELECT full_refresh_version, incremental_refresh_version
      INTO v_full_version, v_incremental_version
      FROM bi_refresh_watermark WHERE pipeline_name = 'FACT_USER_DAY';
    SELECT source_version INTO v_basis_version FROM practice_revenue_source_watermark
     WHERE source_name = 'PRACTICE_BASIS_INPUT';
    SELECT source_version INTO v_finance_version FROM practice_revenue_source_watermark
     WHERE source_name = 'FINANCE_GL';
    SELECT source_version INTO v_classification_version FROM practice_revenue_source_watermark
     WHERE source_name = 'ACCOUNT_CLASSIFICATION';

    SET v_vector = SHA2(CONCAT_WS('|', v_full_version, v_incremental_version,
                                  v_basis_version, v_finance_version, v_classification_version,
                                  DATE_FORMAT(v_global_start, '%Y-%m-%d'),
                                  DATE_FORMAT(v_global_end, '%Y-%m-%d')), 256);
    SET v_request_key = SHA2(CONCAT('INCREMENTAL_BI|', v_incremental_version, '|', v_vector), 256);

    INSERT INTO practice_cost_basis_refresh_request (
        request_key, cause, trigger_origin, cause_input_version,
        expected_full_refresh_version, expected_incremental_refresh_version,
        expected_practice_basis_input_version, expected_finance_gl_version,
        expected_account_classification_version, input_vector_fingerprint,
        affected_start_date, affected_end_date
    ) VALUES (
        v_request_key, 'INCREMENTAL_BI', 'PROCEDURE', v_incremental_version,
        v_full_version, v_incremental_version, v_basis_version,
        v_finance_version, v_classification_version, v_vector,
        v_global_start, v_global_end
    ) ON DUPLICATE KEY UPDATE request_id = LAST_INSERT_ID(request_id);
    SET v_request_id = LAST_INSERT_ID();

    UPDATE practice_operating_cost_publication
       SET latest_cost_basis_request_id = v_request_id,
           latest_cost_basis_request_vector = v_vector,
           publication_version = publication_version + 1
     WHERE publication_id = 1;

    CALL sp_supersede_dominated_cost_requests(v_request_id);

    COMMIT;
    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;

-- Defect 9 forward data repair: any PENDING request that is not the newest is dominated by a newer
-- covering input and must retire to SUPERSEDED pointing at the newest request. This is a no-op when the
-- invariant already holds (no PENDING row exists below the newest request), and it is idempotent on a
-- re-run because a second pass again finds no dominated PENDING row. It reproduces exactly the runtime
-- supersession rule so a schema repaired here is indistinguishable from one built under the new producers.
UPDATE practice_cost_basis_refresh_request r
JOIN (SELECT MAX(request_id) AS newest FROM practice_cost_basis_refresh_request) m
   SET r.status = 'SUPERSEDED',
       r.superseded_by_request_id = m.newest,
       r.owner_token = NULL,
       r.completed_at = UTC_TIMESTAMP(6),
       r.safe_reason = 'SUPERSEDED_BY_NEWER_INPUT',
       r.optimistic_version = r.optimistic_version + 1
 WHERE r.status = 'PENDING' AND r.request_id < m.newest;
