-- Durable, bounded calendar recovery requests. No calendar subjects, bodies or token material.
CREATE TABLE calendar_sync_job (
    uuid CHAR(36) NOT NULL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    full_read BOOLEAN NOT NULL,
    started_by CHAR(36) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    claimed_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    summary_json TEXT NULL,
    failure_code VARCHAR(64) NULL,
    INDEX ix_calendar_sync_job_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE calendar_sync_job_lock (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO calendar_sync_job_lock (id) VALUES (1);
