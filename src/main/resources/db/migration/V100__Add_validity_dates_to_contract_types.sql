-- Add optional validity date fields to contract_type_definitions
-- Allows contract types to be valid only during specific time periods
--
-- valid_from: Date when the contract type becomes valid (INCLUSIVE)
-- valid_until: Date when the contract type stops being valid (EXCLUSIVE)
--
-- Both fields are nullable. NULL means no date restriction on that end.
--
-- Example: SKI0217_2025
--   valid_from: 2025-01-01 (inclusive)
--   valid_until: 2026-01-01 (exclusive)
--   Valid dates: 2025-01-01 to 2025-12-31

ALTER TABLE contract_type_definitions
ADD COLUMN valid_from DATE NULL COMMENT 'Contract type is valid FROM this date (inclusive)',
ADD COLUMN valid_until DATE NULL COMMENT 'Contract type is valid UNTIL this date (exclusive)';

-- Ensure valid_until is after valid_from when both are specified
ALTER TABLE contract_type_definitions
ADD CONSTRAINT chk_contract_type_date_range
CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from);

-- Add index for efficient date-based queries
CREATE INDEX idx_contract_type_validity ON contract_type_definitions(code, active, valid_from, valid_until);
