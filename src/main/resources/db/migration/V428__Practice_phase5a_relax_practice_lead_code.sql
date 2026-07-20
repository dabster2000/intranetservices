-- =============================================================================
-- V428: Practice Part 2, Phase 5A — relax practice_lead.practice_code.
--
-- The expand half of an expand/contract pair. Phase 5A re-keys the application
-- to uuid-only persistence: PracticeLead.practiceCode became a registry-derived
-- @Formula, and Hibernate never emits a @Formula field in an INSERT. But
-- practice_lead.practice_code is NOT NULL with no default — the only one of the
-- six legacy practice code columns that is — so under STRICT_TRANS_TABLES the
-- 5A INSERT fails outright:
--
--     INSERT INTO practice_lead (uuid, practice_uuid, useruuid, startdate, ...)
--     → ERROR 1364 (HY000): Field 'practice_code' doesn't have a default value
--
-- i.e. POST /practices/{id}/leads would 500 for the entire 5A→5B window.
-- Relaxing the column to NULL is compatible in BOTH directions across the ECS
-- canary: the draining pre-5A task still writes a real code (unaffected), and
-- the incoming 5A task omits the column entirely (now legal, lands NULL).
--
-- NULL rather than DEFAULT '': an empty string would fabricate a fake legacy
-- code in a column that is about to be deleted; NULL is the honest "this row
-- was written by the uuid-only writer" marker. Nothing reads the raw column
-- under 5A — PracticeLead resolves the code through the registry @Formula —
-- so a NULL here is invisible to every reader.
--
-- This is the house two-step discipline for drop-column migrations under ECS
-- Express canary (stop writing first, drop later): 5A + this flip is the
-- "stop writing" step; V429 is the drop.
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §4.1, §4.4 wave 4, §1.6.K.
-- Plan:  docs/superpowers/plans/2026-07-19-practice-part2-phased-rollout.md
--        Phase 5 (5A).
--
-- Idempotency: MODIFY COLUMN to the same definition is a no-op on re-run
-- (repair-at-start, and the nightly prod→staging refresh strip). It is also
-- guarded on the column still EXISTING, because V429 drops it — without the
-- guard, any replay of this file on a V429-migrated database would abort boot
-- with ER_BAD_FIELD (verified). Idiom mirrors V425 §3b.
--
-- Rollback: re-assert NOT NULL — safe only while no uuid-only writer has run
-- (any NULL row must be backfilled from the registry via practice_uuid first).
-- practice_lead is empty on every environment at authoring time.
-- =============================================================================

SET @practice_lead_has_code := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'practice_lead'
      AND COLUMN_NAME = 'practice_code'
);
SET @practice_lead_relax := IF(@practice_lead_has_code > 0,
    'ALTER TABLE practice_lead MODIFY COLUMN practice_code VARCHAR(10) NULL',
    'DO 0');
PREPARE practice_lead_relax_stmt FROM @practice_lead_relax;
EXECUTE practice_lead_relax_stmt;
DEALLOCATE PREPARE practice_lead_relax_stmt;
