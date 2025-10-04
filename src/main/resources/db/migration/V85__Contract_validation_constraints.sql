-- V85: Contract Validation Constraints
-- This migration adds database-level constraints to prevent contract conflicts
-- and ensure data integrity for consultant assignments

-- =========================================================================
-- PART 0: Data Cleanup - Fix Existing Constraint Violations
-- =========================================================================
-- Before adding constraints, we must fix existing data that violates them.
-- All fixes are logged in an audit table for review and potential rollback.

-- Create audit table to track data fixes made by this migration
CREATE TABLE IF NOT EXISTS contract_data_fixes_v85_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fix_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    consultant_uuid VARCHAR(36),
    contract_uuid VARCHAR(36),
    user_uuid VARCHAR(36),
    issue_type VARCHAR(50),
    old_activefrom DATE,
    old_activeto DATE,
    new_activefrom DATE,
    new_activeto DATE,
    old_rate DOUBLE,
    new_rate DOUBLE,
    old_hours DOUBLE,
    new_hours DOUBLE,
    description TEXT,
    INDEX idx_audit_consultant (consultant_uuid),
    INDEX idx_audit_issue (issue_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='Audit log of data fixes applied during V85 migration';

-- Log all rows with reversed dates (activefrom > activeto)
INSERT INTO contract_data_fixes_v85_audit (
    consultant_uuid, contract_uuid, user_uuid, issue_type,
    old_activefrom, old_activeto, new_activefrom, new_activeto,
    old_rate, new_rate, old_hours, new_hours, description
)
SELECT
    uuid, contractuuid, useruuid, 'REVERSED_DATES',
    activefrom, activeto, activeto, activefrom,
    rate, rate, hours, hours,
    CONCAT('Reversed dates: FROM ', activefrom, ' TO ', activeto, ' -> FROM ', activeto, ' TO ', activefrom)
FROM contract_consultants
WHERE activefrom > activeto;

-- Log all rows with invalid rates (rate <= 0)
INSERT INTO contract_data_fixes_v85_audit (
    consultant_uuid, contract_uuid, user_uuid, issue_type,
    old_activefrom, old_activeto, new_activefrom, new_activeto,
    old_rate, new_rate, old_hours, new_hours, description
)
SELECT
    uuid, contractuuid, useruuid, 'INVALID_RATE',
    activefrom, activeto, activefrom, activeto,
    rate, 1000.0, hours, hours,
    CONCAT('Fixed invalid rate: ', rate, ' -> 1000.0 (default)')
FROM contract_consultants
WHERE rate <= 0;

-- Log all rows with negative hours (hours < 0)
INSERT INTO contract_data_fixes_v85_audit (
    consultant_uuid, contract_uuid, user_uuid, issue_type,
    old_activefrom, old_activeto, new_activefrom, new_activeto,
    old_rate, new_rate, old_hours, new_hours, description
)
SELECT
    uuid, contractuuid, useruuid, 'NEGATIVE_HOURS',
    activefrom, activeto, activefrom, activeto,
    rate, rate, hours, 0,
    CONCAT('Fixed negative hours: ', hours, ' -> 0')
FROM contract_consultants
WHERE hours < 0;

-- Fix reversed dates by swapping activefrom and activeto
-- In MariaDB, multiple SET clauses in UPDATE happen simultaneously, so this swap works correctly
UPDATE contract_consultants cc
JOIN (
    SELECT uuid, activefrom AS old_from, activeto AS old_to
    FROM contract_consultants
    WHERE activefrom > activeto
) AS swaps ON cc.uuid = swaps.uuid
SET
    cc.activefrom = swaps.old_to,
    cc.activeto = swaps.old_from;

-- Fix invalid rates by setting to default value
UPDATE contract_consultants
SET rate = 1000.0
WHERE rate <= 0;

-- Fix negative hours by setting to 0
UPDATE contract_consultants
SET hours = 0
WHERE hours < 0;

-- =========================================================================
-- PART 1: Add Check Constraints for Date Range Validity
-- =========================================================================
-- Now that data is clean, we can safely add constraints
-- Drop constraints first to make migration idempotent (handles partial previous runs)

-- Drop existing constraints if they exist
ALTER TABLE contract_consultants DROP CONSTRAINT IF EXISTS chk_consultant_date_range;
ALTER TABLE contract_consultants DROP CONSTRAINT IF EXISTS chk_consultant_positive_rate;
ALTER TABLE contract_consultants DROP CONSTRAINT IF EXISTS chk_consultant_non_negative_hours;

-- Ensure activeFrom is always less than or equal to activeTo
ALTER TABLE contract_consultants
ADD CONSTRAINT chk_consultant_date_range
CHECK (activefrom <= activeto);

-- Ensure rate is positive
ALTER TABLE contract_consultants
ADD CONSTRAINT chk_consultant_positive_rate
CHECK (rate > 0);

-- Ensure hours is non-negative
ALTER TABLE contract_consultants
ADD CONSTRAINT chk_consultant_non_negative_hours
CHECK (hours >= 0);

-- =========================================================================
-- PART 2: Add Indexes for Validation Performance
-- =========================================================================

-- Index for efficient validation queries when checking consultant overlaps
CREATE INDEX IF NOT EXISTS idx_consultant_validation
ON contract_consultants(useruuid, contractuuid, activefrom, activeto);

-- Index for contract project lookups during validation
CREATE INDEX IF NOT EXISTS idx_contract_project_validation
ON contract_project(contractuuid, projectuuid);

-- Index for contract status filtering during validation
CREATE INDEX IF NOT EXISTS idx_contract_status
ON contracts(status);

-- =========================================================================
-- PART 3: Create Audit Table for Validation Failures
-- =========================================================================

-- Track validation failures for monitoring and debugging
CREATE TABLE IF NOT EXISTS contract_validation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    validation_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    entity_type VARCHAR(50) NOT NULL, -- 'CONTRACT', 'CONSULTANT', 'PROJECT'
    entity_uuid VARCHAR(36),
    contract_uuid VARCHAR(36),
    user_uuid VARCHAR(36),
    project_uuid VARCHAR(36),
    error_type VARCHAR(100),
    error_message TEXT,
    request_data JSON,
    INDEX idx_validation_log_date (validation_date),
    INDEX idx_validation_log_contract (contract_uuid),
    INDEX idx_validation_log_user (user_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='Audit log of contract validation failures for monitoring';

-- =========================================================================
-- PART 4: Add Comments for Documentation
-- =========================================================================

ALTER TABLE contract_consultants
COMMENT = 'Contract consultant assignments with validation constraints to prevent overlaps';

ALTER TABLE contract_project
COMMENT = 'Links contracts to projects. Multiple contracts per project allowed but consultant overlaps are validated';

ALTER TABLE contracts
COMMENT = 'Master contract table. Status changes trigger validation of all related consultants and projects';

-- =========================================================================
-- MIGRATION SUMMARY
-- =========================================================================
-- This migration has:
-- 1. Fixed existing data constraint violations (logged in contract_data_fixes_v85_audit)
-- 2. Added CHECK constraints to prevent future violations
-- 3. Added performance indexes for validation queries
-- 4. Created audit table for tracking validation failures
-- 5. Added table comments for documentation
--
-- NOTE: Stored functions/procedures were removed to avoid SUPER privilege requirements
--       in managed database environments (AWS RDS, etc.). All validation logic is
--       implemented in the Java application layer (ContractValidationService).
--
-- ACTION REQUIRED: Review contract_data_fixes_v85_audit table to see what data was corrected.
-- Query: SELECT * FROM contract_data_fixes_v85_audit ORDER BY fix_date DESC;
--
-- Data fixes applied:
-- - REVERSED_DATES: Swapped activefrom/activeto where dates were reversed
-- - INVALID_RATE: Set rates <= 0 to default value (1000.0)
-- - NEGATIVE_HOURS: Set negative hours to 0
-- =========================================================================