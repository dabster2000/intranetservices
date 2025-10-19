-- Create pricing_rule_steps table for dynamic pricing rule management
-- Stores individual pricing rules that apply to contract types

CREATE TABLE pricing_rule_steps (
    id INT AUTO_INCREMENT PRIMARY KEY,
    contract_type_code VARCHAR(50) NOT NULL COMMENT 'FK to contract_type_definitions.code',
    rule_id VARCHAR(64) NOT NULL COMMENT 'Stable identifier for the rule (e.g., ski21726-admin)',
    label VARCHAR(255) NOT NULL COMMENT 'Display label for the rule (e.g., 5% SKI administrationsgebyr)',
    rule_step_type VARCHAR(50) NOT NULL COMMENT 'Type: PERCENT_DISCOUNT_ON_SUM, ADMIN_FEE_PERCENT, FIXED_DEDUCTION, GENERAL_DISCOUNT_PERCENT, ROUNDING',
    step_base VARCHAR(50) NOT NULL COMMENT 'Calculation base: SUM_BEFORE_DISCOUNTS or CURRENT_SUM',
    percent DECIMAL(10,4) COMMENT 'Percentage value for percentage-based rules (e.g., 5.00 for 5%)',
    amount DECIMAL(15,2) COMMENT 'Fixed amount for FIXED_DEDUCTION rules',
    param_key VARCHAR(64) COMMENT 'Reference to contract_type_items key (e.g., trapperabat)',
    valid_from DATE COMMENT 'Rule activation date (nullable = always active)',
    valid_to DATE COMMENT 'Rule expiration date (nullable = never expires)',
    priority INT NOT NULL COMMENT 'Execution order - lower numbers execute first (10, 20, 30...)',
    active BOOLEAN DEFAULT TRUE COMMENT 'Soft delete flag',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Foreign key constraint
    CONSTRAINT fk_pricing_rule_contract_type
        FOREIGN KEY (contract_type_code)
        REFERENCES contract_type_definitions(code)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Unique constraints
    UNIQUE KEY unique_rule_id_per_contract (contract_type_code, rule_id),

    -- Note: We allow same priority for inactive rules, but only one active rule per priority
    -- This is enforced at application level, not database level

    -- Indexes for performance
    INDEX idx_contract_type (contract_type_code),
    INDEX idx_active (active),
    INDEX idx_priority (priority),
    INDEX idx_contract_type_active_priority (contract_type_code, active, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Pricing rules for dynamic contract types - defines how invoices are calculated';

-- Add check constraints for data integrity
ALTER TABLE pricing_rule_steps
    ADD CONSTRAINT chk_valid_date_range
        CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from < valid_to);

ALTER TABLE pricing_rule_steps
    ADD CONSTRAINT chk_positive_priority
        CHECK (priority > 0);

ALTER TABLE pricing_rule_steps
    ADD CONSTRAINT chk_valid_percent
        CHECK (percent IS NULL OR (percent >= 0 AND percent <= 100));

ALTER TABLE pricing_rule_steps
    ADD CONSTRAINT chk_valid_amount
        CHECK (amount IS NULL OR amount != 0);
