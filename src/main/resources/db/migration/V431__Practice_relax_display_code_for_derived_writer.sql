-- =============================================================================
-- V431: Relax practice.display_code so the derived-field writer can run.
--
-- The stop-writing half of retiring practice.display_code and practice.type
-- (spec §1.6.K trailing micro-step, itself a two-release step): this release's
-- code removes both fields from the Practice entity and serves them as derived
-- getters (displayCode ≡ code since the V429 fold; type ≡ 'PRACTICE' since
-- V429 deleted the UD row, the only SEGMENT). The physical drop of both
-- columns plus uq_practice_display_code is V432, a migration-only release that
-- ships once this release has fully drained.
--
-- Why this ALTER must ship WITH the code change — the V428 lesson, recursed:
-- display_code is NOT NULL with no default, and Hibernate omits unmapped
-- columns from INSERTs, so the moment the entity stops mapping the field,
-- POST /practices dies with ERROR 1364 "Field 'display_code' doesn't have a
-- default value" — for the entire window until V432, not just at cutover.
-- Unlike V428's practice_lead case this endpoint has a live UI writer (the
-- V430 teams/practices admin create form). Deliberately NULL, not DEFAULT '':
-- an empty-string default would fabricate a fake display code in a column
-- about to die — and would collide with itself on the second insert, because
-- uq_practice_display_code is UNIQUE and MariaDB permits multiple NULLs but
-- not multiple ''. practice.type needs no relaxation: it already carries
-- DEFAULT 'PRACTICE', which is also the only value left in the domain.
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §1.6.K (Deviation D2 + its 2026-07-21 correction block).
-- Prior: V429 §6 folded code := display_code (values identical on every row);
--        V429's header records both columns as retained for the draining task.
--
-- Verified state at authoring time (local twservices4-staging @ V430; prod
-- confirmed at V429 with the same practice DDL):
--   * practice.display_code is varchar(10) NOT NULL with no default, covered
--     by UNIQUE uq_practice_display_code — the exact shape V418 created;
--   * practice.type is enum('PRACTICE','SEGMENT') NOT NULL DEFAULT 'PRACTICE';
--   * 5 registry rows (PM/IA/BU/TECH/CYB), code = display_code on every row,
--     every row type='PRACTICE';
--   * no view, routine, trigger, event or FK references either column
--     (information_schema sweep of all 10 practice-referencing views and all
--     4 routines: only uuid, code, name, sort_order are read).
--
-- Idempotency (mandatory — repair-at-start re-runs this file, and the nightly
-- prod→staging refresh strips its effect until V431 reaches prod): MODIFY
-- COLUMN to an already-NULL-able column is a natural no-op, but the statement
-- is guarded on the column still existing so the file stays replayable after
-- V432 drops it (same guard idiom as V429 §6).
--
-- ECS canary note: during this release's cutover the DRAINING pre-V431 task
-- still maps display_code and writes it explicitly on INSERT — a NULL-able
-- column accepts that unchanged. The incoming task never reads or writes it.
-- The only cross-window artifact: a practice created by the NEW task while an
-- OLD task still serves GET /practices would show displayCode null to that old
-- task; the window is minutes long and practice creation is a rare admin act.
--
-- Rollback: forward-only per repo discipline, but fully reversible in place —
-- no data is touched, and every row still carries its (unread) display_code
-- value until V432. Restoring NOT NULL would only require backfilling
-- display_code := code for rows created during the window.
-- =============================================================================

SET @practice_has_display_code := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'practice'
      AND COLUMN_NAME = 'display_code'
);
SET @practice_relax := IF(@practice_has_display_code > 0,
    'ALTER TABLE practice MODIFY COLUMN display_code VARCHAR(10) NULL',
    'DO 0');
PREPARE practice_relax_stmt FROM @practice_relax;
EXECUTE practice_relax_stmt;
DEALLOCATE PREPARE practice_relax_stmt;
