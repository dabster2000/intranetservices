-- Create table for storing locked bonus pool data snapshots
-- This ensures financial data immutability and audit compliance

CREATE TABLE IF NOT EXISTS locked_bonus_pool_data (
    fiscal_year INT PRIMARY KEY COMMENT 'Fiscal year (e.g., 2024 for FY 2024-2025)',
    pool_context_json TEXT NOT NULL COMMENT 'Complete JSON serialization of FiscalYearPoolContext',
    locked_at TIMESTAMP NOT NULL COMMENT 'When the data was locked',
    locked_by VARCHAR(255) NOT NULL COMMENT 'Username/email who locked the data',
    checksum VARCHAR(64) NOT NULL COMMENT 'SHA-256 checksum for data integrity',
    version INT NOT NULL DEFAULT 1 COMMENT 'Optimistic locking version',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Locked bonus pool data for audit and compliance';

-- Index for querying by who locked the data
CREATE INDEX idx_locked_by ON locked_bonus_pool_data(locked_by);

-- Index for querying by lock timestamp
CREATE INDEX idx_locked_at ON locked_bonus_pool_data(locked_at);
