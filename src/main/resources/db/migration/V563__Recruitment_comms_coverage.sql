-- ===================================================================
-- V563: Recruitment comms coverage — the nightly rollup the Journey reads
-- ===================================================================
-- Feature: candidate communications (Journey tab counts)
-- Domain:  recruitmentservice (candidate email aggregate)
--
-- Purpose:
--   The Journey tab answers two questions per pipeline moment: does a
--   letter answer it, and how often has it actually happened lately.
--   The first is decided live against the template table
--   (RecruitmentCommsCoverageService). The second is a count over 90
--   days of recruitment_events with a JSON payload predicate per event
--   type — far too much work to do inside a page request, and pointless
--   to redo per viewer since the answer changes once a day at most.
--
--   RecruitmentCommsCoverageJob recomputes this table nightly and the
--   coverage endpoint simply reads it.
--
-- Tables:
--   recruitment_comms_coverage — one row per trigger key that occurred
--   or was emailed inside the window
--
-- Design notes:
--   * The primary key is the trigger key, not a surrogate uuid: this is
--     a derived read model with exactly one row per key, and the PK is
--     what carries the job's INSERT .. ON DUPLICATE KEY UPDATE.
--   * The job REPLACES the counts it writes (occurred_count =
--     VALUES(occurred_count)) rather than adding to them, and deletes
--     rows for keys that fell out of the window. Every run recomputes
--     the whole window from the event stream, so a re-run — a retry, a
--     second ECS task, a manual ops run — lands on the same numbers
--     instead of doubling them. This is deliberately NOT the additive
--     idiom recruitment_fact_monthly uses (V449): that projection sees
--     each event exactly once, this one sees the same 90 days again
--     every night.
--   * window_days is stored per row rather than assumed by the reader,
--     so a future change of the window is visible in the data instead
--     of silently reinterpreting old numbers.
--   * VARCHAR(60) matches recruitment_email_templates.template_key and
--     .trigger_key — the values are drawn from the same reserved-key
--     vocabulary (ACKNOWLEDGEMENT, STAGE_*, REJECTION_*, POOLED_*).
--   * No rows are seeded. An empty table means "nothing has counted
--     yet", which the coverage endpoint already renders as zeroes — the
--     page has to work on a cold database, and does.
--   * The table holds counts only. No candidate, application or user
--     identifier appears in it, so it is outside the GDPR anonymization
--     and hard-delete cascades by construction.
--
-- Reserved-word check (MariaDB 10.x, the V534 LINES lesson): `window`
--   and `trigger` ARE reserved, but `window_days` and `trigger_key` are
--   not — reservation applies to the whole identifier, not a prefix.
--
-- Collation: utf8mb4_general_ci, matching recruitment_email_templates
--   (V446) whose keys these values mirror.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   all DDL is IF NOT EXISTS.
--
-- Author: Claude Code
-- Date:   2026-09-02
-- Rollback: inert without the accompanying backend image (nothing
--   writes the table and the coverage endpoint already tolerates it
--   being absent). Full removal:
--     DROP TABLE recruitment_comms_coverage;
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_comms_coverage (
    trigger_key VARCHAR(60) NOT NULL
        COMMENT 'The pipeline moment counted: ACKNOWLEDGEMENT | STAGE_<stage> | REJECTION_* | POOLED_* | ... — same vocabulary as recruitment_email_templates.trigger_key',

    occurred_count INT NOT NULL DEFAULT 0
        COMMENT 'Times the moment happened inside the window, derived from the event that would fire it. One rejection increments BOTH its generic and its reason-coded key — the Journey shows both rows.',

    emailed_count INT NOT NULL DEFAULT 0
        COMMENT 'EMAIL_SENT events inside the window whose payload.template_key resolves to this trigger. The gap to occurred_count is the silence the page exists to show.',

    window_days INT NOT NULL DEFAULT 90
        COMMENT 'Length of the rolling window these counts cover, so a later change of the window cannot silently reinterpret old rows',

    computed_at DATETIME NOT NULL
        COMMENT 'UTC. When the nightly rollup last wrote this row.',

    PRIMARY KEY (trigger_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Nightly 90-day rollup behind the communications Journey tab; recomputed in full each run, never accumulated. See V563';
