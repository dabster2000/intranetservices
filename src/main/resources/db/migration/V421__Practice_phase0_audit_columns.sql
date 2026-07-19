-- =============================================================================
-- V421: Audit parity for the practice registry tables (Part 2, Phase 0).
--
-- Practice, PracticeLead and TeamSetting adopt the house Auditable +
-- AuditEntityListener pattern (the standard 4-column audit trail established
-- by V88/V218, as used by salary_lump_sum, user_career_level, ...): durable
-- created/updated by+at on every row, replacing log-line-only auditing.
--
-- Spec: docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--       §4.3 (audit parity) / §1.6.E.
-- Plan: docs/superpowers/plans/2026-07-19-practice-part2-phased-rollout.md
--       Phase 0.
--
-- Existing state (verified on staging twservices4-staging, 2026-07-19):
--   practice      has created_at/updated_at (DB-managed) — actor columns missing
--   practice_lead has no audit columns at all
--   team_settings has updated_at/updated_by — creation columns missing
--     (the entity maps Auditable.modifiedBy onto the existing updated_by column)
--
-- Audit column semantics (V218):
--   created_at   DATETIME(6)   — set by AuditEntityListener on persist
--   updated_at   DATETIME(6)   — set by AuditEntityListener on every write
--   created_by   VARCHAR(255)  — user UUID from X-Requested-By (or 'system')
--   modified_by  VARCHAR(255)  — nullable; NULL on records never modified
--
-- Idempotent by construction: ADD COLUMN IF NOT EXISTS, backfills guarded by
-- IS NULL, MODIFY COLUMN re-runs are no-ops. (This file re-runs via
-- repair-at-start and after the nightly prod→staging refresh strips it.)
--
-- Rollback strategy: drop the added columns; only derived/defaulted audit
-- values are lost.
-- =============================================================================


-- 1) practice: add the two actor columns (timestamps already exist). ----------

ALTER TABLE practice
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN IF NOT EXISTS modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE practice
SET created_by = 'system'
WHERE created_by IS NULL;

ALTER TABLE practice
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL COMMENT 'User identifier who created the record';


-- 2) practice_lead: full 4-column audit trail. --------------------------------

ALTER TABLE practice_lead
    ADD COLUMN IF NOT EXISTS created_at  DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN IF NOT EXISTS updated_at  DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(255) NULL COMMENT 'User identifier who created the record',
    ADD COLUMN IF NOT EXISTS modified_by VARCHAR(255) NULL COMMENT 'User identifier who last modified the record';

UPDATE practice_lead
SET created_at = NOW(6),
    updated_at = NOW(6),
    created_by = 'system'
WHERE created_at IS NULL;

ALTER TABLE practice_lead
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT 'Creation timestamp',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT 'Last update timestamp',
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL COMMENT 'User identifier who created the record';


-- 3) team_settings: add the creation columns (updated_at/updated_by exist). ---
--    Existing rows: the row's own update columns are the best known creation
--    facts (every row so far was written exactly once by V418's seed or the
--    settings UI).

ALTER TABLE team_settings
    ADD COLUMN IF NOT EXISTS created_at DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255) NULL COMMENT 'User identifier who created the record';

UPDATE team_settings
SET created_at = updated_at,
    created_by = COALESCE(updated_by, 'system')
WHERE created_at IS NULL;

ALTER TABLE team_settings
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT 'Creation timestamp',
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL COMMENT 'User identifier who created the record';
