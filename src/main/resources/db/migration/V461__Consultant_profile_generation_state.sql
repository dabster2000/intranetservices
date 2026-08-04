-- ============================================================================
-- V461: Consultant profile generation state + poisoned-row repair
-- ============================================================================
-- Purpose: The consultant_profiles cache (V244) could not distinguish "never
--          generated" from "generation failed" from "generated successfully",
--          and a failed OpenAI call was written back with generated_at stamped
--          — caching an empty card for 7 days. This migration adds explicit
--          generation state so the read path can serve a truthful status to the
--          dashboard, and repairs rows already poisoned by that defect.
--
-- Columns added:
--   status              - PENDING | READY | UNAVAILABLE. PENDING = not generated
--                         yet or a retry is due; READY = a usable pitch is
--                         cached; UNAVAILABLE = no CV row, or generation failed
--                         max-attempts times and is parked.
--   generation_attempts - consecutive failed attempts; reset to 0 on success.
--   last_attempt_at     - when generation was last attempted (success OR
--                         failure). Drives the retry backoff and the
--                         cross-instance claim used by the nightly pre-warm job
--                         (the scheduler is NOT clustered — every ECS task
--                         fires the cron).
--   last_error          - short failure code (empty-output, unparseable,
--                         bad-pitch, bad-industries, bad-skills, no-cv).
--                         Diagnostic only; never rendered to users.
--
-- Index: idx_consultant_profiles_prewarm (status, generated_at) supports the
--        nightly candidate scan.
--
-- Data repair: rows whose industries_json/top_skills_json hold the literal
--        4-character JSON string 'null' (Jackson serialises a MissingNode that
--        way) or an empty pitch were written by the failure path with
--        generated_at stamped, so isStale() considered them fresh for 7 days.
--        Clearing generated_at makes them stale immediately; the read path then
--        enqueues a regeneration. Note the literal 'null' passes any
--        "IS NOT NULL" / "<> ''" test, so it must be matched explicitly.
--
-- Nullability: status and generation_attempts are NOT NULL with defaults so
--        existing rows and INSERT IGNORE seeding both work without app changes.
--
-- Rollback:
--   DROP INDEX idx_consultant_profiles_prewarm ON consultant_profiles;
--   ALTER TABLE consultant_profiles
--     DROP COLUMN status, DROP COLUMN generation_attempts,
--     DROP COLUMN last_attempt_at, DROP COLUMN last_error;
--   (The data repair is not reversible; it only clears a timestamp on rows that
--    held unusable content, and the profiles regenerate.)
--
-- Impact:
--   - Entity ConsultantProfile gains four fields.
--   - No other tables or entities are affected.
--
-- Author: Claude Code
-- Date: 2026-08-04
-- ============================================================================

ALTER TABLE consultant_profiles
    ADD COLUMN status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' AFTER cv_updated_at,
    ADD COLUMN generation_attempts INT          NOT NULL DEFAULT 0         AFTER status,
    ADD COLUMN last_attempt_at     DATETIME     NULL                       AFTER generation_attempts,
    ADD COLUMN last_error          VARCHAR(255) NULL                       AFTER last_attempt_at;

-- Repair rows poisoned by the failure path (literal 'null' / empty pitch with
-- generated_at stamped): make them stale so they regenerate.
UPDATE consultant_profiles
SET generated_at = NULL,
    status       = 'PENDING',
    last_error   = 'v461-repair'
WHERE generated_at IS NOT NULL
  AND (pitch_text IS NULL
       OR TRIM(pitch_text) = ''
       OR industries_json IS NULL
       OR CAST(industries_json AS CHAR) IN ('null', '[]')
       OR top_skills_json IS NULL
       OR CAST(top_skills_json AS CHAR) IN ('null', '[]'));

-- Everything still holding a real pitch is READY.
UPDATE consultant_profiles
SET status = 'READY'
WHERE generated_at IS NOT NULL
  AND pitch_text IS NOT NULL
  AND TRIM(pitch_text) <> '';

CREATE INDEX idx_consultant_profiles_prewarm ON consultant_profiles (status, generated_at);
