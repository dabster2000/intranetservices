-- Create contract_validation_rules table for business constraint rules
-- Stores validation rules that enforce constraints during work registration and contract operations
-- Separate from pricing rules as these are validation constraints, not invoice calculations

CREATE TABLE contract_validation_rules (
    id INT AUTO_INCREMENT PRIMARY KEY,
    contract_type_code VARCHAR(50) NOT NULL COMMENT 'FK to contract_type_definitions.code',
    rule_id VARCHAR(64) NOT NULL COMMENT 'Stable identifier for the rule (e.g., ski-notes-required)',
    label VARCHAR(255) NOT NULL COMMENT 'Display label for the rule (e.g., Notes required for time registration)',
    validation_type VARCHAR(50) NOT NULL COMMENT 'Type: NOTES_REQUIRED, MIN_HOURS_PER_ENTRY, MAX_HOURS_PER_DAY, REQUIRE_TASK_SELECTION',

    -- Configuration fields
    required BOOLEAN DEFAULT FALSE COMMENT 'For boolean validations (e.g., NOTES_REQUIRED)',
    threshold_value DECIMAL(10,2) COMMENT 'For numeric thresholds (e.g., MIN_HOURS, MAX_HOURS)',
    config_json JSON COMMENT 'Additional configuration for complex rules',

    priority INT NOT NULL COMMENT 'Evaluation order - lower numbers execute first (10, 20, 30...)',
    active BOOLEAN DEFAULT TRUE COMMENT 'Soft delete flag',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Foreign key constraint
    CONSTRAINT fk_validation_contract_type
        FOREIGN KEY (contract_type_code)
        REFERENCES contract_type_definitions(code)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Unique constraints
    UNIQUE KEY unique_validation_rule (contract_type_code, rule_id),

    -- Indexes for performance
    INDEX idx_contract_type (contract_type_code),
    INDEX idx_active (active),
    INDEX idx_contract_type_active_priority (contract_type_code, active, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Validation rules for contract types - defines business constraints and requirements';

-- Add check constraints for data integrity
ALTER TABLE contract_validation_rules
    ADD CONSTRAINT chk_positive_priority
        CHECK (priority > 0);

ALTER TABLE contract_validation_rules
    ADD CONSTRAINT chk_positive_threshold
        CHECK (threshold_value IS NULL OR threshold_value >= 0);
