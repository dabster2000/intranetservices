-- Create contract_rate_adjustments table for time-based rate modifications
-- Stores rate adjustment rules that modify hourly rates based on time/date
-- Separate from pricing rules as these handle rate evolution over time, not invoice calculations

CREATE TABLE contract_rate_adjustments (
    id INT AUTO_INCREMENT PRIMARY KEY,
    contract_type_code VARCHAR(50) NOT NULL COMMENT 'FK to contract_type_definitions.code',
    rule_id VARCHAR(64) NOT NULL COMMENT 'Stable identifier for the rule (e.g., annual-increase-2025)',
    label VARCHAR(255) NOT NULL COMMENT 'Display label for the rule (e.g., 3% annual rate increase)',
    adjustment_type VARCHAR(50) NOT NULL COMMENT 'Type: ANNUAL_INCREASE, INFLATION_LINKED, STEP_BASED, FIXED_ADJUSTMENT',

    -- Adjustment parameters
    adjustment_percent DECIMAL(5,2) COMMENT 'Percentage adjustment (e.g., 3.50 for 3.5%)',
    frequency VARCHAR(20) COMMENT 'Frequency: YEARLY, QUARTERLY, MONTHLY, ONE_TIME',

    -- Date range for adjustment applicability
    effective_date DATE NOT NULL COMMENT 'Date when this adjustment takes effect',
    end_date DATE COMMENT 'Date when this adjustment stops (nullable = ongoing)',

    priority INT NOT NULL COMMENT 'Application order - lower numbers execute first (10, 20, 30...)',
    active BOOLEAN DEFAULT TRUE COMMENT 'Soft delete flag',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Foreign key constraint
    CONSTRAINT fk_rate_adjustment_contract_type
        FOREIGN KEY (contract_type_code)
        REFERENCES contract_type_definitions(code)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Unique constraints
    UNIQUE KEY unique_rate_rule (contract_type_code, rule_id),

    -- Indexes for performance
    INDEX idx_contract_type (contract_type_code),
    INDEX idx_active (active),
    INDEX idx_effective_date (effective_date),
    INDEX idx_contract_type_active_date (contract_type_code, active, effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Rate adjustment rules for contract types - handles time-based rate modifications';

-- Add check constraints for data integrity
ALTER TABLE contract_rate_adjustments
    ADD CONSTRAINT chk_valid_date_range
        CHECK (end_date IS NULL OR effective_date < end_date);

ALTER TABLE contract_rate_adjustments
    ADD CONSTRAINT chk_positive_priority
        CHECK (priority > 0);

ALTER TABLE contract_rate_adjustments
    ADD CONSTRAINT chk_valid_percent
        CHECK (adjustment_percent IS NULL OR (adjustment_percent >= -100 AND adjustment_percent <= 1000));
