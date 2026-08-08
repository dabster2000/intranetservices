-- ===================================================================
-- V477: invoice_booking_attempt — durable intent record for the
--       e-conomic booking POST
-- ===================================================================
-- Feature: Invoice booking integrity (plan 2026-08-07)
--
-- Purpose:
--   Persist the idempotency key and the exact request body BEFORE the
--   irreversible POST /invoices/booked, so a retry replays byte-identically
--   and e-conomic returns the cached 201 instead of 400 PayloadChanged.
--
--   Incident 2026-08-07: invoice 77ee648a booked as 28214 (HTTP 201), the
--   local transaction died on a MariaDB 1213 deadlock at JTA commit and
--   rolled back. Four retries re-resolved draftInvoiceNumber live, changing
--   the payload under an invariant key, and got 400 PayloadChanged. Once
--   e-conomic's 1-hour idempotency window expired a further attempt booked
--   a SECOND invoice, 28218.
--
-- Authority:
--   This table is EVIDENCE, never state. invoices.economics_booked_number
--   remains the sole source of truth for "is this invoice booked". Every
--   invoice booked before this migration, and every invoice booked by an
--   old task during the blue/green rollout, has no row here. No reconciler
--   or query may treat a missing row as an anomaly.
--
-- Idempotency:
--   CREATE TABLE / CREATE INDEX IF NOT EXISTS throughout, no DROP anywhere.
--   quarkus.flyway.repair-at-start is true and ignore-migration-patterns
--   resolves EMPTY (finding F-7), so an old-image boot writes a DELETE
--   tombstone for this version and the next forward deploy RE-RUNS this
--   file. A bare CREATE TABLE would then crash every task at boot, in a
--   self-perpetuating loop.
--
-- Online DDL: not applicable — the indexes are built on a new empty table.
--
-- Rollback: NONE. Never DROP this table. Old code ignores it entirely.
--
-- Author: Claude Code (plan docs/superpowers/plans/2026-08-07-invoice-booking-integrity.md, S1.1 — renumbered V476→V477, collision with a concurrent session)
-- ===================================================================

CREATE TABLE IF NOT EXISTS invoice_booking_attempt (
    uuid                   CHAR(36)     NOT NULL,
    invoice_uuid           VARCHAR(40)  NOT NULL,   -- matches invoices.uuid width
    company_uuid           CHAR(36)     NOT NULL,
    economics_draft_number INT          NULL,       -- DIAGNOSTIC ONLY — an ordinal, not an ID (F-3)
    draft_invoice_number   INT          NOT NULL,   -- resolved ONCE at reserve, then FROZEN
    send_by                VARCHAR(10)  NULL,       -- null | 'ean' | 'Email'
    idempotency_key        VARCHAR(100) NOT NULL,   -- "book-" + invoice_uuid; deliberately NOT unique,
                                                    -- and deliberately carries no per-attempt suffix
                                                    -- in this deploy (mixed-version safety, see S1)
    payload_json           TEXT         NOT NULL,
    payload_hash           CHAR(64)     NOT NULL,
    state                  VARCHAR(24)  NOT NULL,   -- PENDING|BOOKED|FAILED|NEEDS_RECONCILIATION|SUPERSEDED
    booked_number          INT          NULL,
    attempt_count          INT          NOT NULL DEFAULT 0,
    last_error             TEXT         NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    posted_at              DATETIME(6)  NULL,       -- when the POST was ISSUED — the TTL clock
    completed_at           DATETIME(6)  NULL,
    CONSTRAINT pk_invoice_booking_attempt PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- The natural key is (invoice_uuid, draft_invoice_number) — the value actually
-- sent to the vendor. NOT economics_draft_number, which is a non-unique ordinal
-- that production reuses across unrelated invoices and days (finding F-3).
CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_booking_attempt_draft
    ON invoice_booking_attempt (invoice_uuid, draft_invoice_number);

CREATE INDEX IF NOT EXISTS idx_invoice_booking_attempt_state
    ON invoice_booking_attempt (state, created_at);

CREATE INDEX IF NOT EXISTS idx_invoice_booking_attempt_invoice
    ON invoice_booking_attempt (invoice_uuid, created_at);
