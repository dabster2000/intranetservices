-- ===================================================================
-- V548: Agreement backfill (Phase 4 of the template-clauses
--       & agreement-registry plan)
-- ===================================================================
-- Feature: Template Clauses & Agreement Registry, Phase 4 — AI-assisted
--          backfill of the registry from active employees' existing
--          signed SharePoint documents; HR confirms every record
--          (spec docs/design/template-clauses-and-agreement-registry-spec.md §4.8/§10,
--           plan docs/superpowers/plans/2026-08-31-template-clauses-implementation-plan.md)
-- Domain:  agreementservice — writes employee_agreements (V547) on confirm
--
-- Purpose:
--   1. agreement_backfill_runs  — one row per corpus walk (admin-
--      triggered, single-flight); live counters drive the console.
--   2. agreement_backfill_items — one row per discovered PDF document,
--      carrying the AI proposal(s) and the human review state. Nothing
--      enters employee_agreements without a confirm (D8).
--   3. Seed the documents.agreements.backfill.enabled flag (default OFF).
--
-- Idempotency of the walk: doc_sha256 is computed from the downloaded
--   bytes. UNIQUE is (user_uuid, doc_sha256), deliberately narrower than
--   the spec's "UNIQUE per corpus": an identical circular filed in two
--   employees' folders must produce an item for EACH employee — a global
--   UNIQUE would silently attach the document to whichever employee was
--   walked first and hide it for the other. Same-employee duplicates
--   (moved/copied files) still collapse to one item.
--
-- proposal_json holds an ARRAY of proposals — §10.2 says one extraction
--   call proposes "zero-or-more records" per document. Confirmed rows'
--   uuids land in created_agreements_json (array, one per record).
--
-- Reserved-word check (MariaDB 10.x): uuid, status, dry_run, started_by,
--   started_at, finished_at, corpus_summary, employees_total,
--   folders_total, folders_walked, files_seen, files_skipped,
--   documents_new, proposals_created, errors_count, error_message,
--   run_uuid, user_uuid, site_url, drive_id, sharepoint_item_id, e_tag,
--   web_url, file_name, file_size, doc_sha256, proposal_json,
--   extraction_note, reviewed_by, reviewed_at, created_agreements_json,
--   created_at, updated_at — none reserved (the V534 LINES lesson).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   CREATE TABLE IF NOT EXISTS, INSERT IGNORE for every seed.

-- 1. Runs --------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agreement_backfill_runs (
    uuid              VARCHAR(36)  NOT NULL,
    status            VARCHAR(20)  NOT NULL COMMENT 'RUNNING / COMPLETED / FAILED',
    dry_run           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Enumerate + count only; no downloads, no AI calls, no items',
    started_by        VARCHAR(36)  NULL COMMENT 'Acting HR/ADMIN user (X-Requested-By)',
    started_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       TIMESTAMP    NULL,
    corpus_summary    VARCHAR(500) NULL COMMENT 'Human-readable corpus description',
    employees_total   INT          NOT NULL DEFAULT 0 COMMENT 'Active employees in the corpus',
    folders_total     INT          NOT NULL DEFAULT 0 COMMENT 'Mapped SharePoint folders for those employees',
    folders_walked    INT          NOT NULL DEFAULT 0,
    files_seen        INT          NOT NULL DEFAULT 0 COMMENT 'Files enumerated (the by-files walk, never folder aggregates)',
    files_skipped     INT          NOT NULL DEFAULT 0 COMMENT 'Non-PDF / temp / zero-byte / excluded-subfolder files',
    documents_new     INT          NOT NULL DEFAULT 0 COMMENT 'PDFs not already itemized for the employee',
    proposals_created INT          NOT NULL DEFAULT 0 COMMENT 'Agreement proposals across all new items',
    errors_count      INT          NOT NULL DEFAULT 0,
    error_message     TEXT         NULL COMMENT 'Terminal failure detail when status=FAILED',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_abr_status (status, started_at)
);

-- 2. Items -------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agreement_backfill_items (
    uuid                    VARCHAR(36)   NOT NULL,
    run_uuid                VARCHAR(36)   NOT NULL COMMENT 'The run that discovered the document',
    user_uuid               VARCHAR(36)   NOT NULL COMMENT 'Employee the folder belongs to (backfill corpus is user-keyed only)',
    site_url                VARCHAR(500)  NOT NULL,
    drive_id                VARCHAR(255)  NOT NULL COMMENT 'Graph drive id — needed to re-download for the PDF preview',
    sharepoint_item_id      VARCHAR(255)  NOT NULL COMMENT 'Graph drive item id (stable across moves)',
    e_tag                   VARCHAR(255)  NULL COMMENT 'Item eTag at discovery; unchanged id+eTag skips re-download on re-runs',
    web_url                 VARCHAR(1000) NULL COMMENT 'SharePoint link; becomes employee_agreements.document_url on confirm',
    file_name               VARCHAR(500)  NOT NULL,
    file_size               BIGINT        NOT NULL DEFAULT 0,
    doc_sha256              CHAR(64)      NOT NULL COMMENT 'SHA-256 of the downloaded bytes — idempotent re-runs',
    status                  VARCHAR(20)   NOT NULL COMMENT 'PROPOSED / CONFIRMED / EDITED / REJECTED / NO_PROPOSALS / FAILED',
    proposal_json           JSON          NULL COMMENT 'Array of {agreementType,title,summary,amount,currency,validFrom,validTo,effectiveDate,verbatimQuote,confidence}',
    extraction_note         VARCHAR(500)  NULL COMMENT 'Diagnostics: vision fallback used, extraction error, …',
    reviewed_by             VARCHAR(36)   NULL,
    reviewed_at             TIMESTAMP     NULL,
    created_agreements_json JSON          NULL COMMENT 'employee_agreements uuids written by the confirm',
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_abi_user_sha (user_uuid, doc_sha256),
    KEY idx_abi_run (run_uuid),
    KEY idx_abi_user_status (user_uuid, status),
    KEY idx_abi_status (status),
    KEY idx_abi_item (sharepoint_item_id),
    CONSTRAINT fk_abi_run FOREIGN KEY (run_uuid) REFERENCES agreement_backfill_runs (uuid)
);

-- 3. Feature flag (default OFF — the backfill console ships dark) ------------

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('documents.agreements.backfill.enabled', 'false', 'documents');
