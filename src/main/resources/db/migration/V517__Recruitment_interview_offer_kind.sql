-- ===================================================================
-- V517: Recruitment ATS — the OFFER interview kind
-- ===================================================================
-- Feature: interviews in the offer phase (2026-08-19)
-- Domain:  recruitmentservice (interview loop)
--
-- WHY
--   Interviews could only be booked as rounds 1–3 (kind='ROUND', mapped
--   to stage INTERVIEW_n) or as the informal "uformel snak"
--   (kind='INFORMAL'). Meetings held while the candidate sits in the
--   OFFER phase — the offer/contract conversation, or a last talk with
--   a partner — had nowhere to go: booking them as a round would invent
--   a pipeline stage the position does not have, and booking them as an
--   informal chat mislabels them on every calendar, Slack card and
--   candidate invitation.
--
--   kind='OFFER' is that third kind. It behaves like INFORMAL, not like
--   a round: no round number, no scorecard, no debrief entry, no SLA
--   nudge, and it never advances the stage machine. It is deliberately
--   NOT gated on the application already standing at OFFER — the meeting
--   is regularly booked while the decision to make an offer is still
--   being taken (RecruitmentInterviewService#create).
--
-- Changes (constraints only — no data is read, written or deleted):
--   recruitment_interviews.chk_recr_interview_kind  — allow 'OFFER'
--   recruitment_interviews.chk_recr_interview_round — 'OFFER' rows carry
--                                                     round IS NULL
--   Both are WIDENED: every row legal before this migration is still
--   legal after it, so the recreate cannot reject existing data.
--
--   recruitment_scheduling_request (Method B, V498) needs no change: its
--   kind column is a plain VARCHAR(10) with no CHECK, and 'OFFER' fits.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   each ADD is preceded by DROP ... IF EXISTS, so a re-run recreates
--   rather than collides (the V479 widening idiom, made re-runnable).
--
-- Author: Claude Code
-- Date:   2026-08-19
-- Rollback: only meaningful while no OFFER row exists (recruitment rows
--   are never hard-deleted — cancel them instead), then restore the two
--   V442 constraints:
--     ALTER TABLE recruitment_interviews
--         DROP CONSTRAINT IF EXISTS chk_recr_interview_kind;
--     ALTER TABLE recruitment_interviews
--         ADD CONSTRAINT chk_recr_interview_kind
--             CHECK (kind IN ('INFORMAL', 'ROUND'));
--     ALTER TABLE recruitment_interviews
--         DROP CONSTRAINT IF EXISTS chk_recr_interview_round;
--     ALTER TABLE recruitment_interviews
--         ADD CONSTRAINT chk_recr_interview_round
--             CHECK ((kind = 'ROUND' AND round BETWEEN 1 AND 3)
--                 OR (kind = 'INFORMAL' AND round IS NULL));
-- ===================================================================

ALTER TABLE recruitment_interviews
    DROP CONSTRAINT IF EXISTS chk_recr_interview_kind;

ALTER TABLE recruitment_interviews
    ADD CONSTRAINT chk_recr_interview_kind
        CHECK (kind IN ('INFORMAL', 'ROUND', 'OFFER'));

ALTER TABLE recruitment_interviews
    DROP CONSTRAINT IF EXISTS chk_recr_interview_round;

ALTER TABLE recruitment_interviews
    ADD CONSTRAINT chk_recr_interview_round
        CHECK ((kind = 'ROUND' AND round BETWEEN 1 AND 3)
            OR (kind IN ('INFORMAL', 'OFFER') AND round IS NULL));

-- The column comments are the schema's own documentation — keep them
-- honest now that a third kind exists.
ALTER TABLE recruitment_interviews
    MODIFY COLUMN kind VARCHAR(10) NOT NULL
        COMMENT 'ROUND (counts toward the stage machine), INFORMAL (uformel snak) or OFFER (offer-phase meeting)';

ALTER TABLE recruitment_interviews
    MODIFY COLUMN round TINYINT NULL
        COMMENT '1..3 for kind=ROUND (maps to stage INTERVIEW_n); NULL for INFORMAL and OFFER';
