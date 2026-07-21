-- =============================================================================
-- V432: Retire practice.display_code and practice.type — the final schema step
-- of the Practice Part 2 programme.
--
-- The drop half of the two-release retirement that V431 started: the V431
-- release removed both fields from the Practice entity (displayCode and type
-- are served as derived getters — displayCode ≡ code since the V429 fold,
-- type ≡ 'PRACTICE' since V429 deleted the UD row, the only SEGMENT) and
-- relaxed display_code so the unmapped writer could insert. Nothing has read
-- or written either column since; this migration removes them and the unique
-- index that covered display_code. After it, the practice table matches spec
-- §4.1's end state exactly: uuid PK, UNIQUE code, name, active, sort_order,
-- audit columns — the schema finally agrees with the behaviour.
--
--   ⚠ V432 MUST NEVER SHIP UNTIL THE V431 RELEASE HAS FULLY DRAINED. Any
--     pre-V431 task (origin/master before that promotion) still maps both
--     columns on the Practice entity, so every registry load — GET /practices,
--     every practice resolution — would 500 with ER_BAD_FIELD the moment this
--     file runs. Same rule that split 5A/5B, applied one level deeper; gate on
--     the live task-def image SHA, not on deployment status flags.
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §1.6.K (Deviation D2 and its correction block), §4.1 (target model).
-- Prior: V429 §6 folded code := display_code and retained both columns for the
--        draining 5A task; V431 relaxed display_code for the derived writer.
--
-- Verified state at authoring time (local twservices4-staging @ V431):
--   * no view, routine, trigger, event or FK references either column — the
--     information_schema sweep of all 10 practice-referencing views and all 4
--     routines found only uuid, code, name, sort_order in use; the 6 FKs all
--     reference practice(uuid);
--   * uq_practice_display_code (UNIQUE, single column display_code) is the only
--     index touching a dropped column — V418 created it, nothing else did;
--   * 5 registry rows (PM/IA/BU/TECH/CYB), code = display_code on every row
--     stored before V431 (rows created after V431 carry NULL — equally dead),
--     every row type='PRACTICE';
--   * the V431 backend serves GET /practices, the practice-filtered analytics
--     and the practice admin mutations without touching either column
--     (golden-master verified against the pre-V431 baseline).
--
-- Idempotency (mandatory — repair-at-start re-runs this file, and the nightly
-- prod→staging refresh resurrects the columns on staging until this migration
-- reaches prod): all three statements use native IF EXISTS shorthands, so a
-- replay on the post-drop schema is a clean no-op. The index is dropped
-- explicitly and FIRST — dropping the column alone would also remove the
-- emptied index, but relying on that side effect would leave the intent
-- invisible and the replay order unprovable.
--
-- ECS canary note: during cutover the DRAINING V431 task neither reads nor
-- writes either column (that was the whole point of the V431 release), so it
-- observes nothing. The incoming task is identical code; this is a
-- migration-only release precisely so no code change races the schema change.
--
-- Rollback: forward-only per repo discipline — and the first practice
-- migration where the dropped data is not reconstructable (display_code's
-- pre-V429 legacy values survive only in git history at V418/V419). Nothing
-- can need it: code has been the canonical display value since V429, and the
-- V431 task-def is a proven-working configuration on the post-V432 schema.
-- =============================================================================

ALTER TABLE practice
    DROP INDEX IF EXISTS uq_practice_display_code;

ALTER TABLE practice
    DROP COLUMN IF EXISTS display_code;

ALTER TABLE practice
    DROP COLUMN IF EXISTS type;
