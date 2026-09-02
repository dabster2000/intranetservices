-- ===================================================================
-- V557: Drop the SharePoint columns, tables and settings that the
--       deletion release (V555/V556 + feature/sharepoint-deletion-release)
--       stopped using.
-- ===================================================================
-- SECOND step of the two-step drop rule: the previous release removed
-- every read and write of these objects from the code, so an old task
-- that is still running while this release canaries never touches them.
-- Nothing here is read by any code path any more; the SharePoint→S3
-- migration is complete and employee_documents (S3) is the only store.
--
-- Drop order matters where an index or CHECK sits on the column:
-- index/constraint first, then the column. Whole-table drops take their
-- own indexes, unique keys and FKs with them (sharepoint_locations owns
-- fk_sharepoint_locations_company → companies; nothing references the
-- three tables from elsewhere — verified via information_schema on
-- production 2026-09-01).
--
-- Reserved-word check (MariaDB 10.x): none of the identifiers below is
-- reserved (the V534 LINES lesson).
-- Idempotency: repair-at-start re-runs migrations across checkouts —
-- every statement uses IF EXISTS, so a re-run is a no-op.

-- ---- signing_cases: SharePoint auto-upload bookkeeping ---------------
ALTER TABLE signing_cases DROP INDEX IF EXISTS idx_sc_sharepoint_location;
ALTER TABLE signing_cases DROP INDEX IF EXISTS idx_sc_upload_status;
ALTER TABLE signing_cases
    DROP COLUMN IF EXISTS sharepoint_location_uuid,
    DROP COLUMN IF EXISTS sharepoint_upload_status,
    DROP COLUMN IF EXISTS sharepoint_upload_error,
    DROP COLUMN IF EXISTS sharepoint_file_url;

-- ---- recruitment_candidates: retired copy-then-delete pipeline -------
ALTER TABLE recruitment_candidates DROP CONSTRAINT IF EXISTS chk_rc_sharepoint_move_status_enum;
ALTER TABLE recruitment_candidates
    DROP COLUMN IF EXISTS sharepoint_folder_path,
    DROP COLUMN IF EXISTS sharepoint_move_status;

-- ---- onboarding_upload_submissions: single S3 path since V555 --------
-- (chk_ous_storage was dropped by V555; storage_target has had a default
-- since then so the deletion release could stop writing it.)
ALTER TABLE onboarding_upload_submissions DROP INDEX IF EXISTS idx_ous_s3_retention;
ALTER TABLE onboarding_upload_submissions
    DROP COLUMN IF EXISTS storage_target,
    DROP COLUMN IF EXISTS sharepoint_drive_item_id,
    DROP COLUMN IF EXISTS sharepoint_web_url,
    DROP COLUMN IF EXISTS s3_retention_until;

-- ---- document_templates: destination is no longer a concept ----------
ALTER TABLE document_templates DROP COLUMN IF EXISTS sharepoint_type;

-- ---- retention stamps of the retired S3 reaper ------------------------
ALTER TABLE candidate_dossier_revisions DROP COLUMN IF EXISTS s3_retention_until;
ALTER TABLE candidate_dossier_appendices DROP COLUMN IF EXISTS s3_retention_until;

-- ---- agreement_backfill_items: legacy Graph pointers (V549-era) -------
-- Previews of those items now resolve the S3 document by
-- (user_uuid, doc_sha256); the pointers carry nothing the code reads.
ALTER TABLE agreement_backfill_items DROP INDEX IF EXISTS idx_abi_item;
ALTER TABLE agreement_backfill_items
    DROP COLUMN IF EXISTS site_url,
    DROP COLUMN IF EXISTS drive_id,
    DROP COLUMN IF EXISTS sharepoint_item_id,
    DROP COLUMN IF EXISTS e_tag,
    DROP COLUMN IF EXISTS web_url;

-- ---- tables ------------------------------------------------------------
-- Migration working tables (V457): the copy finished, the tooling is gone.
DROP TABLE IF EXISTS sharepoint_migration_items;
DROP TABLE IF EXISTS sharepoint_migration_folders;
-- Per-company auto-upload destinations (V139/V323): no reader left.
DROP TABLE IF EXISTS sharepoint_locations;

-- ---- app_settings ------------------------------------------------------
-- The three writer toggles have no reader (writers are unconditionally
-- S3). employee_documents.migration.ai.enabled STAYS: it is the kill
-- switch of the AI categorizer that was kept as a maintenance job.
DELETE FROM app_settings WHERE setting_key IN (
    'employee_documents.writers.onboarding.enabled',
    'employee_documents.writers.signing.enabled',
    'employee_documents.writers.promotion.enabled'
);
