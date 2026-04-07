-- V277__Create_month_submission_table.sql
-- Replaces week_submission (V276, never deployed) with month-based submission.
-- A row is created on first submit. No row = OPEN (implicit).
-- Status lifecycle: (no row) -> SUBMITTED -> UNLOCKED -> SUBMITTED (re-submit)

-- Drop week_submission if it exists (V276 was never deployed to staging/production,
-- but may exist on dev/feature branches)
DROP TABLE IF EXISTS week_submission;

CREATE TABLE month_submission (
    uuid CHAR(36) NOT NULL,
    useruuid VARCHAR(36) NOT NULL,
    year INT NOT NULL,
    month INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at DATETIME NULL,
    unlocked_at DATETIME NULL,
    unlocked_by VARCHAR(36) NULL,
    unlock_reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_ms_user_month (useruuid, year, month),
    INDEX idx_ms_user_year (useruuid, year),
    INDEX idx_ms_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
