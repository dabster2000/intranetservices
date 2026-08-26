-- ===================================================================
-- Recruitment: candidate fact-state projection
-- (Interview Room design spec 2026-08-26 §4.3)
--
-- Purpose:
--   The board needs a completeness ring on eighty cards at once, and
--   deriving fact state (UNKNOWN/ASKED/STATED/CONFIRMED/STALE) from the
--   event stream per candidate per field is fine for one profile and too
--   slow for that. This table is the read model: one row per
--   (candidate, fact field), maintained by RecruitmentFactStateProjector
--   (a RecruitmentReactor on NOTE_ADDED, keyed on seq like every other
--   projection).
--
--   IT IS A CACHE, NEVER A SOURCE OF TRUTH: rebuildable from the stream,
--   and STALE is always re-derived at read time from last_stated_at + the
--   group's freshness window (never persisted — a state that changes by
--   the calendar cannot live in a column).
--
-- GDPR — deliberately NOT an anonymisation target (spec §4.4): the table
--   holds no prose — a field key, an enum, a seq and a timestamp. It is
--   rebuilt from a stream that has already been anonymised, and the
--   anonymiser never reads it.
--
-- Idempotency: IF NOT EXISTS; raw re-run safe.
--
-- Author: Claude Code
-- Date:   2026-08-26
-- Rollback: safe to drop and rebuild at any time (POST
--   /recruitment/reactors — the standard projection rebuild path):
--     DROP TABLE IF EXISTS recruitment_candidate_fact_state;
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_candidate_fact_state (
    candidate_uuid       VARCHAR(36) NOT NULL
        COMMENT 'Soft FK recruitment_candidates.uuid',
    field                VARCHAR(50) NOT NULL
        COMMENT 'RecruitmentFactVocabulary key (e.g. NOTICE_PERIOD)',
    state                VARCHAR(12) NOT NULL
        COMMENT 'ASKED | STATED | CONFIRMED — UNKNOWN is the absent row, STALE is derived at read time',
    last_value_event_seq BIGINT      NOT NULL
        COMMENT 'recruitment_events.seq of the newest NOTE_ADDED carrying this field',
    last_stated_at       DATETIME    NULL
        COMMENT 'UTC occurred_at of the newest value-bearing note; NULL while only ASKED',

    PRIMARY KEY (candidate_uuid, field)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COMMENT = 'Fact-state read model — cache over recruitment_events, no prose, not an anonymisation target';
