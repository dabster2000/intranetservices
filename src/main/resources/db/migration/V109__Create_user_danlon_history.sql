-- Migration: V109 - Create user_danlon_history table
-- Purpose: Store historical Danløn employee numbers with effective dates
-- Pattern: Follows UserBankInfo temporal data pattern
-- Date: 2025-11-17

CREATE TABLE user_danlon_history (
    uuid         VARCHAR(36)  NOT NULL COMMENT 'Primary key (UUID)',
    useruuid     VARCHAR(36)  NOT NULL COMMENT 'Foreign key to user table',
    active_date  DATE         NOT NULL COMMENT 'First day of month when this Danløn number became active',
    danlon       VARCHAR(36)  NOT NULL COMMENT 'Danløn employee number',
    created_date DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Audit: when this record was created in the system',
    created_by   VARCHAR(255) NULL     COMMENT 'Audit: username who created this record',

    CONSTRAINT user_danlon_history_pk
        PRIMARY KEY (uuid),

    CONSTRAINT user_danlon_history_unique_active_date
        UNIQUE (useruuid, active_date)
        COMMENT 'Prevent duplicate records for the same user and month'

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Historical Danløn employee numbers with effective dates. Enables temporal queries for payroll reports.';

-- Index for temporal queries: "Get Danløn number for user X on date Y"
-- Covers the most common query pattern: WHERE useruuid = ? AND active_date <= ? ORDER BY active_date DESC
CREATE INDEX idx_user_danlon_history_useruuid_date
    ON user_danlon_history (useruuid, active_date DESC)
    COMMENT 'Optimize temporal queries for current/historical Danløn number lookup';

-- Index for audit queries: "Who changed what when"
CREATE INDEX idx_user_danlon_history_created
    ON user_danlon_history (created_date DESC)
    COMMENT 'Optimize audit trail queries by creation timestamp';
