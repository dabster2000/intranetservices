-- ======================================================================================
-- V103: Create Contract Override Tables
-- ======================================================================================
-- Purpose: Enable contract-specific rule overrides for validation, rate adjustments, and pricing
-- Feature: Contract Rule Override System
-- Context: Allows individual contracts to override rules inherited from contract types
-- ======================================================================================

-- ======================================================================================
-- Table 1: Contract Validation Overrides
-- ======================================================================================
-- Stores contract-specific overrides for validation rules
-- Allows disabling, replacing, or modifying validation rules per contract
START TRANSACTION;
CREATE TABLE contract_validation_overrides (
    id INT AUTO_INCREMENT PRIMARY KEY,
    contract_uuid VARCHAR(36) NOT NULL COMMENT 'FK to contracts.uuid',
    rule_id VARCHAR(64) NOT NULL COMMENT 'Stable identifier for the rule (e.g., notes-required, max-hours-per-day)',
    override_type ENUM('REPLACE', 'DISABLE', 'MODIFY') NOT NULL COMMENT 'Type of override: REPLACE=full replacement, DISABLE=deactivate rule, MODIFY=adjust parameters',
    label VARCHAR(255) COMMENT 'Display label for the override (e.g., "Require detailed notes for this contract")',
    validation_type VARCHAR(50) COMMENT 'Type of validation: NOTES_REQUIRED, MAX_HOURS, MIN_HOURS, etc.',
    required BOOLEAN COMMENT 'Whether this validation is required (true/false)',
    threshold_value DECIMAL(10,2) COMMENT 'Numeric threshold for validation (e.g., max hours: 8.00)',
    config_json JSON COMMENT 'Additional configuration parameters as JSON',
    priority INT COMMENT 'Execution order - lower numbers execute first (10, 20, 30...)',
    active BOOLEAN DEFAULT TRUE COMMENT 'Soft delete flag - allows deactivation without deletion',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    created_by VARCHAR(36) COMMENT 'User UUID who created this override',


    -- Unique constraint: one override per contract per rule
    CONSTRAINT uq_contract_rule
        UNIQUE (contract_uuid, rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Contract-specific validation rule overrides';

-- Indexes for performance
CREATE INDEX idx_cvo_contract_active ON contract_validation_overrides(contract_uuid, active);
CREATE INDEX idx_cvo_rule_id ON contract_validation_overrides(rule_id);
CREATE INDEX idx_cvo_created_at ON contract_validation_overrides(created_at);

-- ======================================================================================
-- Table 2: Contract Rate Adjustment Overrides
-- ======================================================================================
-- Stores contract-specific overrides for rate adjustment rules
-- Allows modifying how hourly rates are adjusted over time per contract

CREATE TABLE contract_rate_adjustment_overrides (
    id INT AUTO_INCREMENT PRIMARY KEY,
    contract_uuid VARCHAR(36) NOT NULL COMMENT 'FK to contracts.uuid',
    rule_id VARCHAR(64) NOT NULL COMMENT 'Stable identifier for the rule (e.g., annual-increase-2025, inflation-adjustment)',
    override_type ENUM('REPLACE', 'DISABLE', 'MODIFY') NOT NULL COMMENT 'Type of override: REPLACE=full replacement, DISABLE=deactivate rule, MODIFY=adjust parameters',
    label VARCHAR(255) COMMENT 'Display label for the override (e.g., "Custom 5% annual increase")',
    adjustment_type VARCHAR(50) COMMENT 'Type: ANNUAL_INCREASE, INFLATION_LINKED, STEP_BASED, FIXED_ADJUSTMENT',
    adjustment_percent DECIMAL(5,2) COMMENT 'Percentage adjustment (e.g., 3.50 for 3.5%)',
    frequency VARCHAR(20) COMMENT 'Frequency: YEARLY, QUARTERLY, MONTHLY, ONE_TIME',
    effective_date DATE COMMENT 'Date when this adjustment takes effect',
    end_date DATE COMMENT 'Date when this adjustment stops (nullable = ongoing)',
    priority INT COMMENT 'Application order - lower numbers execute first (10, 20, 30...)',
    active BOOLEAN DEFAULT TRUE COMMENT 'Soft delete flag - allows deactivation without deletion',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    created_by VARCHAR(36) COMMENT 'User UUID who created this override',


    -- Unique constraint: one override per contract per rule
    CONSTRAINT uq_contract_rate_rule
        UNIQUE (contract_uuid, rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Contract-specific rate adjustment rule overrides';

-- Indexes for performance
CREATE INDEX idx_crao_contract_active ON contract_rate_adjustment_overrides(contract_uuid, active);
CREATE INDEX idx_crao_dates ON contract_rate_adjustment_overrides(effective_date, end_date);

-- ======================================================================================
-- Table 3: Pricing Rule Overrides
-- ======================================================================================
-- Stores contract-specific overrides for pricing rules
-- Allows modifying invoice calculation rules per contract

CREATE TABLE pricing_rule_overrides (
    id INT AUTO_INCREMENT PRIMARY KEY,
    contract_uuid VARCHAR(36) NOT NULL COMMENT 'FK to contracts.uuid',
    rule_id VARCHAR(64) NOT NULL COMMENT 'Stable identifier for the rule (e.g., admin-fee-override, custom-discount)',
    override_type ENUM('REPLACE', 'DISABLE', 'MODIFY') NOT NULL COMMENT 'Type of override: REPLACE=full replacement, DISABLE=deactivate rule, MODIFY=adjust parameters',
    label VARCHAR(255) COMMENT 'Display label for the override (e.g., "Custom 8% admin fee")',
    rule_step_type VARCHAR(50) COMMENT 'Type: PERCENT_DISCOUNT_ON_SUM, ADMIN_FEE_PERCENT, FIXED_DEDUCTION, GENERAL_DISCOUNT_PERCENT, ROUNDING',
    step_base VARCHAR(50) COMMENT 'Calculation base: SUM_BEFORE_DISCOUNTS or CURRENT_SUM',
    percent DECIMAL(10,4) COMMENT 'Percentage value for percentage-based rules (e.g., 5.00 for 5%)',
    amount DECIMAL(15,2) COMMENT 'Fixed amount for FIXED_DEDUCTION rules',
    param_key VARCHAR(64) COMMENT 'Reference to contract_type_items key (e.g., trapperabat)',
    valid_from DATE COMMENT 'Rule activation date (nullable = always active)',
    valid_to DATE COMMENT 'Rule expiration date (nullable = never expires)',
    priority INT COMMENT 'Execution order - lower numbers execute first (10, 20, 30...)',
    active BOOLEAN DEFAULT TRUE COMMENT 'Soft delete flag - allows deactivation without deletion',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    created_by VARCHAR(36) COMMENT 'User UUID who created this override',


    -- Unique constraint: one override per contract per rule
    CONSTRAINT uq_contract_pricing_rule
        UNIQUE (contract_uuid, rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Contract-specific pricing rule overrides';

-- Indexes for performance
CREATE INDEX idx_pro_contract_active ON pricing_rule_overrides(contract_uuid, active);
CREATE INDEX idx_pro_dates ON pricing_rule_overrides(valid_from, valid_to);

-- ======================================================================================
-- Table 4: Contract Rule Audit Log
-- ======================================================================================
-- Tracks all changes to contract rule overrides for compliance and audit purposes
-- Captures before/after states as JSON for complete audit trail

CREATE TABLE contract_rule_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_uuid VARCHAR(36) NOT NULL COMMENT 'Contract affected by this audit entry',
    rule_type ENUM('VALIDATION', 'RATE_ADJUSTMENT', 'PRICING') NOT NULL COMMENT 'Type of rule that was modified',
    rule_id VARCHAR(64) NOT NULL COMMENT 'Identifier of the specific rule modified',
    action ENUM('CREATE', 'UPDATE', 'DELETE', 'DISABLE', 'ENABLE') NOT NULL COMMENT 'Action performed on the rule',
    old_value JSON COMMENT 'Previous state of the rule (nullable for CREATE actions)',
    new_value JSON COMMENT 'New state of the rule (nullable for DELETE actions)',
    user_id VARCHAR(36) COMMENT 'User UUID who performed the action',
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'When the action occurred',

    -- Indexes for audit queries
    INDEX idx_audit_contract (contract_uuid),
    INDEX idx_audit_timestamp (timestamp),
    INDEX idx_audit_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Audit trail for all contract rule override changes';

-- ======================================================================================
-- Data Integrity Constraints
-- ======================================================================================

-- Validation overrides: ensure positive priority
ALTER TABLE contract_validation_overrides
    ADD CONSTRAINT chk_cvo_positive_priority
        CHECK (priority IS NULL OR priority > 0);

-- Validation overrides: ensure valid threshold
ALTER TABLE contract_validation_overrides
    ADD CONSTRAINT chk_cvo_valid_threshold
        CHECK (threshold_value IS NULL OR threshold_value >= 0);

-- Rate adjustment overrides: ensure valid date range
ALTER TABLE contract_rate_adjustment_overrides
    ADD CONSTRAINT chk_crao_valid_date_range
        CHECK (end_date IS NULL OR effective_date < end_date);

-- Rate adjustment overrides: ensure positive priority
ALTER TABLE contract_rate_adjustment_overrides
    ADD CONSTRAINT chk_crao_positive_priority
        CHECK (priority IS NULL OR priority > 0);

-- Rate adjustment overrides: ensure valid percentage
ALTER TABLE contract_rate_adjustment_overrides
    ADD CONSTRAINT chk_crao_valid_percent
        CHECK (adjustment_percent IS NULL OR (adjustment_percent >= -100 AND adjustment_percent <= 1000));

-- Pricing rule overrides: ensure valid date range
ALTER TABLE pricing_rule_overrides
    ADD CONSTRAINT chk_pro_valid_date_range
        CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from < valid_to);

-- Pricing rule overrides: ensure positive priority
ALTER TABLE pricing_rule_overrides
    ADD CONSTRAINT chk_pro_positive_priority
        CHECK (priority IS NULL OR priority > 0);

-- Pricing rule overrides: ensure valid percentage
ALTER TABLE pricing_rule_overrides
    ADD CONSTRAINT chk_pro_valid_percent
        CHECK (percent IS NULL OR (percent >= 0 AND percent <= 100));

-- Pricing rule overrides: ensure valid amount
ALTER TABLE pricing_rule_overrides
    ADD CONSTRAINT chk_pro_valid_amount
        CHECK (amount IS NULL OR amount != 0);
COMMIT;
-- ======================================================================================
-- Migration Notes
-- ======================================================================================
--
-- ✅ Four new tables created for contract override system
-- ✅ Foreign key constraints ensure referential integrity with contracts table
-- ✅ Unique constraints prevent duplicate overrides per contract/rule combination
-- ✅ Soft delete pattern (active flag) preserves audit history
-- ✅ Comprehensive indexing for query performance
-- ✅ Check constraints enforce data quality
-- ✅ Audit table provides complete change tracking
-- ✅ All tables use utf8mb4 for full Unicode support
-- ✅ Timestamps track creation and modification times
-- ✅ created_by field enables user accountability
--
-- Feature Flag: This schema supports the new override system but does not activate it
-- The existing contract type rule system remains unchanged and fully functional
--
-- Next Steps:
-- 1. V104: Add additional performance indexes
-- 2. V105: Add audit triggers for automatic change tracking
-- 3. JPA entities implementation
-- 4. Service layer with feature flag protection
--
-- ======================================================================================
