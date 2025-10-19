-- Create contract_type_definitions table for dynamic contract type management
-- This allows contract types to be created and managed via REST API instead of hardcoded enums

CREATE TABLE contract_type_definitions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL COMMENT 'Unique identifier for the contract type (e.g., SKI0217_2026)',
    name VARCHAR(255) NOT NULL COMMENT 'Display name for the contract type',
    description TEXT COMMENT 'Detailed description of the contract type',
    active BOOLEAN DEFAULT TRUE COMMENT 'Soft delete flag - inactive types are hidden but preserved',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Indexes for performance
    INDEX idx_code (code),
    INDEX idx_active (active),
    INDEX idx_code_active (code, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Dynamic contract type definitions that can be managed via REST API';
