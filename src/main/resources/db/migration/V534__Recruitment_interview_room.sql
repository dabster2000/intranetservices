-- ===================================================================
-- Recruitment: the Interview Room — live note drafts + feature flags
-- (Interview Room design spec 2026-08-26 §4.1, decision 2)
--
-- Purpose:
--   One draft row per (interview, author): the interviewer's live line
--   array while the room is open. Created on first keystroke, deleted on
--   land — the durable artefact is the SCORECARD_SUBMITTED event, never
--   this row. A row that never lands is swept 30 days after the
--   interview's scheduled_at (recruitment-interview-note-sweep job).
--
--   lines is JSON, not rows: the array is always read and written whole
--   (spec §4.1 — per-line rows would cost a second anonymisation join
--   and give nothing). Shape: INoteLine[] — {id, text, subjectCode,
--   factField, source, verbatim, ts}. client_revision is a
--   last-write-wins guard, not a merge: a PUT with a LOWER revision than
--   stored answers 409 (two tabs on one interview is a mistake, not a
--   use case).
--
-- GDPR — THE FIFTH ANONYMISATION TARGET (spec §4.4):
--   These drafts are one person's written impressions of another — the
--   most sensitive prose in the module. RecruitmentAnonymizerService
--   DELETES the rows (not a scrub — a draft has no structural value
--   worth preserving), and the DSAR export includes them. Both ship in
--   this same change set, per the spec's same-PR rule.
--
-- Flags seeded here (category 'recruitment', all read fresh from
-- app_settings on every call — the RecruitmentFeatureFlag no-cache
-- contract; note the staging nightly refresh reverts these seeds):
--
--   recruitment.facts.enabled            Slice 0 — the fact vocabulary,
--                                        the ledger and completeness
--                                        rings.
--   recruitment.interview-room.enabled   Slice 1 — the room page and its
--                                        endpoints.
--   recruitment.ai.interview-room.prep.enabled        Slice 2 — the AI
--   recruitment.ai.interview-room.extraction.enabled  features, one flag
--   recruitment.ai.interview-room.tidy.enabled        per capability,
--   recruitment.ai.interview-room.alignment.enabled   default OFF (AI
--                                        spec §3: every AI feature is
--                                        opt-in; oversight contract in
--                                        the design spec §9).
--
-- Idempotency: IF NOT EXISTS / INSERT IGNORE only; raw re-run safe
--   (repair-at-start re-runs migrations across checkouts).
--
-- Author: Claude Code
-- Date:   2026-08-26
-- Rollback: the table is a draft cache — safe to drop once the backend
--   image that reads it is gone:
--     DROP TABLE IF EXISTS recruitment_interview_notes;
--     DELETE FROM app_settings WHERE setting_key IN
--       ('recruitment.facts.enabled', 'recruitment.interview-room.enabled',
--        'recruitment.ai.interview-room.prep.enabled',
--        'recruitment.ai.interview-room.extraction.enabled',
--        'recruitment.ai.interview-room.tidy.enabled',
--        'recruitment.ai.interview-room.alignment.enabled');
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_interview_notes (
    uuid            VARCHAR(36) NOT NULL PRIMARY KEY,
    interview_uuid  VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_interviews.uuid (soft — the sweep and the anonymiser resolve it)',
    author_uuid     VARCHAR(36) NOT NULL
        COMMENT 'Soft FK users.uuid — the interviewer whose private draft this is',
    -- named note_lines, not lines: LINES is a MariaDB reserved word
    -- (LOAD DATA ... LINES TERMINATED BY) and broke the CREATE at parse
    -- time on the first staging deploy (2026-08-26 22:03 UTC canary).
    note_lines      JSON        NOT NULL
        COMMENT 'INoteLine[] — the whole draft, read and written as one array (spec §3.3)',
    client_revision BIGINT      NOT NULL DEFAULT 0
        COMMENT 'Monotonic, client-assigned; a PUT with a lower value answers 409 (last-write-wins guard, no merge)',
    created_at      DATETIME    NOT NULL,
    updated_at      DATETIME    NOT NULL
        COMMENT 'UTC; doubles as the presence signal — the poll counts a draft touched within the last minute as "in the room"',
    created_by      VARCHAR(36) NOT NULL,
    modified_by     VARCHAR(36) NULL,

    UNIQUE KEY uq_rin_interview_author (interview_uuid, author_uuid),
    KEY ix_rin_interview (interview_uuid)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COMMENT = 'Interview Room live note drafts — deleted on land, swept after 30 days, anonymisation target 5';

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.facts.enabled',                        'false', 'recruitment'),
    ('recruitment.interview-room.enabled',               'false', 'recruitment'),
    ('recruitment.ai.interview-room.prep.enabled',       'false', 'recruitment'),
    ('recruitment.ai.interview-room.extraction.enabled', 'false', 'recruitment'),
    ('recruitment.ai.interview-room.tidy.enabled',       'false', 'recruitment'),
    ('recruitment.ai.interview-room.alignment.enabled',  'false', 'recruitment');
