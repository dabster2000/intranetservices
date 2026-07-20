-- =============================================================================
-- V426: Practice Part 2, Phase 2 — the application becomes the sole writer.
--
-- Drops the entire V407/V424 trigger family. From this migration on,
-- PracticeSyncService (shipped in the SAME release — Flyway runs at boot before
-- traffic, so no request ever sees a trigger-less DB without the service) is the
-- only writer of user.practice + user.practice_uuid and user_practice_history,
-- with provenance (source TEAM_SYNC/MANUAL, source_team_uuid, updated_by).
--
-- BY DESIGN from here: a raw SQL `UPDATE user SET practice = ...` writes NO
-- history and does NOT maintain the practice_uuid twin — the daily
-- reconciliation tick re-derives every user from their current MEMBER role and
-- repairs both within a day (a raw `DELETE FROM user` likewise leaves its open
-- history rows to the tick's orphan-close pass). This retires the known
-- operational risk that the nightly prod→staging refresh strips triggers
-- (spec §4.3): there is no trigger left to strip.
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §4.2 (derivation + provenance), §4.3 (application-only writer), §1.6.H.
-- Plan:  docs/superpowers/plans/2026-07-19-practice-part2-phased-rollout.md
--        Phase 2.
--
-- Idempotency (mandatory — this file re-runs via repair-at-start and after the
-- nightly refresh strip): DROP TRIGGER IF EXISTS throughout; the twin resyncs
-- are keyed on NULL-safe mismatch (<=>) and DERIVE the uuid from the code
-- column via a registry join, so they converge even when practice.uuid was
-- re-minted by a V424 re-run.
--
-- Rollback: recreate the trigger family from V424 §4 (the definition of record).
-- =============================================================================


-- 1) Drop all five triggers — the uuid mirror pair on `user` and the three
--    history writers. The application reproduces their exact semantics
--    (half-open intervals, <=> change detection, same-day collapse, twin
--    maintenance) in PracticeSyncService.
DROP TRIGGER IF EXISTS trg_user_practice_uuid_before_insert;
DROP TRIGGER IF EXISTS trg_user_practice_uuid_before_update;
DROP TRIGGER IF EXISTS trg_user_practice_history_after_insert;
DROP TRIGGER IF EXISTS trg_user_practice_history_after_update;
DROP TRIGGER IF EXISTS trg_user_practice_history_after_delete;


-- 2) Twin convergence resync — code column is the source of truth. -------------
--    Defensive, normally 0 rows: repairs any code↔uuid drift accumulated while
--    the twins had no maintainer (team.practice_uuid between V424 and this
--    release; any uuid re-mint edge on a refreshed environment). NULL-safe:
--    unregistered codes resolve to NULL uuid, matching the trigger-mirror
--    semantics. user_practice_history rows with codes no longer in the registry
--    (historical values) are left untouched by the inner-join form below.

UPDATE `user` u
    LEFT JOIN practice p ON p.code = u.practice
SET u.practice_uuid = p.uuid
WHERE NOT (u.practice_uuid <=> p.uuid);

UPDATE team t
    LEFT JOIN practice p ON p.code = t.practice_code
SET t.practice_uuid = p.uuid
WHERE NOT (t.practice_uuid <=> p.uuid);

UPDATE user_practice_history h
    JOIN practice p ON p.code = h.practice
SET h.practice_uuid = p.uuid
WHERE NOT (h.practice_uuid <=> p.uuid);
