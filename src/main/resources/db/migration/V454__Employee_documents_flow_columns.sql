-- ===================================================================
-- V454: Employee Documents — flow-rewiring columns (Phase 1c)
-- ===================================================================
-- Feature: Employee Documents S3-only storage
--          (spec docs/superpowers/specs/2026-07-24-employee-documents-s3-only-design.md
--          §6.2 "New columns" + §6.5)
--
-- Purpose:
--   1. signing_cases.archive_status / archive_error — the 3-state S3
--      archival tracking that replaces the 4-state SharePoint upload
--      tracking (PENDING = not yet archived / retrying; ARCHIVED =
--      every document of the case has an employee_documents row;
--      SKIPPED = terminal NextSign status or expired envelope, nothing
--      to archive). The legacy sharepoint_upload_* columns stay
--      untouched until the deletion release (two-step drop rule).
--   2. signing_cases.template_uuid — which document_templates row the
--      case was created from (nullable; template-less cases stay null).
--      Implementation finding: the spec maps archival categories from
--      the template's TemplateCategory (§6.5.1), but signing_cases
--      never persisted the template linkage — the category would be
--      unmappable at archival time. Set by saveMinimalCase for
--      template-based cases from this release on; historic rows stay
--      null and archive as OTHER (or are linked by migration M4).
--   3. recruitment_candidates.promotion_status — the thin 3-state
--      remnant replacing the 5-state sharepoint_move_status once the
--      promotion writer flips to S3→S3 (§6.5.3). NULL = candidate
--      handled by the legacy SharePoint pipeline (or not converted).
--   4. onboarding_upload_submissions.employee_document_uuid — the
--      user-flow submission's link into the new store once the
--      onboarding writer flips to S3 (§6.5.4). Candidate flow keeps
--      s3_file_uuid unchanged.
--   5. candidate_dossier_revisions.signed_pdfs_snapshot — JSON array
--      of {filename, fileUuid} for signed PDFs archived to candidate
--      staging at completion (§6.5.2 — closes the NextSign durability
--      gap; mirror of generated_pdfs_snapshot).
--
-- Idempotency: ADD COLUMN IF NOT EXISTS throughout; the backfill
--   UPDATE is state-guarded and re-runnable.
--
-- Author: Claude Code
-- Date:   2026-07-24
-- Rollback: columns are additive and unread while the writer toggles
--   are OFF. Full removal:
--     ALTER TABLE signing_cases DROP COLUMN IF EXISTS archive_status,
--       DROP COLUMN IF EXISTS archive_error, DROP COLUMN IF EXISTS template_uuid;
--     ALTER TABLE recruitment_candidates DROP COLUMN IF EXISTS promotion_status;
--     ALTER TABLE onboarding_upload_submissions DROP COLUMN IF EXISTS employee_document_uuid;
--     ALTER TABLE candidate_dossier_revisions DROP COLUMN IF EXISTS signed_pdfs_snapshot;
-- ===================================================================

-- 1+2. Signing-case archival tracking + template linkage.
ALTER TABLE signing_cases
    ADD COLUMN IF NOT EXISTS archive_status ENUM('PENDING','ARCHIVED','SKIPPED')
        NOT NULL DEFAULT 'PENDING'
        COMMENT 'S3 archival state (spec §6.5.1); replaces sharepoint_upload_status at the deletion release',
    ADD COLUMN IF NOT EXISTS archive_error TEXT NULL
        COMMENT 'Last S3 archival error; cleared on success',
    ADD COLUMN IF NOT EXISTS template_uuid VARCHAR(36) NULL
        COMMENT 'document_templates.uuid the case was created from (null = template-less); drives archival category mapping';

-- Backfill: cases that are terminally non-uploadable can never produce
-- a signed document — mirror processing_status=SKIPPED so the archival
-- sweep never selects them.
UPDATE signing_cases
   SET archive_status = 'SKIPPED'
 WHERE processing_status = 'SKIPPED'
   AND archive_status = 'PENDING';

-- 3. Conversion promotion remnant (S3→S3 move re-drive state).
ALTER TABLE recruitment_candidates
    ADD COLUMN IF NOT EXISTS promotion_status ENUM('PENDING','COMPLETED','FAILED') NULL
        COMMENT 'S3→S3 promotion state (spec §6.5.3); NULL = legacy SharePoint pipeline / not converted';

-- 4. Onboarding user-flow link into the new store. The V327
--    chk_ous_storage constraint requires s3_file_uuid for S3 rows; the
--    employee-documents path stores user-flow uploads via
--    employee_documents instead of the files table, so the constraint
--    gains a third arm. DROP+ADD is the only portable way to alter a
--    CHECK; both statements are IF-EXISTS-guarded for repair-at-start.
ALTER TABLE onboarding_upload_submissions
    ADD COLUMN IF NOT EXISTS employee_document_uuid VARCHAR(36) NULL
        COMMENT 'employee_documents.uuid for user-flow uploads stored via the S3 path (spec §6.5.4)';

ALTER TABLE onboarding_upload_submissions
    DROP CONSTRAINT IF EXISTS chk_ous_storage;

ALTER TABLE onboarding_upload_submissions
    ADD CONSTRAINT chk_ous_storage CHECK (
        (storage_target = 'S3'         AND s3_file_uuid IS NOT NULL) OR
        (storage_target = 'S3'         AND employee_document_uuid IS NOT NULL) OR
        (storage_target = 'SHAREPOINT' AND sharepoint_drive_item_id IS NOT NULL)
    );

-- 5. Signed-PDF snapshot on the dossier revision (candidate staging).
ALTER TABLE candidate_dossier_revisions
    ADD COLUMN IF NOT EXISTS signed_pdfs_snapshot JSON NULL
        COMMENT 'JSON [{filename,fileUuid}] — signed PDFs archived to candidate staging at completion (spec §6.5.2)';
