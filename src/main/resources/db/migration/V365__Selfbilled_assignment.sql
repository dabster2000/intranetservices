-- ====================================================================
-- V365: Self-billed settlement workbench — human assignment authority.
--
-- Part A re-asserts the V364 objects (CREATE TABLE IF NOT EXISTS + INSERT
-- IGNORE seeds): the nightly prod->staging refresh strips both the V364
-- tables and their flyway_schema_history row, and prod has never run V364.
-- Every combination (tables missing + history claiming applied; clean
-- re-run; prod from scratch) converges.
--
-- Part B evolves the model:
--   selfbilled_line.booking_date   the e-conomic entry date (human reads
--                                  "booked Oct for Aug work").
--   status vocabulary              parse-state values (RESOLVED/UNMAPPED_CODE/
--                                  UNPARSEABLE) -> workflow values (UNASSIGNED/
--                                  ASSIGNED/SAME_COMPANY/SETTLED/IGNORED).
--                                  Parse state is derivable from the suggestion
--                                  columns, which remain as machine suggestions.
--   selfbilled_assignment          the audited human decision: document ->
--                                  consultant + work-period (+ share for splits).
--   invoice_item_attributions      widen source VARCHAR(10) -> VARCHAR(30) for
--                                  'SELFBILLED_ASSIGNMENT' (21 chars).
--
-- Rollback: DROP TABLE selfbilled_assignment;
--           ALTER TABLE selfbilled_line DROP COLUMN booking_date;
--           (status values + source width are backwards-safe to leave.)
-- ====================================================================

-- ── Part A: re-assert V364 (verbatim DDL, idempotent) ──────────────

CREATE TABLE IF NOT EXISTS selfbilled_source (
    uuid                    VARCHAR(36)  NOT NULL COMMENT 'UUID primary key',
    agreement_company_uuid  VARCHAR(36)  NOT NULL COMMENT 'Debtor company whose e-conomic agreement books the self-billing (A/S)',
    account_number          INT          NOT NULL COMMENT 'e-conomic revenue account (2104 Vattenfall, 2106 Energinet)',
    client_uuid             VARCHAR(36)  NOT NULL COMMENT 'Synthetic billing client this account maps to',
    enabled                 TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = paused, not imported',
    label                   VARCHAR(150) NULL     COMMENT 'Human label (e.g. "Vattenfall")',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_selfbilled_source (agreement_company_uuid, account_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS selfbilled_code_map (
    uuid                    VARCHAR(36) NOT NULL COMMENT 'UUID primary key',
    agreement_company_uuid  VARCHAR(36) NOT NULL,
    account_number          INT         NOT NULL,
    code                    VARCHAR(40) NOT NULL COMMENT 'Magnit consultant code as it appears in the line text',
    consultant_uuid         VARCHAR(36) NOT NULL COMMENT 'Resolved Trustworks consultant (user.uuid)',
    created_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(36) NULL     COMMENT 'Admin who confirmed the mapping',
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_selfbilled_code (agreement_company_uuid, account_number, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS selfbilled_line (
    uuid                  VARCHAR(36)   NOT NULL COMMENT 'UUID primary key',
    source_uuid           VARCHAR(36)   NOT NULL COMMENT 'FK-by-value to selfbilled_source.uuid',
    client_uuid           VARCHAR(36)   NOT NULL COMMENT 'Billing client (from the source)',
    debtor_company_uuid   VARCHAR(36)   NOT NULL COMMENT 'Agreement/debtor company (A/S)',
    account_number        INT           NOT NULL,
    voucher_number        INT           NOT NULL COMMENT 'e-conomic voucher — the NETTING UNIT (D10)',
    entry_number          BIGINT        NOT NULL COMMENT 'e-conomic entry — idempotency key',
    faktura_number        VARCHAR(40)   NULL     COMMENT 'Per-line faktura ref (parsed from THIS line; null for corrections)',
    work_year             INT           NULL     COMMENT 'SUGGESTED work period year (voucher-resolved; authority = selfbilled_assignment)',
    work_month            INT           NULL     COMMENT 'SUGGESTED work period month 1-12',
    code                  VARCHAR(40)   NULL     COMMENT 'SUGGESTED Magnit code (voucher-resolved)',
    consultant_uuid       VARCHAR(36)   NULL     COMMENT 'SUGGESTED consultant (code map; authority = selfbilled_assignment)',
    issuer_company_uuid   VARCHAR(36)   NULL     COMMENT 'SUGGESTED issuer company as-of suggested work period',
    amount                DECIMAL(15,2) NOT NULL COMMENT 'Signed line amount as posted (revenue negative)',
    source_text           VARCHAR(255)  NULL     COMMENT 'Raw e-conomic line text (audit)',
    status                VARCHAR(20)   NOT NULL COMMENT 'UNASSIGNED | ASSIGNED | SAME_COMPANY | SETTLED | IGNORED (voucher-level)',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    refreshed_at          DATETIME      NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_selfbilled_entry (entry_number),
    KEY idx_selfbilled_voucher (account_number, voucher_number),
    KEY idx_selfbilled_period (client_uuid, work_year, work_month),
    KEY idx_selfbilled_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO selfbilled_source (uuid, agreement_company_uuid, account_number, client_uuid, enabled, label)
VALUES
 ('5e1f0001-0000-4000-8000-000000002104', 'd8894494-2fb4-4f72-9e05-e6032e6dd691', 2104, '2cbb7f5e-9e2b-4edc-870b-e9591dc58891', 1, 'Vattenfall'),
 ('5e1f0001-0000-4000-8000-000000002106', 'd8894494-2fb4-4f72-9e05-e6032e6dd691', 2106, '16e3ccad-f053-4804-bbcc-cde32de51006', 1, 'Energinet');

-- ── Part B: workbench evolution ─────────────────────────────────────

ALTER TABLE selfbilled_line
    ADD COLUMN IF NOT EXISTS booking_date DATE NULL COMMENT 'e-conomic entry date — the BOOKING month (often != work period)' AFTER entry_number;

ALTER TABLE selfbilled_line
    ADD KEY IF NOT EXISTS idx_selfbilled_booking (client_uuid, booking_date);

-- Parse-state statuses -> workflow statuses. Net-zero vouchers become IGNORED
-- at the next capture run (the importer recomputes voucher nets there).
UPDATE selfbilled_line
SET status = 'UNASSIGNED'
WHERE status IN ('RESOLVED', 'UNMAPPED_CODE', 'UNPARSEABLE');

CREATE TABLE IF NOT EXISTS selfbilled_assignment (
    uuid                  VARCHAR(36)   NOT NULL COMMENT 'UUID primary key',
    selfbilled_line_uuid  VARCHAR(36)   NOT NULL COMMENT 'FK-by-value to the voucher''s anchor line (its parseable Faktura line)',
    consultant_uuid       VARCHAR(36)   NOT NULL COMMENT 'Assigned consultant (user.uuid)',
    work_year             INT           NOT NULL COMMENT 'Human-set work period the document pays for',
    work_month            INT           NOT NULL COMMENT '1-12',
    share_amount          DECIMAL(15,2) NOT NULL COMMENT 'Signed as posted (revenue negative), like selfbilled_line.amount; normally = the voucher net',
    assigned_by           VARCHAR(36)   NOT NULL COMMENT 'Actor user uuid (X-Requested-By)',
    assigned_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source                VARCHAR(20)   NOT NULL DEFAULT 'HUMAN' COMMENT 'HUMAN | AUTO_SAMECOMPANY',
    PRIMARY KEY (uuid),
    KEY idx_sba_line (selfbilled_line_uuid),
    KEY idx_sba_consultant_period (consultant_uuid, work_year, work_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 'SELFBILLED_ASSIGNMENT' (21 chars) must fit; V284 created VARCHAR(10).
ALTER TABLE invoice_item_attributions
    MODIFY COLUMN source VARCHAR(30) NOT NULL DEFAULT 'AUTO';
