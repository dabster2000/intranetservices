-- ===================================================================
-- Signing Cases Tracking Table
-- ===================================================================
-- Purpose: Store metadata for NextSign document signing cases
--          to enable persistent tracking across sessions
--
-- Business Logic:
--   - Each signing case represents a document sent for digital signature
--   - Cases are user-scoped (filtered by user_uuid)
--   - Minimal metadata stored here; full details fetched from NextSign on-demand
--   - Supports both internally-created and externally-created cases (via sync)
--
-- Related Features:
--   - DocumentSigningPage: Status Tracking tab displays cases from this table
--   - NextSign Integration: External e-signature provider
--   - SigningService: Business logic for case management
--
-- Author: Claude Code
-- Date: 2025-12-07
-- ===================================================================

CREATE TABLE signing_cases (
    -- Primary key
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- NextSign identifiers
    case_key VARCHAR(255) NOT NULL UNIQUE COMMENT 'NextSign case ID (_id from API)',
    nextsign_key VARCHAR(255) COMMENT 'NextSign internal key (nextSignKey from API)',

    -- Ownership and reference
    user_uuid VARCHAR(36) NOT NULL COMMENT 'Creator/owner UUID from users table',
    reference_id VARCHAR(255) COMMENT 'Internal reference ID (typically user UUID)',

    -- Case metadata
    document_name VARCHAR(500) NOT NULL COMMENT 'Document title/name',
    status VARCHAR(50) NOT NULL COMMENT 'Case status: PENDING, IN_PROGRESS, COMPLETED, EXPIRED',
    folder VARCHAR(255) DEFAULT 'Default' COMMENT 'NextSign folder/category',

    -- Signer tracking
    total_signers INT DEFAULT 0 COMMENT 'Total number of signers',
    completed_signers INT DEFAULT 0 COMMENT 'Number of signers who have completed',

    -- Audit timestamps
    created_at TIMESTAMP NOT NULL COMMENT 'Case creation timestamp',
    updated_at TIMESTAMP COMMENT 'Last status update timestamp',

    -- Indexes for query performance
    INDEX idx_user_uuid (user_uuid) COMMENT 'Filter by user',
    INDEX idx_status (status) COMMENT 'Filter by status',
    INDEX idx_created_at (created_at DESC) COMMENT 'Sort by creation date',
    INDEX idx_case_key (case_key) COMMENT 'Lookup by NextSign case key'

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Tracks NextSign document signing cases for persistent display in UI';
