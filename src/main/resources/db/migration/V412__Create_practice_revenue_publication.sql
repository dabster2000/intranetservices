-- V412: Versioned Practices net-attributed-revenue publication.
--
-- All monetary controls and allocation evidence are internal. No view is
-- created over consultant identifiers. Existing invoice rows receive only
-- explicit NONE/no-proof provenance; no historical inference is performed.

ALTER TABLE invoice_item_attributions
    ADD COLUMN IF NOT EXISTS source_item_uuid VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS source_attribution_uuid VARCHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS copy_provenance VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS source_distribution_fingerprint CHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS attribution_algorithm_version VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS attribution_source_kind VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS attribution_dependency_fingerprint CHAR(64) NULL,
    ADD INDEX IF NOT EXISTS idx_iia_source_item (source_item_uuid),
    ADD INDEX IF NOT EXISTS idx_iia_source_attribution (source_attribution_uuid),
    ADD INDEX IF NOT EXISTS idx_iia_dependency_fingerprint (attribution_dependency_fingerprint);

ALTER TABLE invoiceitems
    ADD COLUMN IF NOT EXISTS pricing_policy_version VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS pricing_step_id VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS pricing_step_sequence INT NULL,
    ADD COLUMN IF NOT EXISTS pricing_rule_type VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS pricing_input_fingerprint CHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS pricing_output_fingerprint CHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS pricing_output_amount DECIMAL(48,12) NULL,
    ADD COLUMN IF NOT EXISTS calculation_algorithm_version VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS credit_copy_kind VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS credit_copy_scope VARCHAR(24) NULL,
    ADD COLUMN IF NOT EXISTS credit_copy_scale DECIMAL(38,18) NULL,
    ADD COLUMN IF NOT EXISTS credit_copy_original_source_native_amount DECIMAL(48,12) NULL,
    ADD COLUMN IF NOT EXISTS credit_copy_fingerprint CHAR(64) NULL,
    ADD INDEX IF NOT EXISTS idx_invoiceitems_pricing_proof
        (pricing_policy_version, pricing_step_id, pricing_input_fingerprint),
    ADD INDEX IF NOT EXISTS idx_invoiceitems_credit_copy
        (credit_copy_kind, source_item_uuid),
    ADD CONSTRAINT chk_invoiceitems_credit_copy_kind CHECK (
        credit_copy_kind IN ('NONE', 'BYTE_IDENTICAL', 'SCALED', 'RESIDUAL')
    ),
    ADD CONSTRAINT chk_invoiceitems_credit_copy_scope CHECK (
        credit_copy_scope IS NULL OR credit_copy_scope IN ('SOURCE_ITEM', 'SOURCE_INVOICE')
    ),
    ADD CONSTRAINT chk_invoiceitems_credit_copy_evidence CHECK (
        (credit_copy_kind = 'NONE'
         AND credit_copy_scope IS NULL
         AND credit_copy_scale IS NULL
         AND credit_copy_original_source_native_amount IS NULL
         AND credit_copy_fingerprint IS NULL)
        OR
        (credit_copy_kind = 'BYTE_IDENTICAL'
         AND credit_copy_scope = 'SOURCE_ITEM'
         AND source_item_uuid IS NOT NULL
         AND credit_copy_scale = 1.000000000000000000
         AND credit_copy_original_source_native_amount IS NOT NULL
         AND credit_copy_fingerprint IS NOT NULL)
        OR
        (credit_copy_kind = 'SCALED'
         AND credit_copy_scope = 'SOURCE_ITEM'
         AND source_item_uuid IS NOT NULL
         AND credit_copy_scale IS NOT NULL
         AND credit_copy_original_source_native_amount IS NOT NULL
         AND credit_copy_fingerprint IS NOT NULL)
        OR
        (credit_copy_kind = 'RESIDUAL'
         AND credit_copy_scope = 'SOURCE_ITEM'
         AND source_item_uuid IS NOT NULL
         AND credit_copy_original_source_native_amount IS NOT NULL
         AND credit_copy_fingerprint IS NOT NULL)
        OR
        (credit_copy_kind = 'RESIDUAL'
         AND credit_copy_scope = 'SOURCE_INVOICE'
         AND source_item_uuid IS NULL
         AND credit_copy_scale IS NULL
         AND credit_copy_fingerprint IS NOT NULL)
    );

UPDATE invoiceitems
   SET credit_copy_kind = 'NONE',
       credit_copy_scope = NULL,
       credit_copy_scale = NULL,
       credit_copy_original_source_native_amount = NULL,
       credit_copy_fingerprint = NULL
 WHERE credit_copy_kind IS NULL OR credit_copy_kind = 'NONE';

CREATE TABLE practice_invoice_item_delivery_source (
    invoice_item_uuid VARCHAR(40) NOT NULL,
    work_uuid VARCHAR(36) NOT NULL,
    registrant_uuid VARCHAR(36) NOT NULL,
    effective_consultant_uuid VARCHAR(36) NOT NULL,
    delivery_date DATE NOT NULL,
    task_uuid VARCHAR(36) NOT NULL,
    project_uuid VARCHAR(36) NOT NULL,
    contract_uuid VARCHAR(36) NULL,
    contract_project_uuid VARCHAR(36) NULL,
    contract_consultant_uuid VARCHAR(36) NULL,
    normalized_duration DECIMAL(24,6) NOT NULL,
    normalized_rate DECIMAL(24,6) NULL,
    delivery_value DECIMAL(48,12) NULL,
    rate_resolution_status ENUM('RESOLVED', 'MISSING', 'AMBIGUOUS', 'INVALID') NOT NULL,
    contribution_algorithm_version VARCHAR(40) NOT NULL,
    item_fingerprint CHAR(64) NOT NULL,
    distribution_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (invoice_item_uuid, work_uuid),
    UNIQUE KEY uq_piids_item_work (invoice_item_uuid, work_uuid),
    KEY idx_piids_work (work_uuid),
    KEY idx_piids_consultant_date (effective_consultant_uuid, delivery_date),
    KEY idx_piids_contract_routing
        (contract_uuid, contract_project_uuid, contract_consultant_uuid),
    CONSTRAINT fk_piids_invoiceitem FOREIGN KEY (invoice_item_uuid)
        REFERENCES invoiceitems (uuid) ON DELETE CASCADE,
    CONSTRAINT chk_piids_rate_evidence CHECK (
        (rate_resolution_status = 'RESOLVED' AND normalized_rate IS NOT NULL
         AND normalized_rate >= 0 AND delivery_value IS NOT NULL)
        OR
        (rate_resolution_status <> 'RESOLVED' AND delivery_value IS NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE fact_practice_net_revenue_item_mat (
    generation_id CHAR(36) NOT NULL,
    item_control_key VARCHAR(160) NOT NULL,
    row_kind ENUM('SOURCE_ITEM', 'DOCUMENT_RESIDUAL', 'DOCUMENT_EVIDENCE') NOT NULL,
    source_document_uuid VARCHAR(36) NOT NULL,
    source_item_uuid VARCHAR(40) NULL,
    company_uuid VARCHAR(36) NOT NULL,
    source_document_type VARCHAR(32) NOT NULL,
    source_document_status VARCHAR(32) NOT NULL,
    recognized_month DATE NOT NULL,
    attribution_period_start DATE NULL,
    attribution_period_end DATE NULL,
    source_credit_document_uuid VARCHAR(36) NULL,
    source_credit_item_uuid VARCHAR(40) NULL,
    source_credit_attribution_uuid VARCHAR(36) NULL,
    item_category ENUM('DELIVERY_BASE', 'COMMERCIAL_ADJUSTMENT') NULL,
    adjustment_subtype VARCHAR(40) NULL,
    native_currency VARCHAR(8) NULL,
    native_item_amount DECIMAL(48,12) NULL,
    document_sign SMALLINT NULL,
    signed_native_control DECIMAL(48,12) NULL,
    item_control_dkk DECIMAL(48,12) NULL,
    document_control_dkk DECIMAL(48,2) NULL,
    document_gl_revenue_dkk DECIMAL(48,4) NULL,
    item_cent_adjustment_dkk DECIMAL(48,2) NULL,
    effective_document_ratio DECIMAL(38,18) NULL,
    document_ratio_closure_row BOOLEAN NOT NULL DEFAULT FALSE,
    document_ratio_normalization_applied BOOLEAN NOT NULL DEFAULT FALSE,
    unrounded_item_dkk DECIMAL(65,20) NULL,
    provisional_document_control_dkk DECIMAL(48,2) NULL,
    dkk_per_native_unit DECIMAL(17,8) NULL,
    cent_floor_dkk DECIMAL(48,2) NULL,
    fractional_cent_residue DECIMAL(38,20) NULL,
    one_cent_awarded BOOLEAN NOT NULL DEFAULT FALSE,
    fx_normalization_changed BOOLEAN NOT NULL DEFAULT FALSE,
    matched_voucher_key VARCHAR(160) NULL,
    matched_gl_raw_dkk DECIMAL(48,4) NULL,
    matched_gl_candidate_cent_dkk DECIMAL(48,2) NULL,
    matched_accounting_identifier VARCHAR(128) NULL,
    matched_accounting_namespace VARCHAR(40) NULL,
    control_source ENUM('ECONOMIC_GL', 'NATIVE_DKK', 'MONTHLY_FX', 'NONE') NOT NULL,
    valuation_status VARCHAR(64) NOT NULL,
    residual_control_reason VARCHAR(64) NULL,
    attribution_source_status VARCHAR(64) NOT NULL,
    evidence_resolved_segment VARCHAR(16) NULL,
    evidence_practice_basis VARCHAR(40) NULL,
    evidence_consultant_type_basis VARCHAR(40) NULL,
    scope_resolution_status ENUM('RESOLVED', 'UNRESOLVED', 'AMBIGUOUS') NULL,
    scope_resolution_reason VARCHAR(64) NULL,
    copy_provenance VARCHAR(40) NULL,
    source_distribution_fingerprint CHAR(64) NULL,
    dependency_fingerprint CHAR(64) NULL,
    pricing_policy_version VARCHAR(40) NULL,
    pricing_step_id VARCHAR(64) NULL,
    pricing_step_sequence INT NULL,
    pricing_rule_type VARCHAR(40) NULL,
    pricing_input_fingerprint CHAR(64) NULL,
    pricing_output_fingerprint CHAR(64) NULL,
    pricing_output_amount DECIMAL(48,12) NULL,
    calculation_algorithm_version VARCHAR(40) NULL,
    credit_copy_kind VARCHAR(24) NOT NULL DEFAULT 'NONE',
    credit_copy_scope VARCHAR(24) NULL,
    credit_copy_scale DECIMAL(38,18) NULL,
    credit_copy_original_source_native_amount DECIMAL(48,12) NULL,
    credit_copy_fingerprint CHAR(64) NULL,
    duplicate_risk_status VARCHAR(64) NOT NULL DEFAULT 'NONE',
    synthetic_residual BOOLEAN NOT NULL DEFAULT FALSE,
    source_fingerprint CHAR(64) NOT NULL,
    validation_reason_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    refreshed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, item_control_key),
    KEY idx_fpnri_generation_month (generation_id, recognized_month),
    KEY idx_fpnri_generation_scope
        (generation_id, evidence_resolved_segment, scope_resolution_status),
    KEY idx_fpnri_source_document (generation_id, source_document_uuid, source_item_uuid),
    KEY idx_fpnri_source_credit
        (generation_id, source_credit_document_uuid, source_credit_item_uuid),
    KEY idx_fpnri_valuation (generation_id, valuation_status, control_source),
    CONSTRAINT chk_fpnri_adjustment_subtype CHECK (
        adjustment_subtype IS NULL OR adjustment_subtype IN (
            'DISCOUNT', 'FEE_OR_UPLIFT', 'LEGACY_REBATE',
            'LEGACY_FIXED_PRICE_OR_FEE', 'ZERO_ADJUSTMENT'
        )
    ),
    CONSTRAINT chk_fpnri_document_sign CHECK (document_sign IS NULL OR document_sign IN (-1, 1)),
    CONSTRAINT chk_fpnri_residual_reason CHECK (
        residual_control_reason IS NULL OR residual_control_reason IN (
            'NEAR_ZERO_SIGNED_NATIVE_DENOMINATOR',
            'HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE'
        )
    ),
    CONSTRAINT chk_fpnri_row_semantics CHECK (
        (row_kind = 'SOURCE_ITEM' AND source_item_uuid IS NOT NULL
         AND synthetic_residual = FALSE AND residual_control_reason IS NULL)
        OR
        (row_kind = 'DOCUMENT_RESIDUAL' AND source_item_uuid IS NULL
         AND synthetic_residual = TRUE AND residual_control_reason IS NOT NULL
         AND item_control_dkk IS NOT NULL AND item_control_dkk <> 0
         AND control_source = 'ECONOMIC_GL' AND valuation_status = 'CONFIRMED_GL')
        OR
        (row_kind = 'DOCUMENT_EVIDENCE' AND source_item_uuid IS NULL
         AND native_item_amount IS NULL AND item_control_dkk IS NULL
         AND control_source = 'NONE' AND synthetic_residual = FALSE)
    ),
    CONSTRAINT chk_fpnri_credit_copy_kind CHECK (
        credit_copy_kind IN ('NONE', 'BYTE_IDENTICAL', 'SCALED', 'RESIDUAL')
    ),
    CONSTRAINT chk_fpnri_credit_copy_scope CHECK (
        credit_copy_scope IS NULL OR credit_copy_scope IN ('SOURCE_ITEM', 'SOURCE_INVOICE')
    )
) ENGINE=InnoDB;

CREATE TABLE fact_practice_net_revenue_allocation_mat (
    generation_id CHAR(36) NOT NULL,
    item_control_key VARCHAR(160) NOT NULL,
    allocation_sequence INT UNSIGNED NOT NULL,
    consultant_uuid VARCHAR(36) NULL,
    segment_id VARCHAR(16) NOT NULL,
    effective_practice_code VARCHAR(50) NULL,
    effective_practice_basis VARCHAR(40) NULL,
    practice_resolution_method ENUM(
        'DATED_DELIVERY', 'SCHEDULED_CAPACITY', 'MONTH_END_PRACTICE'
    ) NULL,
    inherited_credit_resolution BOOLEAN NOT NULL DEFAULT FALSE,
    source_allocation_reference VARCHAR(220) NULL,
    source_dependency_reference VARCHAR(220) NULL,
    attribution_source VARCHAR(40) NOT NULL,
    attribution_status ENUM('CONFIRMED', 'ESTIMATED', 'UNASSIGNED') NOT NULL,
    share_before_rounding DECIMAL(38,18) NOT NULL,
    raw_fraction DECIMAL(38,18) NOT NULL,
    effective_normalized_fraction DECIMAL(38,18) NOT NULL,
    raw_share_sum DECIMAL(38,18) NOT NULL,
    fraction_closure_row BOOLEAN NOT NULL DEFAULT FALSE,
    fraction_normalization_applied BOOLEAN NOT NULL DEFAULT FALSE,
    contributing_source_ids TEXT NOT NULL,
    unrounded_allocation_dkk DECIMAL(65,20) NOT NULL,
    floor_allocation_dkk DECIMAL(48,2) NOT NULL,
    fractional_cent_residue DECIMAL(38,20) NOT NULL,
    one_cent_awarded BOOLEAN NOT NULL DEFAULT FALSE,
    allocation_dkk DECIMAL(48,2) NOT NULL,
    delivery_start_date DATE NULL,
    delivery_end_date DATE NULL,
    historical_practice_fallback BOOLEAN NOT NULL DEFAULT FALSE,
    residual_reason VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    refreshed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (generation_id, item_control_key, allocation_sequence),
    KEY idx_fpnra_generation_segment (generation_id, segment_id),
    KEY idx_fpnra_generation_status (generation_id, attribution_status),
    KEY idx_fpnra_consultant_date (generation_id, consultant_uuid, delivery_start_date),
    CONSTRAINT fk_fpnra_item FOREIGN KEY (generation_id, item_control_key)
        REFERENCES fact_practice_net_revenue_item_mat
        (generation_id, item_control_key) ON DELETE CASCADE,
    CONSTRAINT chk_fpnra_segment CHECK (
        segment_id IN ('PM', 'BA', 'CYB', 'DEV', 'SA', 'JK', 'UD', 'EXTERNAL', 'OTHER', 'UNASSIGNED')
    ),
    CONSTRAINT chk_fpnra_fraction_range CHECK (
        effective_normalized_fraction >= 0 AND effective_normalized_fraction <= 1
    ),
    CONSTRAINT chk_fpnra_delivery_interval CHECK (
        delivery_start_date IS NULL OR delivery_end_date IS NULL
        OR delivery_start_date <= delivery_end_date
    ),
    CONSTRAINT chk_fpnra_unassigned CHECK (
        (attribution_status = 'UNASSIGNED' AND segment_id = 'UNASSIGNED')
        OR attribution_status <> 'UNASSIGNED'
    ),
    CONSTRAINT chk_fpnra_inherited_credit CHECK (
        (inherited_credit_resolution = FALSE
         AND source_allocation_reference IS NULL
         AND source_dependency_reference IS NULL)
        OR
        (inherited_credit_resolution = TRUE
         AND source_allocation_reference IS NOT NULL
         AND source_dependency_reference IS NOT NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE fact_practice_revenue_dependency_mat (
    generation_id CHAR(36) NOT NULL,
    dependent_item_control_key VARCHAR(160) NOT NULL,
    dependency_kind VARCHAR(40) NOT NULL,
    dependency_key VARCHAR(160) NOT NULL,
    dependency_sequence INT UNSIGNED NOT NULL,
    dependent_recognized_month DATE NOT NULL,
    dependency_source_category VARCHAR(40) NOT NULL,
    source_document_uuid VARCHAR(36) NULL,
    source_item_uuid VARCHAR(40) NULL,
    source_attribution_uuid VARCHAR(36) NULL,
    source_work_uuid VARCHAR(36) NULL,
    source_user_uuid VARCHAR(36) NULL,
    source_task_uuid VARCHAR(36) NULL,
    source_project_uuid VARCHAR(36) NULL,
    source_contract_project_uuid VARCHAR(36) NULL,
    source_contract_uuid VARCHAR(36) NULL,
    source_contract_consultant_uuid VARCHAR(36) NULL,
    source_self_billed_uuid VARCHAR(36) NULL,
    source_phantom_uuid VARCHAR(36) NULL,
    source_practice_basis_generation_id CHAR(36) NULL,
    source_capacity_user_uuid VARCHAR(36) NULL,
    source_capacity_start_date DATE NULL,
    source_capacity_end_date DATE NULL,
    delivery_start_date DATE NULL,
    delivery_end_date DATE NULL,
    booked_voucher_key VARCHAR(160) NULL,
    dependency_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (
        generation_id, dependent_item_control_key, dependency_kind,
        dependency_key, dependency_sequence
    ),
    KEY idx_fprdm_document (source_document_uuid, source_item_uuid, dependent_recognized_month),
    KEY idx_fprdm_attribution (source_attribution_uuid, dependent_recognized_month),
    KEY idx_fprdm_work (source_work_uuid, dependent_recognized_month),
    KEY idx_fprdm_user_dates
        (source_user_uuid, delivery_start_date, delivery_end_date, dependent_recognized_month),
    KEY idx_fprdm_task (source_task_uuid, dependent_recognized_month),
    KEY idx_fprdm_project (source_project_uuid, dependent_recognized_month),
    KEY idx_fprdm_contract_project (source_contract_project_uuid, dependent_recognized_month),
    KEY idx_fprdm_contract (source_contract_uuid, dependent_recognized_month),
    KEY idx_fprdm_contract_consultant
        (source_contract_consultant_uuid, dependent_recognized_month),
    KEY idx_fprdm_self_billed (source_self_billed_uuid, dependent_recognized_month),
    KEY idx_fprdm_phantom (source_phantom_uuid, dependent_recognized_month),
    KEY idx_fprdm_voucher (booked_voucher_key, dependent_recognized_month),
    KEY idx_fprdm_basis
        (source_practice_basis_generation_id, source_capacity_user_uuid,
         source_capacity_start_date, source_capacity_end_date),
    CONSTRAINT fk_fprdm_item FOREIGN KEY (generation_id, dependent_item_control_key)
        REFERENCES fact_practice_net_revenue_item_mat
        (generation_id, item_control_key) ON DELETE CASCADE,
    CONSTRAINT fk_fprdm_basis_generation FOREIGN KEY (source_practice_basis_generation_id)
        REFERENCES practice_basis_generation (generation_id),
    CONSTRAINT chk_fprdm_source_category CHECK (
        dependency_source_category IN (
            'INVOICE_DOCUMENT', 'FINANCE_GL', 'CURRENCY', 'ACCOUNT_CLASSIFICATION',
            'INVOICE_ATTRIBUTION', 'SELF_BILLED', 'PHANTOM_ATTRIBUTION',
            'DELIVERY_EVIDENCE', 'PRACTICE_BASIS_INPUT'
        )
    ),
    CONSTRAINT chk_fprdm_delivery_interval CHECK (
        delivery_start_date IS NULL OR delivery_end_date IS NULL
        OR delivery_start_date <= delivery_end_date
    )
) ENGINE=InnoDB;

CREATE TABLE practice_revenue_publication (
    publication_key VARCHAR(64) NOT NULL,
    published_generation_id CHAR(36) NULL,
    previous_generation_id CHAR(36) NULL,
    attempt_generation_id CHAR(36) NULL,
    paired_cost_generation_at DATETIME(6) NULL COMMENT 'UTC',
    practice_basis_generation_id CHAR(36) NULL,
    full_bi_refresh_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    invoice_document_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    finance_gl_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    currency_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    account_classification_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    invoice_attribution_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    self_billed_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    phantom_attribution_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    delivery_evidence_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    practice_basis_input_source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    booked_available BOOLEAN NOT NULL DEFAULT FALSE,
    booked_reason VARCHAR(64) NULL,
    booked_anchor_month DATE NULL,
    booked_current_start_month DATE NULL,
    booked_current_end_month DATE NULL,
    booked_prior_start_month DATE NULL,
    booked_prior_end_month DATE NULL,
    booked_plus_draft_available BOOLEAN NOT NULL DEFAULT FALSE,
    booked_plus_draft_reason VARCHAR(64) NULL,
    booked_plus_draft_anchor_month DATE NULL,
    booked_plus_draft_current_start_month DATE NULL,
    booked_plus_draft_current_end_month DATE NULL,
    booked_plus_draft_prior_start_month DATE NULL,
    booked_plus_draft_prior_end_month DATE NULL,
    status ENUM('UNINITIALIZED', 'RUNNING', 'READY', 'FAILED')
        NOT NULL DEFAULT 'UNINITIALIZED',
    shared_control_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    owner_token CHAR(36) NULL,
    lock_acquired_at DATETIME(6) NULL COMMENT 'UTC',
    source_snapshot_at DATETIME(6) NULL COMMENT 'UTC',
    source_snapshot_fact_change_log_high_water BIGINT UNSIGNED NOT NULL DEFAULT 0,
    coverage_start_month DATE NULL,
    coverage_end_month DATE NULL,
    source_document_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    source_item_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    valued_item_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    allocation_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    missing_control_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    provisional_control_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    confirmed_attribution_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    estimated_attribution_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    unassigned_allocation_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    residual_control_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    duplicate_risk_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    item_control_total_dkk DECIMAL(48,2) NULL,
    allocation_total_dkk DECIMAL(48,2) NULL,
    gl_control_total_dkk DECIMAL(48,2) NULL,
    reconciliation_gap_dkk DECIMAL(48,2) NULL,
    started_at DATETIME(6) NULL COMMENT 'UTC',
    published_at DATETIME(6) NULL COMMENT 'UTC',
    failed_at DATETIME(6) NULL COMMENT 'UTC',
    refreshed_at DATETIME(6) NULL COMMENT 'UTC',
    failure_code VARCHAR(64) NULL,
    publication_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (publication_key),
    KEY idx_prp_status_published (status, published_at),
    KEY idx_prp_generation (published_generation_id, previous_generation_id, attempt_generation_id),
    KEY idx_prp_basis (practice_basis_generation_id),
    CONSTRAINT fk_prp_basis_generation FOREIGN KEY (practice_basis_generation_id)
        REFERENCES practice_basis_generation (generation_id),
    CONSTRAINT chk_prp_singleton CHECK (publication_key = 'PRACTICE_CONTRIBUTION'),
    CONSTRAINT chk_prp_owner CHECK (
        (status = 'RUNNING' AND owner_token IS NOT NULL
         AND attempt_generation_id IS NOT NULL AND lock_acquired_at IS NOT NULL)
        OR status <> 'RUNNING'
    ),
    CONSTRAINT chk_prp_coverage CHECK (
        coverage_start_month IS NULL OR coverage_end_month IS NULL
        OR coverage_start_month <= coverage_end_month
    )
) ENGINE=InnoDB;

INSERT INTO practice_revenue_publication (publication_key, status)
VALUES ('PRACTICE_CONTRIBUTION', 'UNINITIALIZED')
ON DUPLICATE KEY UPDATE publication_key = VALUES(publication_key);

INSERT INTO practice_revenue_source_watermark (
    source_name, source_version, source_state, completed_at, last_observed_at,
    last_fact_change_log_id, last_pruned_fact_change_log_id
) VALUES
    ('INVOICE_DOCUMENT', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0),
    ('CURRENCY', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0),
    ('INVOICE_ATTRIBUTION', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0),
    ('SELF_BILLED', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0),
    ('PHANTOM_ATTRIBUTION', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0),
    ('DELIVERY_EVIDENCE', 0, 'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
     COALESCE((SELECT MAX(id) FROM fact_change_log), 0), 0)
ON DUPLICATE KEY UPDATE source_name = VALUES(source_name);

-- Replace the bootstrap marker with coverage-aware invalidation. Observation
-- always advances; versioning advances only for global/in-coverage/running
-- impact. The procedure is transaction-local, so caller rollback also rolls
-- back the watermark advance.
DROP PROCEDURE IF EXISTS sp_mark_practice_revenue_source_changed;

DELIMITER $$

CREATE PROCEDURE sp_mark_practice_revenue_source_changed(
    IN p_source_name VARCHAR(40),
    IN p_affected_month DATE
)
BEGIN
    DECLARE v_publication_status VARCHAR(20) DEFAULT 'UNINITIALIZED';
    DECLARE v_coverage_start DATE DEFAULT NULL;
    DECLARE v_coverage_end DATE DEFAULT NULL;
    DECLARE v_should_advance BOOLEAN DEFAULT FALSE;

    SELECT status, coverage_start_month, coverage_end_month
      INTO v_publication_status, v_coverage_start, v_coverage_end
      FROM practice_revenue_publication
     WHERE publication_key = 'PRACTICE_CONTRIBUTION';

    SET v_should_advance = p_affected_month IS NULL
        OR v_publication_status IN ('UNINITIALIZED', 'RUNNING')
        OR v_coverage_start IS NULL OR v_coverage_end IS NULL
        OR DATE_FORMAT(p_affected_month, '%Y-%m-01') BETWEEN v_coverage_start AND v_coverage_end;

    UPDATE practice_revenue_source_watermark
       SET source_version = source_version + IF(v_should_advance, 1, 0),
           changed_at = IF(v_should_advance, UTC_TIMESTAMP(6), changed_at),
           last_observed_at = UTC_TIMESTAMP(6),
           affected_start_month = CASE
               WHEN NOT v_should_advance THEN affected_start_month
               WHEN p_affected_month IS NULL THEN NULL
               WHEN affected_start_month IS NULL THEN DATE_FORMAT(p_affected_month, '%Y-%m-01')
               ELSE LEAST(affected_start_month, DATE_FORMAT(p_affected_month, '%Y-%m-01'))
           END,
           affected_end_month = CASE
               WHEN NOT v_should_advance THEN affected_end_month
               WHEN p_affected_month IS NULL THEN NULL
               WHEN affected_end_month IS NULL THEN DATE_FORMAT(p_affected_month, '%Y-%m-01')
               ELSE GREATEST(affected_end_month, DATE_FORMAT(p_affected_month, '%Y-%m-01'))
           END,
           safe_reason = IF(v_should_advance, 'SOURCE_CHANGED', safe_reason),
           optimistic_version = optimistic_version + IF(v_should_advance, 1, 0)
     WHERE source_name = p_source_name;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Unknown practice revenue source category';
    END IF;
END$$

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
                p.published_generation_id, p.previous_generation_id, p.attempt_generation_id
            )
         WHERE p.publication_key = 'PRACTICE_CONTRIBUTION'
           AND d.dependency_source_category = p_source_name
           AND d.dependency_kind = p_dependency_kind
           AND d.dependency_key = p_dependency_key;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    SELECT status INTO v_status FROM practice_revenue_publication
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
        IF done THEN
            UPDATE practice_revenue_source_watermark
               SET last_observed_at = UTC_TIMESTAMP(6)
             WHERE source_name = p_source_name;
        END IF;
    END IF;
END$$

CREATE PROCEDURE sp_mark_practice_revenue_document_and_credit_dependents_changed(
    IN p_source_document_uuid VARCHAR(36),
    IN p_source_item_uuid VARCHAR(40),
    IN p_source_month DATE
)
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE v_month DATE;
    DECLARE dep CURSOR FOR
        SELECT DISTINCT dependent_recognized_month
          FROM fact_practice_revenue_dependency_mat
         WHERE source_document_uuid = p_source_document_uuid
           AND (p_source_item_uuid IS NULL OR source_item_uuid = p_source_item_uuid);
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    CALL sp_mark_practice_revenue_source_changed('INVOICE_DOCUMENT', p_source_month);
    OPEN dep;
    dependency_loop: LOOP
        FETCH dep INTO v_month;
        IF done THEN LEAVE dependency_loop; END IF;
        CALL sp_mark_practice_revenue_source_changed('INVOICE_DOCUMENT', v_month);
    END LOOP;
    CLOSE dep;
END$$

DELIMITER ;

-- Conditional SQL invalidation for FX and the mutable registered-delivery
-- relationship chain. There is deliberately no second trigger on `work`.
DROP TRIGGER IF EXISTS trg_currences_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_currences_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_currences_practice_revenue_ad;
DROP TRIGGER IF EXISTS trg_task_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_task_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_task_practice_revenue_ad;
DROP TRIGGER IF EXISTS trg_project_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_project_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_project_practice_revenue_ad;
DROP TRIGGER IF EXISTS trg_contract_project_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_contract_project_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_contract_project_practice_revenue_ad;
DROP TRIGGER IF EXISTS trg_contracts_practice_revenue_ai;
DROP TRIGGER IF EXISTS trg_contracts_practice_revenue_au;
DROP TRIGGER IF EXISTS trg_contracts_practice_revenue_ad;

DELIMITER $$

CREATE TRIGGER trg_currences_practice_revenue_ai
AFTER INSERT ON currences FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed(
        'CURRENCY', STR_TO_DATE(CONCAT(NEW.month, '01'), '%Y%m%d'));
END$$
CREATE TRIGGER trg_currences_practice_revenue_au
AFTER UPDATE ON currences FOR EACH ROW
BEGIN
    IF NOT (OLD.currency <=> NEW.currency) OR NOT (OLD.month <=> NEW.month)
       OR NOT (OLD.conversion <=> NEW.conversion) THEN
        CALL sp_mark_practice_revenue_source_changed(
            'CURRENCY', STR_TO_DATE(CONCAT(OLD.month, '01'), '%Y%m%d'));
        CALL sp_mark_practice_revenue_source_changed(
            'CURRENCY', STR_TO_DATE(CONCAT(NEW.month, '01'), '%Y%m%d'));
    END IF;
END$$
CREATE TRIGGER trg_currences_practice_revenue_ad
AFTER DELETE ON currences FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_source_changed(
        'CURRENCY', STR_TO_DATE(CONCAT(OLD.month, '01'), '%Y%m%d'));
END$$

CREATE TRIGGER trg_task_practice_revenue_ai AFTER INSERT ON task FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'TASK', NEW.uuid); END$$
CREATE TRIGGER trg_task_practice_revenue_au AFTER UPDATE ON task FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'TASK', OLD.uuid);
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'TASK', NEW.uuid);
END$$
CREATE TRIGGER trg_task_practice_revenue_ad AFTER DELETE ON task FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'TASK', OLD.uuid); END$$

CREATE TRIGGER trg_project_practice_revenue_ai AFTER INSERT ON project FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'PROJECT', NEW.uuid); END$$
CREATE TRIGGER trg_project_practice_revenue_au AFTER UPDATE ON project FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'PROJECT', OLD.uuid);
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'PROJECT', NEW.uuid);
END$$
CREATE TRIGGER trg_project_practice_revenue_ad AFTER DELETE ON project FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'PROJECT', OLD.uuid); END$$

CREATE TRIGGER trg_contract_project_practice_revenue_ai AFTER INSERT ON contract_project FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT_PROJECT', NEW.uuid); END$$
CREATE TRIGGER trg_contract_project_practice_revenue_au AFTER UPDATE ON contract_project FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT_PROJECT', OLD.uuid);
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT_PROJECT', NEW.uuid);
END$$
CREATE TRIGGER trg_contract_project_practice_revenue_ad AFTER DELETE ON contract_project FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT_PROJECT', OLD.uuid); END$$

CREATE TRIGGER trg_contracts_practice_revenue_ai AFTER INSERT ON contracts FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT', NEW.uuid); END$$
CREATE TRIGGER trg_contracts_practice_revenue_au AFTER UPDATE ON contracts FOR EACH ROW
BEGIN
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT', OLD.uuid);
    CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT', NEW.uuid);
END$$
CREATE TRIGGER trg_contracts_practice_revenue_ad AFTER DELETE ON contracts FOR EACH ROW
BEGIN CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'CONTRACT', OLD.uuid); END$$

DELIMITER ;

-- Replace, do not duplicate, the V256 contract-consultant triggers. Their
-- 24-month fact_change_log behavior is preserved exactly, while the direct
-- dependency marker also covers long-running contracts beyond that cap.
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
    CALL sp_mark_practice_revenue_dependency_changed(
        'DELIVERY_EVIDENCE', 'CONTRACT_CONSULTANT', NEW.uuid);
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
    CALL sp_mark_practice_revenue_dependency_changed(
        'DELIVERY_EVIDENCE', 'CONTRACT_CONSULTANT', OLD.uuid);
    CALL sp_mark_practice_revenue_dependency_changed(
        'DELIVERY_EVIDENCE', 'CONTRACT_CONSULTANT', NEW.uuid);
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
    CALL sp_mark_practice_revenue_dependency_changed(
        'DELIVERY_EVIDENCE', 'CONTRACT_CONSULTANT', OLD.uuid);
END$$

DELIMITER ;
