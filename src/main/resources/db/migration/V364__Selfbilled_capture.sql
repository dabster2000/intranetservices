-- ====================================================================
-- V364: Self-billed capture for cross-company PHANTOM settlement.
--
-- Additive only. Does NOT touch invoices, work_full, or the lump-PHANTOM
-- import. Three tables:
--   selfbilled_source    config: which (agreement company, e-conomic account)
--                        maps to which billing client. Seeded with the two
--                        known rows (2104 Vattenfall, 2106 Energinet).
--   selfbilled_code_map  Magnit consultant code -> Trustworks consultant.
--                        Codes are Magnit's, not initials, so the map is
--                        mandatory. Unique per (agreement, account, code).
--   selfbilled_line      one row per e-conomic debtor line (idempotent on
--                        entry_number). Settlement nets these BY voucher_number
--                        (Decision D10): the voucher-resolved work period/code/
--                        consultant is stamped onto EVERY sibling row (incl.
--                        correction lines) so SQL SUMs include the corrections.
--                        amount is SIGNED as posted (revenue negative); the
--                        settlement target negates the netted sum.
--
-- IDEMPOTENT (IF NOT EXISTS) so re-running after a partial failure is safe;
-- MariaDB auto-commits each DDL statement.
--
-- Rollback: DROP TABLE selfbilled_line, selfbilled_code_map, selfbilled_source;
-- ====================================================================

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
    work_year             INT           NULL     COMMENT 'Voucher-resolved work period year (stamped on every sibling; null only when the voucher is unresolved)',
    work_month            INT           NULL     COMMENT 'Voucher-resolved work period month 1-12',
    code                  VARCHAR(40)   NULL     COMMENT 'Voucher-resolved Magnit code',
    consultant_uuid       VARCHAR(36)   NULL     COMMENT 'Resolved consultant (null until mapped)',
    issuer_company_uuid   VARCHAR(36)   NULL     COMMENT 'Consultant company as-of work period (null until resolved)',
    amount                DECIMAL(15,2) NOT NULL COMMENT 'Signed line amount as posted (revenue negative)',
    source_text           VARCHAR(255)  NULL     COMMENT 'Raw e-conomic line text (audit)',
    status                VARCHAR(20)   NOT NULL COMMENT 'RESOLVED | UNMAPPED_CODE | UNPARSEABLE (voucher-level)',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    refreshed_at          DATETIME      NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_selfbilled_entry (entry_number),
    KEY idx_selfbilled_voucher (account_number, voucher_number),
    KEY idx_selfbilled_period (client_uuid, work_year, work_month),
    KEY idx_selfbilled_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed the two known sources (Decision D4 "seeded with the two known rows").
-- INSERT IGNORE so re-running the migration does not duplicate (uq guard).
INSERT IGNORE INTO selfbilled_source (uuid, agreement_company_uuid, account_number, client_uuid, enabled, label)
VALUES
 ('5e1f0001-0000-4000-8000-000000002104', 'd8894494-2fb4-4f72-9e05-e6032e6dd691', 2104, '2cbb7f5e-9e2b-4edc-870b-e9591dc58891', 1, 'Vattenfall'),
 ('5e1f0001-0000-4000-8000-000000002106', 'd8894494-2fb4-4f72-9e05-e6032e6dd691', 2106, '16e3ccad-f053-4804-bbcc-cde32de51006', 1, 'Energinet');
