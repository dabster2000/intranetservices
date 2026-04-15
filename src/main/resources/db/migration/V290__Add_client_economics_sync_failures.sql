-- =============================================================================
-- Migration V290: E-conomics Phase G2 -- sync failure tracking
-- =============================================================================
-- Spec: SPEC-INV-001 Section 7.1, Section 3.3
-- Purpose: Records every per-(client,company) sync attempt that did not succeed.
--          The retry batchlet iterates this table. One row per (client_uuid, company_uuid).
--
-- Semantics:
--   When ClientResource POST/PUT triggers sync and any agreement's sync fails,
--   we UPSERT a row with attempt_count and next_retry_at. When the retry succeeds,
--   we DELETE the row. If attempt_count exceeds the backoff schedule (Section 6.8),
--   we leave the row with status='ABANDONED' for the admin to resolve manually.

CREATE TABLE client_economics_sync_failures (
    uuid              VARCHAR(36) NOT NULL PRIMARY KEY,
    client_uuid       VARCHAR(36) NOT NULL,
    company_uuid      VARCHAR(36) NOT NULL,
    attempt_count     INT         NOT NULL DEFAULT 0,
    last_error        TEXT        NULL,
    next_retry_at     DATETIME    NOT NULL,
    last_attempted_at DATETIME    NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING | ABANDONED
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_client_economics_sync_failures UNIQUE (client_uuid, company_uuid),
    CONSTRAINT fk_cesf_client  FOREIGN KEY (client_uuid)  REFERENCES client(uuid)    ON DELETE CASCADE,
    CONSTRAINT fk_cesf_company FOREIGN KEY (company_uuid) REFERENCES companies(uuid) ON DELETE CASCADE,

    INDEX idx_cesf_next_retry (status, next_retry_at)
);
