-- ===================================================================
-- V562: Recruitment email templates — the moment a letter ANSWERS
-- ===================================================================
-- Feature: candidate communications (trigger/identity split)
-- Domain:  recruitmentservice (candidate email aggregate)
--
-- Purpose:
--   Until now template_key carried two jobs at once: identity (every
--   EMAIL_SENT payload and every report joins on it, so it can never be
--   renamed) and routing (the candidate mailer looked the letter up BY
--   that key). Both cannot be true at once — pointing a different letter
--   at "Rejected after interview" meant renaming a key that reporting
--   depends on, so nobody did, and the pipeline stayed silent instead.
--
--   trigger_key takes the routing half. template_key keeps identity and
--   stays immutable.
--
-- Tables:
--   recruitment_email_templates — one additive nullable column + index
--
-- Design notes:
--   * NULL is "this letter has claimed no moment", which is the state
--     every existing row is created in. RecruitmentEmailService's
--     resolution falls back to template_key for exactly those rows, so
--     every pre-existing template answers precisely what it answered
--     before this column existed.
--   * NOTHING IS BACKFILLED. Deliberate: the release must be
--     behaviour-neutral, and a rollback to the previous image must be
--     harmless — the old code simply never reads the column.
--   * The unique index is what forbids two letters claiming the same
--     moment. MariaDB permits many NULLs in a UNIQUE index, which is
--     exactly the semantics "unassigned" needs — the constraint binds
--     only the rows that have actually claimed something.
--   * VARCHAR(60) matches template_key: the assignable values are drawn
--     from the same reserved-key vocabulary (ACKNOWLEDGEMENT, STAGE_*,
--     REJECTION_*, POOLED_*), enforced in code by
--     RecruitmentEmailService.isTriggerKey.
--
-- Collation: table default (utf8mb4_general_ci, V438).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   all DDL is IF NOT EXISTS.
--
-- Author: Claude Code
-- Date:   2026-09-02
-- Rollback: inert without the accompanying backend image (the column
--   stays NULL and the old code never reads it). Full removal is the
--   reverse of the DDL below — remove the index
--   uq_recr_email_tmpl_trigger_key, then the trigger_key column.
-- ===================================================================

ALTER TABLE recruitment_email_templates
    ADD COLUMN IF NOT EXISTS trigger_key VARCHAR(60) NULL
        COMMENT 'Which pipeline moment this letter answers; NULL = unassigned, and resolution then falls back to template_key. Mutable, unlike template_key.'
        AFTER template_key;

-- Many NULLs are permitted; two rows claiming the same moment are not.
CREATE UNIQUE INDEX IF NOT EXISTS uq_recr_email_tmpl_trigger_key
    ON recruitment_email_templates (trigger_key);
