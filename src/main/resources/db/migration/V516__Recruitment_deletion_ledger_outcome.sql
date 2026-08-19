-- ===================================================================
-- V516: Recruitment ATS — hard-delete ledger: outcome + external redaction
-- ===================================================================
-- Feature: ADMIN-only hard delete of a candidate created by mistake
-- Domain:  recruitmentservice
--
-- WHY THIS EXISTS AS A SEPARATE MIGRATION
--   V515 created recruitment_candidate_deletions on the assumption that
--   the ledger row is INSERTed inside the cascade transaction, at the end
--   of a delete that already worked. A security review found the hole in
--   that: Microsoft Graph event cancellation and Slack card redaction are
--   irreversible, they run BEFORE the cascade (they read rows the cascade
--   deletes, and remote I/O may not happen inside a recruitment
--   transaction — the 2026-08-11 reactor deadlock rule), and the cascade
--   can still roll back. When it did, the candidate survived with their
--   Outlook invitations cancelled and their Slack cards rewritten, and
--   NOTHING recorded it: the ledger INSERT rolled back with everything
--   else.
--
--   V515 is not edited in place because it may already be applied in a
--   developer database, where changing an applied migration's checksum
--   breaks Flyway validation on the next boot.
--
-- WHAT CHANGES
--   The ledger row is now opened and COMMITTED before the first Graph or
--   Slack call, and what those calls actually did is committed before the
--   destructive transaction opens. So the row's lifecycle needs to be
--   visible:
--
--   outcome            ATTEMPTED         row opened. Either nothing irreversible has
--                                        happened yet, or it happened and could not be
--                                        written down - the one window two-phase commit
--                                        would be needed to close. The service logs the
--                                        whole record at ERROR in that case and refuses
--                                        to run the cascade, so the candidate survives.
--                      EXTERNAL_REDACTED Graph/Slack done and recorded; the
--                                        cascade has not committed
--                      COMPLETED         the cascade committed — the ONLY
--                                        value that means the candidate is gone
--                      ROLLED_BACK       the cascade threw AFTER the external
--                                        redaction: the candidate still exists
--                                        and external_redaction says what was
--                                        already done out there
--
--   external_redaction what was irreversibly changed OUTSIDE the database:
--                      the cancelled Outlook events (as
--                      "<interview uuid>#internal" / "#candidate") and the
--                      rewritten Slack root cards and discussion roots.
--                      Ids and uuids only — the V515 rule that this table
--                      carries no candidate identifier beyond the (now
--                      meaningless) uuid is unchanged and applies here too.
--
--   deleted_counts stays NOT NULL and is now written twice: '{}' when the
--   row is opened, then the real per-table counts in the same transaction
--   as the deletes. It is no longer immutable.
--
-- OPERATOR NOTE
--   SELECT uuid, candidate_uuid, actor_uuid, deleted_at, outcome,
--          external_redaction
--     FROM recruitment_candidate_deletions
--    WHERE outcome <> 'COMPLETED';
--   Every row that comes back is a delete that touched Slack or Outlook
--   without finishing. Each one needs a human.
--
-- Backfill: existing rows all come from the old flow, where the row only
--   ever existed because the cascade committed — so 'COMPLETED' is the
--   correct value for every one of them, and it is also the column
--   default's opposite on purpose (a NEW row must never default to
--   COMPLETED; the application sets ATTEMPTED explicitly).
--
-- Idempotency: guarded with INFORMATION_SCHEMA checks, so a re-run after a
--   partial apply is a no-op. No procedure is touched — V515 already put
--   this table on the prod -> staging sync exclusion list.
--
-- Author: Claude Code
-- Date:   2026-08-19
-- Rollback:
--   ALTER TABLE recruitment_candidate_deletions
--       DROP COLUMN outcome, DROP COLUMN external_redaction;
--   (the ledger rows themselves must never be dropped — they are the only
--    record of the deletes they hold)
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. outcome
-- -------------------------------------------------------------------
SET @has_outcome := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'recruitment_candidate_deletions'
      AND COLUMN_NAME = 'outcome');

SET @sql := IF(@has_outcome = 0,
    'ALTER TABLE recruitment_candidate_deletions
        ADD COLUMN outcome VARCHAR(32) NOT NULL DEFAULT ''ATTEMPTED''
            COMMENT ''ATTEMPTED | EXTERNAL_REDACTED | COMPLETED | ROLLED_BACK. Only COMPLETED means the candidate row is actually gone.''
            AFTER reason',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Rows written by the pre-V516 flow only exist because the cascade
-- committed. Runs once: after this, no row is left on the default.
UPDATE recruitment_candidate_deletions
   SET outcome = 'COMPLETED'
 WHERE outcome = 'ATTEMPTED'
   AND deleted_counts IS NOT NULL
   AND JSON_LENGTH(deleted_counts) > 0;

-- -------------------------------------------------------------------
-- 2. external_redaction
-- -------------------------------------------------------------------
SET @has_external := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'recruitment_candidate_deletions'
      AND COLUMN_NAME = 'external_redaction');

SET @sql := IF(@has_external = 0,
    'ALTER TABLE recruitment_candidate_deletions
        ADD COLUMN external_redaction JSON NULL
            COMMENT ''What was irreversibly changed outside the DB before the cascade ran: cancelled Outlook event refs and rewritten Slack roots. Ids only, never a name.''
            AFTER outcome',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------
-- 3. deleted_counts is no longer immutable (it is '{}' until the cascade
--    runs). The column definition itself does not change — this comment
--    is the record that the application now UPDATEs it.
-- -------------------------------------------------------------------
ALTER TABLE recruitment_candidate_deletions
    MODIFY COLUMN deleted_counts JSON NOT NULL
        COMMENT 'Per-table deleted row counts. ''{}'' between opening the row and the cascade commit. Structural only, never PII.';
