-- V411: Align Practices cost publication to one immutable effective-dated basis.
--
-- This migration is intentionally additive.  It replaces the V410 BI
-- orchestrators so SQL produces durable cost-basis requests; Java owns the
-- immutable basis/cost candidate and its final compare-and-set publication.

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_v411_acquire_migration_lock$$
CREATE PROCEDURE sp_v411_acquire_migration_lock()
BEGIN
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE v_active_owner CHAR(36) DEFAULT NULL;

    SELECT GET_LOCK('bi_refresh', 30) INTO v_lock_acquired;
    IF COALESCE(v_lock_acquired, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V411 could not acquire the bi_refresh migration lock';
    END IF;

    SELECT active_refresh_token INTO v_active_owner
      FROM bi_refresh_watermark
     WHERE pipeline_name = 'FACT_USER_DAY';
    IF v_active_owner IS NOT NULL THEN
        DO RELEASE_LOCK('bi_refresh');
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V411 refused an active FACT_USER_DAY owner';
    END IF;
END$$

DELIMITER ;

CALL sp_v411_acquire_migration_lock();
DROP PROCEDURE sp_v411_acquire_migration_lock;

ALTER TABLE bi_refresh_watermark
    ADD COLUMN IF NOT EXISTS full_refresh_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS incremental_refresh_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dependency_manifest_input_version BIGINT UNSIGNED NOT NULL DEFAULT 0;

UPDATE bi_refresh_watermark
   SET full_refresh_version = CASE
           WHEN refresh_state = 'READY' AND last_full_refresh_at IS NOT NULL
               THEN GREATEST(full_refresh_version, 1)
           ELSE full_refresh_version
       END
 WHERE pipeline_name = 'FACT_USER_DAY';

CREATE TABLE practice_revenue_source_watermark (
    source_name VARCHAR(40) NOT NULL,
    source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    source_state ENUM('READY', 'RUNNING', 'FAILED') NOT NULL DEFAULT 'READY',
    attempt_token CHAR(36) NULL,
    started_at DATETIME(6) NULL COMMENT 'UTC',
    changed_at DATETIME(6) NULL COMMENT 'UTC',
    completed_at DATETIME(6) NULL COMMENT 'UTC',
    last_observed_at DATETIME(6) NULL COMMENT 'UTC',
    affected_start_month DATE NULL,
    affected_end_month DATE NULL,
    safe_reason VARCHAR(64) NULL,
    last_fact_change_log_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_pruned_fact_change_log_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    recovery_target_fact_change_log_id BIGINT UNSIGNED NULL,
    recovery_token CHAR(36) NULL,
    recovery_started_at DATETIME(6) NULL COMMENT 'UTC',
    retention_gap_reason VARCHAR(64) NULL,
    async_mutation_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0,
    async_completed_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0,
    async_pending_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (source_name),
    KEY idx_prsw_state_changed (source_state, changed_at),
    CONSTRAINT chk_prsw_month_interval CHECK (
        affected_start_month IS NULL OR affected_end_month IS NULL
        OR affected_start_month <= affected_end_month
    ),
    CONSTRAINT chk_prsw_attempt_state CHECK (
        (source_state = 'RUNNING' AND attempt_token IS NOT NULL AND started_at IS NOT NULL)
        OR source_state <> 'RUNNING'
    ),
    CONSTRAINT chk_prsw_async_sequence CHECK (
        async_completed_sequence <= async_mutation_sequence
        AND async_pending_count <= async_mutation_sequence
    )
) ENGINE=InnoDB;

CREATE TABLE practice_revenue_async_mutation_attempt (
    source_name VARCHAR(40) NOT NULL,
    mutation_sequence BIGINT UNSIGNED NOT NULL,
    attempt_token CHAR(36) NOT NULL,
    attempt_state ENUM('RUNNING', 'READY', 'FAILED') NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    completed_at DATETIME(6) NULL COMMENT 'UTC',
    PRIMARY KEY (source_name, mutation_sequence),
    UNIQUE KEY uq_prama_token (attempt_token),
    KEY idx_prama_source_state (source_name, attempt_state),
    CONSTRAINT fk_prama_source FOREIGN KEY (source_name)
        REFERENCES practice_revenue_source_watermark (source_name),
    CONSTRAINT chk_prama_completion CHECK (
        (attempt_state = 'RUNNING' AND completed_at IS NULL)
        OR (attempt_state <> 'RUNNING' AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB;

INSERT INTO practice_revenue_source_watermark (
    source_name, source_version, source_state, completed_at, last_observed_at
) VALUES
    ('FINANCE_GL', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('ACCOUNT_CLASSIFICATION', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('PRACTICE_BASIS_INPUT', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE source_name = VALUES(source_name);

CREATE TABLE practice_basis_generation (
    generation_id CHAR(36) NOT NULL,
    status ENUM('RUNNING', 'READY', 'FAILED') NOT NULL,
    coverage_start_date DATE NOT NULL,
    coverage_end_date DATE NOT NULL,
    history_coverage_start_date DATE NULL,
    fallback_policy_version VARCHAR(40) NOT NULL,
    consultant_type_policy_version VARCHAR(40) NOT NULL,
    full_refresh_version BIGINT UNSIGNED NOT NULL,
    incremental_refresh_version BIGINT UNSIGNED NOT NULL,
    practice_basis_input_source_version BIGINT UNSIGNED NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    capacity_source_fingerprint CHAR(64) NOT NULL,
    dependency_manifest_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    published_at DATETIME(6) NULL COMMENT 'UTC',
    failure_code VARCHAR(64) NULL,
    PRIMARY KEY (generation_id),
    KEY idx_pbg_status_created (status, created_at),
    KEY idx_pbg_source_vector (full_refresh_version, practice_basis_input_source_version),
    CONSTRAINT chk_pbg_coverage CHECK (coverage_start_date <= coverage_end_date),
    CONSTRAINT chk_pbg_publication_state CHECK (
        (status = 'READY' AND published_at IS NOT NULL AND failure_code IS NULL)
        OR status <> 'READY'
    )
) ENGINE=InnoDB;

CREATE TABLE practice_user_effective_basis_mat (
    generation_id CHAR(36) NOT NULL,
    user_uuid VARCHAR(36) NOT NULL,
    effective_from_date DATE NOT NULL,
    effective_to_date_exclusive DATE NOT NULL,
    consultant_type VARCHAR(32) NOT NULL,
    practice_code VARCHAR(50) NULL,
    attribution_basis ENUM('HISTORY', 'CURRENT_PRACTICE_FALLBACK') NOT NULL,
    fallback_reason VARCHAR(64) NULL,
    source_evidence VARCHAR(64) NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, user_uuid, effective_from_date),
    KEY idx_pueb_lookup (generation_id, user_uuid, effective_from_date, effective_to_date_exclusive),
    KEY idx_pueb_practice_date (generation_id, practice_code, effective_from_date),
    CONSTRAINT fk_pueb_generation FOREIGN KEY (generation_id)
        REFERENCES practice_basis_generation (generation_id) ON DELETE CASCADE,
    CONSTRAINT chk_pueb_interval CHECK (effective_from_date < effective_to_date_exclusive),
    CONSTRAINT chk_pueb_fallback CHECK (
        (attribution_basis = 'CURRENT_PRACTICE_FALLBACK' AND fallback_reason IS NOT NULL)
        OR attribution_basis = 'HISTORY'
    )
) ENGINE=InnoDB;

CREATE TABLE practice_user_daily_capacity_basis_mat (
    generation_id CHAR(36) NOT NULL,
    user_uuid VARCHAR(36) NOT NULL,
    capacity_date DATE NOT NULL,
    company_uuid VARCHAR(36) NOT NULL,
    gross_available_hours DECIMAL(24,6) NOT NULL,
    effective_basis_from_date DATE NOT NULL,
    consultant_type VARCHAR(32) NOT NULL,
    practice_code VARCHAR(50) NULL,
    capacity_source VARCHAR(40) NOT NULL,
    capacity_source_fingerprint CHAR(64) NOT NULL,
    historical_practice_fallback BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, user_uuid, capacity_date),
    KEY idx_pudcb_company_date (generation_id, company_uuid, capacity_date),
    KEY idx_pudcb_practice_date (generation_id, practice_code, capacity_date),
    CONSTRAINT fk_pudcb_effective_basis FOREIGN KEY
        (generation_id, user_uuid, effective_basis_from_date)
        REFERENCES practice_user_effective_basis_mat
        (generation_id, user_uuid, effective_from_date) ON DELETE CASCADE,
    CONSTRAINT chk_pudcb_hours CHECK (gross_available_hours >= 0)
) ENGINE=InnoDB;

CREATE TABLE practice_basis_dependency_manifest_mat (
    generation_id CHAR(36) NOT NULL,
    manifest_sequence INT UNSIGNED NOT NULL,
    recognized_document_uuid VARCHAR(36) NOT NULL,
    recognized_item_uuid VARCHAR(40) NULL,
    recognized_document_type VARCHAR(32) NOT NULL,
    recognized_month DATE NOT NULL,
    dependency_kind VARCHAR(40) NOT NULL,
    source_document_uuid VARCHAR(36) NULL,
    source_item_uuid VARCHAR(40) NULL,
    required_start_date DATE NOT NULL,
    required_end_date DATE NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, manifest_sequence),
    UNIQUE KEY uq_pbdm_dependency (
        generation_id, recognized_document_uuid, recognized_item_uuid,
        dependency_kind, source_document_uuid, source_item_uuid,
        required_start_date, required_end_date
    ),
    KEY idx_pbdm_source_document (generation_id, source_document_uuid, source_item_uuid),
    KEY idx_pbdm_bounds (generation_id, required_start_date, required_end_date),
    CONSTRAINT fk_pbdm_generation FOREIGN KEY (generation_id)
        REFERENCES practice_basis_generation (generation_id) ON DELETE CASCADE,
    CONSTRAINT chk_pbdm_bounds CHECK (required_start_date <= required_end_date)
) ENGINE=InnoDB;

-- The canonical cost candidate is generation-keyed.  Legacy BI facts remain mutable inputs for
-- unrelated dashboards; a Practices publication never certifies them by changing timestamps.
CREATE TABLE fact_practice_cost_generation_mat (
    generation_id CHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    practice_code VARCHAR(50) NOT NULL,
    month_key CHAR(6) NOT NULL,
    posting_status ENUM('BOOKED', 'DRAFT') NOT NULL,
    cost_type ENUM('SALARIES', 'OPEX') NOT NULL,
    source_control_key CHAR(64) NOT NULL,
    allocated_amount_dkk DECIMAL(24,2) NOT NULL,
    source_control_dkk DECIMAL(24,2) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, company_id, practice_code, month_key,
                 posting_status, cost_type, source_control_key),
    KEY idx_fpcgm_read (generation_id, month_key, practice_code, posting_status, cost_type),
    CONSTRAINT fk_fpcgm_generation FOREIGN KEY (generation_id)
        REFERENCES practice_basis_generation (generation_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE fact_practice_fte_generation_mat (
    generation_id CHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    practice_code VARCHAR(50) NOT NULL,
    month_key CHAR(6) NOT NULL,
    billable_fte DECIMAL(24,6) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, company_id, practice_code, month_key),
    KEY idx_fpfgm_read (generation_id, month_key, practice_code),
    CONSTRAINT fk_fpfgm_generation FOREIGN KEY (generation_id)
        REFERENCES practice_basis_generation (generation_id) ON DELETE CASCADE,
    CONSTRAINT chk_fpfgm_nonnegative CHECK (billable_fte >= 0)
) ENGINE=InnoDB;

CREATE TABLE fact_practice_cost_completeness_generation_mat (
    generation_id CHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    month_key CHAR(6) NOT NULL,
    cost_source ENUM('BOOKED', 'BOOKED_PLUS_DRAFT') NOT NULL,
    intended_salary_dkk DECIMAL(24,2) NOT NULL,
    signed_salary_gl_dkk DECIMAL(24,2) NOT NULL,
    allocated_salary_dkk DECIMAL(24,2) NOT NULL,
    expected_salary_cell_count INT UNSIGNED NOT NULL,
    actual_salary_cell_count INT UNSIGNED NOT NULL,
    covered_salary_cell_count INT UNSIGNED NOT NULL,
    missing_salary_cell_count INT UNSIGNED NOT NULL,
    unexpected_salary_cell_count INT UNSIGNED NOT NULL,
    allocation_gap_dkk DECIMAL(24,2) NOT NULL,
    allowed_allocation_gap_dkk DECIMAL(24,2) NOT NULL,
    complete BOOLEAN NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, company_id, month_key, cost_source),
    KEY idx_fpc_cgm_read (generation_id, cost_source, month_key),
    CONSTRAINT fk_fpc_cgm_generation FOREIGN KEY (generation_id)
        REFERENCES practice_basis_generation (generation_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE practice_cost_basis_refresh_request (
    request_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_key CHAR(64) NOT NULL,
    cause ENUM('FULL_BI', 'INCREMENTAL_BI', 'PRACTICE_BASIS_INPUT',
               'COST_GL_INPUT', 'DEPENDENCY_MANIFEST_INPUT') NOT NULL,
    trigger_origin ENUM('PROCEDURE', 'DIRTY_MARKER', 'SCHEDULER', 'OPERATOR') NOT NULL,
    cause_input_version BIGINT UNSIGNED NOT NULL,
    expected_full_refresh_version BIGINT UNSIGNED NOT NULL,
    expected_incremental_refresh_version BIGINT UNSIGNED NOT NULL,
    expected_practice_basis_input_version BIGINT UNSIGNED NOT NULL,
    expected_finance_gl_version BIGINT UNSIGNED NOT NULL,
    expected_account_classification_version BIGINT UNSIGNED NOT NULL,
    input_vector_fingerprint CHAR(64) NOT NULL,
    dependency_fingerprint CHAR(64) NULL,
    affected_start_date DATE NULL,
    affected_end_date DATE NULL,
    status ENUM('PENDING', 'RUNNING', 'READY', 'NO_CHANGE', 'FAILED', 'SUPERSEDED')
        NOT NULL DEFAULT 'PENDING',
    superseded_by_request_id BIGINT UNSIGNED NULL,
    owner_token CHAR(36) NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    resulting_cost_generation_at DATETIME(6) NULL COMMENT 'UTC',
    resulting_basis_generation_id CHAR(36) NULL,
    compared_cost_generation_at DATETIME(6) NULL COMMENT 'UTC',
    compared_basis_generation_id CHAR(36) NULL,
    content_fingerprint CHAR(64) NULL,
    safe_reason VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    claimed_at DATETIME(6) NULL COMMENT 'UTC',
    completed_at DATETIME(6) NULL COMMENT 'UTC',
    failed_at DATETIME(6) NULL COMMENT 'UTC',
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (request_id),
    UNIQUE KEY uq_pcbr_request_key (request_key),
    KEY idx_pcbr_pending_claim (status, request_id),
    KEY idx_pcbr_state_age (status, claimed_at),
    KEY idx_pcbr_successor (superseded_by_request_id),
    KEY idx_pcbr_expected_vector (
        expected_full_refresh_version, expected_incremental_refresh_version,
        expected_practice_basis_input_version
    ),
    KEY idx_pcbr_retention (status, completed_at, resulting_cost_generation_at),
    CONSTRAINT fk_pcbr_successor FOREIGN KEY (superseded_by_request_id)
        REFERENCES practice_cost_basis_refresh_request (request_id),
    CONSTRAINT fk_pcbr_result_basis FOREIGN KEY (resulting_basis_generation_id)
        REFERENCES practice_basis_generation (generation_id),
    CONSTRAINT fk_pcbr_compared_basis FOREIGN KEY (compared_basis_generation_id)
        REFERENCES practice_basis_generation (generation_id),
    CONSTRAINT chk_pcbr_affected_bounds CHECK (
        affected_start_date IS NULL OR affected_end_date IS NULL
        OR affected_start_date <= affected_end_date
    ),
    CONSTRAINT chk_pcbr_owner CHECK (
        (status = 'RUNNING' AND owner_token IS NOT NULL AND claimed_at IS NOT NULL)
        OR status <> 'RUNNING'
    ),
    CONSTRAINT chk_pcbr_superseded CHECK (
        (status = 'SUPERSEDED' AND superseded_by_request_id IS NOT NULL)
        OR status <> 'SUPERSEDED'
    )
) ENGINE=InnoDB;

-- Durable edge from a changed READY cost generation to the revenue scheduler.
-- Byte-identical NO_CHANGE requests have no resulting generation and therefore never emit.
CREATE TABLE practice_cost_generation_signal (
    cost_generation_at DATETIME(6) NOT NULL COMMENT 'UTC',
    practice_basis_generation_id CHAR(36) NOT NULL,
    cost_basis_request_id BIGINT UNSIGNED NOT NULL,
    input_vector_fingerprint CHAR(64) NOT NULL,
    cause VARCHAR(40) NOT NULL,
    emitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (cost_generation_at),
    UNIQUE KEY uq_pcgs_request (cost_basis_request_id),
    CONSTRAINT fk_pcgs_basis FOREIGN KEY (practice_basis_generation_id)
        REFERENCES practice_basis_generation (generation_id),
    CONSTRAINT fk_pcgs_request FOREIGN KEY (cost_basis_request_id)
        REFERENCES practice_cost_basis_refresh_request (request_id)
) ENGINE=InnoDB;

CREATE TABLE practice_contribution_publication_control (
    control_id TINYINT UNSIGNED NOT NULL,
    refresh_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    contribution_serving_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    legacy_cost_serving_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    control_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_transition_actor VARCHAR(36) NULL,
    last_transition_at DATETIME(6) NULL COMMENT 'UTC',
    last_transition_reason VARCHAR(64) NULL,
    revenue_recovery_execution_id VARCHAR(64) NULL,
    revenue_recovery_owner_token CHAR(36) NULL,
    revenue_recovery_started_at DATETIME(6) NULL COMMENT 'UTC',
    revenue_recovery_state ENUM('RUNNING', 'BUILT') NULL,
    recovery_expected_cost_generation_at DATETIME(6) NULL COMMENT 'UTC',
    recovery_expected_cost_request_id BIGINT UNSIGNED NULL,
    recovery_expected_cost_request_key CHAR(64) NULL,
    recovery_expected_cost_input_vector_fingerprint CHAR(64) NULL,
    recovery_expected_full_refresh_version BIGINT UNSIGNED NULL,
    recovery_expected_source_vector_fingerprint CHAR(64) NULL,
    PRIMARY KEY (control_id),
    CONSTRAINT chk_pcpc_singleton CHECK (control_id = 1),
    CONSTRAINT chk_pcpc_recovery_complete CHECK (
        (revenue_recovery_state IS NULL
         AND revenue_recovery_execution_id IS NULL
         AND revenue_recovery_owner_token IS NULL
         AND revenue_recovery_started_at IS NULL
         AND recovery_expected_cost_generation_at IS NULL
         AND recovery_expected_cost_request_id IS NULL
         AND recovery_expected_cost_request_key IS NULL
         AND recovery_expected_cost_input_vector_fingerprint IS NULL
         AND recovery_expected_full_refresh_version IS NULL
         AND recovery_expected_source_vector_fingerprint IS NULL)
        OR
        (revenue_recovery_state IN ('RUNNING', 'BUILT')
         AND revenue_recovery_execution_id IS NOT NULL
         AND revenue_recovery_owner_token IS NOT NULL
         AND revenue_recovery_started_at IS NOT NULL
         AND recovery_expected_cost_generation_at IS NOT NULL
         AND recovery_expected_cost_request_id IS NOT NULL
         AND recovery_expected_cost_request_key IS NOT NULL
         AND recovery_expected_cost_input_vector_fingerprint IS NOT NULL
         AND recovery_expected_full_refresh_version IS NOT NULL
         AND recovery_expected_source_vector_fingerprint IS NOT NULL)
    )
) ENGINE=InnoDB;

INSERT INTO practice_contribution_publication_control (
    control_id, refresh_enabled, contribution_serving_enabled,
    legacy_cost_serving_enabled, control_version, last_transition_at,
    last_transition_reason
) VALUES (1, FALSE, FALSE, TRUE, 0, UTC_TIMESTAMP(6), 'MIGRATION_BOOTSTRAP')
ON DUPLICATE KEY UPDATE control_id = VALUES(control_id);

ALTER TABLE practice_operating_cost_publication
    ADD COLUMN IF NOT EXISTS practice_basis_generation_id CHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS latest_cost_basis_request_id BIGINT UNSIGNED NULL,
    ADD COLUMN IF NOT EXISTS latest_cost_basis_request_vector CHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS certified_cost_basis_request_id BIGINT UNSIGNED NULL,
    ADD COLUMN IF NOT EXISTS certified_cost_basis_request_vector CHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS cost_content_fingerprint CHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS publication_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD INDEX IF NOT EXISTS idx_pocp_latest_request (latest_cost_basis_request_id),
    ADD INDEX IF NOT EXISTS idx_pocp_certified_request (certified_cost_basis_request_id),
    ADD CONSTRAINT fk_pocp_basis_generation FOREIGN KEY (practice_basis_generation_id)
        REFERENCES practice_basis_generation (generation_id),
    ADD CONSTRAINT fk_pocp_latest_request FOREIGN KEY (latest_cost_basis_request_id)
        REFERENCES practice_cost_basis_refresh_request (request_id),
    ADD CONSTRAINT fk_pocp_certified_request FOREIGN KEY (certified_cost_basis_request_id)
        REFERENCES practice_cost_basis_refresh_request (request_id);

-- Initial/global marker. V412 replaces this with coverage-aware filtering.
DROP PROCEDURE IF EXISTS sp_mark_practice_revenue_source_changed;

DELIMITER $$

CREATE PROCEDURE sp_mark_practice_revenue_source_changed(
    IN p_source_name VARCHAR(40),
    IN p_affected_month DATE
)
BEGIN
    UPDATE practice_revenue_source_watermark
       SET source_version = source_version + 1,
           changed_at = UTC_TIMESTAMP(6),
           last_observed_at = UTC_TIMESTAMP(6),
           affected_start_month = CASE
               WHEN p_affected_month IS NULL THEN NULL
               WHEN affected_start_month IS NULL THEN DATE_FORMAT(p_affected_month, '%Y-%m-01')
               ELSE LEAST(affected_start_month, DATE_FORMAT(p_affected_month, '%Y-%m-01'))
           END,
           affected_end_month = CASE
               WHEN p_affected_month IS NULL THEN NULL
               WHEN affected_end_month IS NULL THEN DATE_FORMAT(p_affected_month, '%Y-%m-01')
               ELSE GREATEST(affected_end_month, DATE_FORMAT(p_affected_month, '%Y-%m-01'))
           END,
           safe_reason = 'SOURCE_CHANGED',
           optimistic_version = optimistic_version + 1
     WHERE source_name = p_source_name;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Unknown practice revenue source category';
    END IF;
END$$

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
END$$

DELIMITER ;

-- Global cost-classification and effective-practice/type invalidation is in
-- the same transaction as the source write. Existing V407 user triggers are
-- intentionally preserved; these are additive triggers on the source tables.
DROP TRIGGER IF EXISTS trg_accounting_accounts_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_accounting_accounts_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_accounting_accounts_practice_revenue_ad;
DROP TRIGGER IF EXISTS trg_user_practice_history_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_user_practice_history_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_user_practice_history_practice_revenue_ad;
DROP TRIGGER IF EXISTS trg_userstatus_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_userstatus_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_userstatus_practice_revenue_ad;

DELIMITER $$

CREATE TRIGGER trg_accounting_accounts_practice_revenue_ai
AFTER INSERT ON accounting_accounts FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed('ACCOUNT_CLASSIFICATION', NULL);
    CALL sp_enqueue_practice_cost_basis_refresh('COST_GL_INPUT', 'DIRTY_MARKER', NULL, NULL);
END$$

CREATE TRIGGER trg_accounting_accounts_practice_revenue_au
AFTER UPDATE ON accounting_accounts FOR EACH ROW
BEGIN
    IF NOT (OLD.cost_type <=> NEW.cost_type)
       OR OLD.account_code <> NEW.account_code
       OR NOT (OLD.companyuuid <=> NEW.companyuuid) THEN
        CALL sp_mark_practice_revenue_source_changed('ACCOUNT_CLASSIFICATION', NULL);
        CALL sp_enqueue_practice_cost_basis_refresh('COST_GL_INPUT', 'DIRTY_MARKER', NULL, NULL);
    END IF;
END$$

CREATE TRIGGER trg_accounting_accounts_practice_revenue_ad
AFTER DELETE ON accounting_accounts FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed('ACCOUNT_CLASSIFICATION', NULL);
    CALL sp_enqueue_practice_cost_basis_refresh('COST_GL_INPUT', 'DIRTY_MARKER', NULL, NULL);
END$$

CREATE TRIGGER trg_user_practice_history_practice_revenue_ai
AFTER INSERT ON user_practice_history FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed('PRACTICE_BASIS_INPUT', NULL);
    CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT', 'DIRTY_MARKER', NULL, NULL);
END$$

CREATE TRIGGER trg_user_practice_history_practice_revenue_au
AFTER UPDATE ON user_practice_history FOR EACH ROW
BEGIN
    IF NOT (OLD.practice <=> NEW.practice)
       OR OLD.effective_from <> NEW.effective_from
       OR NOT (OLD.effective_to <=> NEW.effective_to) THEN
        CALL sp_mark_practice_revenue_source_changed('PRACTICE_BASIS_INPUT', NULL);
        CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT', 'DIRTY_MARKER', NULL, NULL);
    END IF;
END$$

CREATE TRIGGER trg_user_practice_history_practice_revenue_ad
AFTER DELETE ON user_practice_history FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed('PRACTICE_BASIS_INPUT', NULL);
    CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT', 'DIRTY_MARKER', NULL, NULL);
END$$

CREATE TRIGGER trg_userstatus_practice_revenue_ai
AFTER INSERT ON userstatus FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed('PRACTICE_BASIS_INPUT', NULL);
    CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT', 'DIRTY_MARKER', NULL, NULL);
END$$

CREATE TRIGGER trg_userstatus_practice_revenue_au
AFTER UPDATE ON userstatus FOR EACH ROW
BEGIN
    IF NOT (OLD.type <=> NEW.type)
       OR NOT (OLD.status <=> NEW.status)
       OR OLD.statusdate <> NEW.statusdate
       OR OLD.allocation <> NEW.allocation
       OR NOT (OLD.companyuuid <=> NEW.companyuuid) THEN
        CALL sp_mark_practice_revenue_source_changed('PRACTICE_BASIS_INPUT', NULL);
        CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT', 'DIRTY_MARKER', NULL, NULL);
    END IF;
END$$

CREATE TRIGGER trg_userstatus_practice_revenue_ad
AFTER DELETE ON userstatus FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed('PRACTICE_BASIS_INPUT', NULL);
    CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT', 'DIRTY_MARKER', NULL, NULL);
END$$

DELIMITER ;

-- The V410 begin/stage/finalize publication calls are deliberately absent.
-- Full BI now advances one unsigned input version and emits one idempotent
-- request in the same transaction as FACT_USER_DAY certification.
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

    SELECT full_refresh_version INTO v_full_version
      FROM bi_refresh_watermark WHERE pipeline_name = 'FACT_USER_DAY';
    SELECT source_version INTO v_basis_version FROM practice_revenue_source_watermark
     WHERE source_name = 'PRACTICE_BASIS_INPUT';
    SELECT source_version INTO v_finance_version FROM practice_revenue_source_watermark
     WHERE source_name = 'FINANCE_GL';
    SELECT source_version INTO v_classification_version FROM practice_revenue_source_watermark
     WHERE source_name = 'ACCOUNT_CLASSIFICATION';

    SET v_vector = SHA2(CONCAT_WS('|', v_full_version, 0, v_basis_version,
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
        v_full_version, 0, v_basis_version, v_finance_version,
        v_classification_version, v_vector, v_start, v_end
    ) ON DUPLICATE KEY UPDATE request_id = LAST_INSERT_ID(request_id);
    SET v_request_id = LAST_INSERT_ID();

    UPDATE practice_operating_cost_publication
       SET latest_cost_basis_request_id = v_request_id,
           latest_cost_basis_request_vector = v_vector,
           publication_version = publication_version + 1
     WHERE publication_id = 1;

    COMMIT;
    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;

-- Every non-empty incremental is conservatively potentially relevant. The
-- Java writer may certify a byte-equivalent candidate as NO_CHANGE.
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

    COMMIT;
    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;

DO RELEASE_LOCK('bi_refresh');
