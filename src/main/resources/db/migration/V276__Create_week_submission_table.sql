-- V276__Create_week_submission_table.sql
-- Adds weekly timesheet submission tracking.
-- A row is created when a consultant submits a week. No row = OPEN (implicit).
-- Status lifecycle: (no row) -> SUBMITTED -> UNLOCKED -> SUBMITTED (re-submit)

CREATE TABLE week_submission (
    uuid CHAR(36) NOT NULL,
    useruuid VARCHAR(36) NOT NULL,
    year INT NOT NULL,
    week_number INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at DATETIME NULL,
    unlocked_at DATETIME NULL,
    unlocked_by VARCHAR(36) NULL,
    unlock_reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_ws_user_week (useruuid, year, week_number),
    INDEX idx_ws_user_year (useruuid, year),
    INDEX idx_ws_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
