-- ===================================================================
-- V554: Agreement backfill corpus moves to the S3 employee-documents
--       store (template-clauses Phase 4 rework)
-- ===================================================================
-- The V549 walker enumerated SharePoint via Graph, per the spec as
-- written 2026-08-31. The SharePoint->S3 migration has since completed
-- and employee_documents (S3) is the operative store: new documents
-- (SIGNING / MANUAL_HR / ONBOARDING) land ONLY there, and rows are
-- already categorized and sha256-hashed. The corpus walker therefore
-- now enumerates employee_documents; items point at the source row.
--
-- The SharePoint columns stay for the items created by the V549-era
-- runs (their PDF preview still streams from Graph) but become
-- NULLable — S3-sourced items do not have them.
--
-- Reserved-word check (MariaDB 10.x): employee_document_uuid, site_url,
-- drive_id, sharepoint_item_id — none reserved (the V534 LINES lesson).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
-- IF NOT EXISTS on ADD COLUMN / ADD KEY; MODIFY is naturally idempotent.

ALTER TABLE agreement_backfill_items
    ADD COLUMN IF NOT EXISTS employee_document_uuid VARCHAR(36) NULL
        COMMENT 'employee_documents.uuid the item was extracted from (S3 corpus); NULL on legacy SharePoint-walk items'
        AFTER user_uuid;

ALTER TABLE agreement_backfill_items
    MODIFY site_url VARCHAR(500) NULL,
    MODIFY drive_id VARCHAR(255) NULL COMMENT 'Legacy Graph pointer (V549-era items); NULL on S3-sourced items',
    MODIFY sharepoint_item_id VARCHAR(255) NULL COMMENT 'Legacy Graph pointer (V549-era items); NULL on S3-sourced items';

ALTER TABLE agreement_backfill_items
    ADD KEY IF NOT EXISTS idx_abi_empdoc (employee_document_uuid);
