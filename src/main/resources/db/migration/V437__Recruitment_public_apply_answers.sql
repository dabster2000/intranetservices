-- ===================================================================
-- V437: Recruitment ATS expansion — Phase 5: public application forms
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P5, spec §4.1)
-- Domain:  recruitmentservice (application answers — dual ownership)
--
-- Purpose:
--   The P5 public forms write long-form answers in two shapes:
--     * position forms   → answers scoped to the created APPLICATION
--                          (application_uuid set, candidate_uuid NULL);
--     * unsolicited form → no application exists (recruiter triage
--                          attaches later — deliberate P5 spec decision),
--                          so answers scope to the CANDIDATE
--                          (candidate_uuid set, application_uuid NULL).
--   V436 created the table application-only; this migration relaxes
--   application_uuid to NULL and adds the candidate leg.
--
-- Design notes:
--   * Exactly one owner per row — enforced by chk_raa_owner (MariaDB
--     10.11 enforces CHECK constraints). Both set is also rejected: the
--     two form flows are mutually exclusive by construction and the
--     stricter XOR keeps reporting unambiguous.
--   * uk_raa_candidate_question mirrors uk_raa_application_question.
--     MariaDB UNIQUE keys permit multiple NULLs, so application-scoped
--     rows (candidate_uuid NULL) are unaffected, and vice versa.
--   * No separate idx on candidate_uuid: the UNIQUE key's leftmost
--     column already serves "this candidate's answers" lookups and the
--     FK's index requirement.
--   * candidate_uuid is a REAL FK inside the module (V436 idiom).
--     ON DELETE RESTRICT — recruitment rows are never hard-deleted
--     (GDPR anonymizes; this table is a named P19 anonymization target).
--
-- Collation: table already utf8mb4_general_ci (V436).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   ADD COLUMN/CONSTRAINT are IF NOT EXISTS; MODIFY restates the full
--   (already nullable) definition and is a no-op on re-run.
--
-- Author: Claude Code
-- Date:   2026-07-22
-- Rollback: inert without the P5 backend image. Full removal (only if
--   the programme is abandoned):
--     ALTER TABLE recruitment_application_answers
--       DROP CONSTRAINT chk_raa_owner,
--       DROP FOREIGN KEY fk_raa_candidate_uuid,
--       DROP KEY uk_raa_candidate_question,
--       DROP COLUMN candidate_uuid;
--     (restoring application_uuid NOT NULL requires no candidate-scoped
--      rows to exist)
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Relax application_uuid (unsolicited answers have no application)
-- -------------------------------------------------------------------
ALTER TABLE recruitment_application_answers
    MODIFY COLUMN application_uuid VARCHAR(36) NULL
        COMMENT 'FK recruitment_applications.uuid — set for position-form answers; NULL for unsolicited (candidate-scoped) answers';

-- -------------------------------------------------------------------
-- 2. Candidate leg (unsolicited form answers)
-- -------------------------------------------------------------------
ALTER TABLE recruitment_application_answers
    ADD COLUMN IF NOT EXISTS candidate_uuid VARCHAR(36) NULL
        COMMENT 'FK recruitment_candidates.uuid — set for unsolicited answers; NULL for application-scoped answers';

CREATE UNIQUE INDEX IF NOT EXISTS uk_raa_candidate_question
    ON recruitment_application_answers (candidate_uuid, question_key);

ALTER TABLE recruitment_application_answers
    ADD CONSTRAINT fk_raa_candidate_uuid
        FOREIGN KEY IF NOT EXISTS (candidate_uuid)
        REFERENCES recruitment_candidates (uuid) ON DELETE RESTRICT;

-- -------------------------------------------------------------------
-- 3. Exactly one owner per row
-- -------------------------------------------------------------------
ALTER TABLE recruitment_application_answers
    ADD CONSTRAINT IF NOT EXISTS chk_raa_owner
        CHECK ((application_uuid IS NULL) != (candidate_uuid IS NULL));
