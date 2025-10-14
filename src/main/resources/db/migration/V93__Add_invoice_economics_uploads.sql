-- Add robust upload tracking for queued internal invoices
-- Tracks upload attempts to e-conomics per company with retry support

CREATE TABLE invoice_economics_uploads (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    invoiceuuid VARCHAR(40) NOT NULL,
    companyuuid VARCHAR(36) NOT NULL,
    upload_type ENUM('ISSUER', 'DEBTOR') NOT NULL,
    status ENUM('PENDING', 'SUCCESS', 'FAILED') NOT NULL DEFAULT 'PENDING',
    journal_number INT NOT NULL COMMENT 'E-conomics journal number used for upload',
    voucher_number INT NULL COMMENT 'Voucher number returned from e-conomics on success',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    last_attempt_at DATETIME NULL,
    last_error TEXT NULL COMMENT 'Error message from last failed attempt',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Ensure we don't create duplicate upload tasks
    UNIQUE KEY uk_invoice_company_type (invoiceuuid, companyuuid, upload_type),

    -- Optimize retry queries
    KEY idx_status_attempts (status, attempt_count),
    KEY idx_next_retry (status, last_attempt_at),
    KEY idx_invoice (invoiceuuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
