-- ===================================================================
-- V556: signing_cases — take the legacy SharePoint-uploaded cases out
--       of the S3 archival catch-up sweep
-- ===================================================================
-- Why (SharePoint deletion release): NextSignStatusSyncBatchlet's
-- archival catch-up sweep used to exclude cases whose signed PDFs the
-- retired auto-upload path had already put in SharePoint
-- (sharepoint_upload_status IN ('UPLOADED','PARTIAL_FAILURE') — "the
-- migration's job"). The SigningCase entity stops mapping the
-- sharepoint_* columns in this release, so that predicate can no longer
-- be expressed in the sweep's query. Without it every such case —
-- archive_status='PENDING' since V454 seeded it for all pre-existing
-- rows; 69 on production by V551's measurement — would be handed to
-- EmployeeSigningArchivalService, which re-downloads the signed PDFs
-- from NextSign and stores a SECOND copy next to the migrated one (the
-- uq_ed_signing idempotency key never matches a source=MIGRATION row
-- until the categorizer links it) and DMs the employee about a document
-- signed months or years ago.
--
-- Those cases' bytes are already in the S3 store: the completed
-- SharePoint->S3 migration copied them in. Mark them SKIPPED — the
-- existing terminal "nothing to archive" state from V454, no new enum
-- value — with a legible archive_error. SKIPPED is deliberately NOT
-- ARCHIVED: SigningCaseRepository.findCompletedNotArchived() still hands
-- SKIPPED cases to the categorizer's deterministic signing linkage,
-- which flips a case to ARCHIVED once its migrated files are linked.
--
-- The predicate mirrors the sweep's exactly (COMPLETED/COMPLETED/
-- PENDING) so only rows the sweep would actually have picked up change;
-- everything else keeps its status quo.
--
-- Two-step rule: sharepoint_upload_status still exists in this release
-- (it is dropped by a later one once no running task references it), so
-- it is safe to read here. The UPDATE is guarded on the column's
-- existence via information_schema + PREPARE/EXECUTE, so a
-- repair-at-start re-run after that drop is a no-op instead of a boot
-- failure. The archival service carries a matching runtime guard for
-- any case this backfill does not reach (a row reset to PENDING later,
-- a FAILED legacy upload whose bytes were migrated anyway).
--
-- Idempotent: state-guarded (archive_status='PENDING'), re-runnable.
--
-- Reserved words checked (MariaDB 10.x): archive_status, archive_error,
-- processing_status, status, sharepoint_upload_status — none reserved
-- (cf. the V534 LINES incident).
-- ===================================================================

SET @skip_legacy := (
    SELECT IF(COUNT(*) > 0,
              'UPDATE signing_cases
                  SET archive_status = ''SKIPPED'',
                      archive_error  = ''Legacy SharePoint upload; signed documents already migrated to the S3 store (linked by the categorizer)''
                WHERE archive_status = ''PENDING''
                  AND processing_status = ''COMPLETED''
                  AND status = ''COMPLETED''
                  AND sharepoint_upload_status IN (''UPLOADED'', ''PARTIAL_FAILURE'')',
              'SELECT 1')
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'signing_cases'
       AND COLUMN_NAME = 'sharepoint_upload_status'
);
PREPARE stmt FROM @skip_legacy;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
