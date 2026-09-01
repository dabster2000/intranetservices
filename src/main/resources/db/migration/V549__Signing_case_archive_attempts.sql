-- ===================================================================
-- V549: signing_cases.archive_attempts — bound the archival retry loop
-- ===================================================================
-- Feature: Employee documents S3-only store (spec §14 open item)
--
-- Purpose:
--   NextSignStatusSyncBatchlet.runArchivalCatchupSweep selects purely on
--   archive_status='PENDING'. On failure the archival service records
--   archive_error but deliberately leaves the row PENDING, so a case whose
--   NextSign envelope has expired is re-selected on every 5-minute pass
--   forever. The sweep is bounded at 25 cases per pass and ordered by
--   created_at ASC, so a handful of permanently-stuck oldest cases would
--   starve every later case out of the queue entirely.
--
--   This column counts failed archival attempts. Once the count reaches the
--   compiled cap the archival service moves the case to the existing
--   terminal state archive_status='SKIPPED' (spec §14 explicitly proposes
--   "a SKIPPED-on-permanent-404 rule or retry counter"), keeping the last
--   archive_error so the reason survives. No new enum value is introduced —
--   archive_status stays ENUM('PENDING','ARCHIVED','SKIPPED') from V454.
--
-- Blast radius: none today. Measured on production 2026-09-01 the
--   sweep-eligible population is ZERO (all 85 PENDING cases are excluded —
--   69 by the sharepoint_upload_status='UPLOADED' filter, the rest by not
--   being COMPLETED) and every PENDING case has archive_error IS NULL,
--   confirming the sweep is not even attempting them. This is robustness
--   against a future expired-envelope case, not a live incident.
--
-- Idempotency: ADD COLUMN IF NOT EXISTS; additive only (no drops, per the
--   two-step drop rule — old and new tasks run side by side during an ECS
--   Express canary, and a NOT NULL DEFAULT 0 column is invisible to the
--   old task).
--
-- Author: Claude Code
-- ===================================================================

ALTER TABLE signing_cases
    ADD COLUMN IF NOT EXISTS archive_attempts INT NOT NULL DEFAULT 0
        COMMENT 'Failed S3 archival attempts; at the compiled cap the case goes terminal as SKIPPED';
