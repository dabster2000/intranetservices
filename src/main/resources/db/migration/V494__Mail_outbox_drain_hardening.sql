-- ===================================================================
-- V494: Mail outbox drain hardening — batch drain, attempt tracking,
--       poison-pill isolation
-- ===================================================================
-- Feature: Interview scheduling Method B, Phase 7.2 precondition (plan
--          2026-08-12; analysis in the 2026-07-18 recruitment findings
--          §Email-loose-ends). Shared-subsystem fix, ships on its own.
--
-- Purpose:
--   The queued send path (MailResource.sendMailJob, driven by the JBeret
--   mail-send job every 5 minutes) drained exactly ONE READY row per run
--   — 12 mails/hour — and a permanently failing mail stalled the whole
--   queue forever: the send threw, the transaction rolled back, the row
--   stayed READY and was picked first again next run. MailStatus.FAILED
--   has existed since the enum was written but nothing ever set it.
--
--   1. mail.created_at — the drain order. The table never had a
--      timestamp, so "oldest first" was previously unexpressible.
--      Pre-V494 rows all get the migration timestamp (they are SENT
--      rows almost without exception; ties break on uuid).
--   2. mail.attempt_count — send tries so far, counted BEFORE each try
--      so a JVM-killing send still burns an attempt.
--   3. mail.last_error — the last failure, truncated to 500 chars, for
--      operators; never rendered to users.
--   4. idx_mail_status_created — the drain query is
--      status = 'READY' ORDER BY created_at LIMIT 20 against a table
--      that is overwhelmingly SENT rows.
--
--   No status values change; every existing caller keeps writing READY
--   and the immediate paths still write SENT. Deploy order is safe:
--   pre-V494 code ignores the new columns entirely.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   every statement is IF NOT EXISTS.
--
-- Rollback (manual):
--     ALTER TABLE mail
--         DROP INDEX IF EXISTS idx_mail_status_created,
--         DROP COLUMN IF EXISTS last_error,
--         DROP COLUMN IF EXISTS attempt_count,
--         DROP COLUMN IF EXISTS created_at;
-- ===================================================================

ALTER TABLE mail
    ADD COLUMN IF NOT EXISTS created_at DATETIME(3) NOT NULL DEFAULT current_timestamp(3)
        COMMENT 'Queue-entry time; the drain order (oldest first). Pre-V494 rows carry the migration timestamp.',

    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0
        COMMENT 'Send tries so far, incremented and committed BEFORE each try so a crash mid-send still counts. At MailResource.MAX_ATTEMPTS the row is parked as FAILED instead of retried.',

    ADD COLUMN IF NOT EXISTS last_error VARCHAR(500) DEFAULT NULL
        COMMENT 'Last send failure (exception class + message, truncated). Operator-facing only.';

ALTER TABLE mail
    ADD INDEX IF NOT EXISTS idx_mail_status_created (status, created_at);
