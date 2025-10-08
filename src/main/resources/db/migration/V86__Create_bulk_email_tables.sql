-- Bulk Email System Tables
-- Allows sending the same email with attachments to multiple recipients
-- with throttling and progress tracking via JBeret batch jobs

-- Main job table: stores metadata for each bulk email job
CREATE TABLE bulk_email_job (
    uuid VARCHAR(36) PRIMARY KEY,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    status ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    total_recipients INT NOT NULL DEFAULT 0,
    sent_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Recipients table: tracks individual recipient status
CREATE TABLE bulk_email_recipient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_uuid VARCHAR(36) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    status ENUM('PENDING', 'SENT', 'FAILED') NOT NULL DEFAULT 'PENDING',
    sent_at DATETIME NULL,
    error_message TEXT NULL,
    INDEX idx_job_status (job_uuid, status),
    INDEX idx_recipient (recipient_email),
    FOREIGN KEY (job_uuid) REFERENCES bulk_email_job(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Attachments table: stores attachments once per job (shared by all recipients)
CREATE TABLE bulk_email_attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_uuid VARCHAR(36) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    content MEDIUMBLOB NOT NULL,
    file_size BIGINT NOT NULL,
    INDEX idx_job (job_uuid),
    FOREIGN KEY (job_uuid) REFERENCES bulk_email_job(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
