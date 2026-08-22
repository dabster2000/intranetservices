-- ===================================================================
-- V523: Recruitment reporting — weekly grain for the funnel digest
-- ===================================================================
-- Feature: Recruitment weekly digest redesign (2026-08-22)
-- Domain:  recruitmentservice (insight)
--
-- Purpose:
--   recruitment_fact_weekly — an ISO-week-grain sibling of
--   recruitment_fact_monthly (V449), maintained by the same
--   ReportingProjector reactor in the same transaction as the monthly
--   increment. Identical dimensions and measures; only the time column
--   differs.
--
--   WHY A SECOND TABLE AND NOT A `week` COLUMN ON V449:
--     An ISO week can straddle a month boundary (e.g. Mon 2026-06-29 →
--     Sun 2026-07-05). If the monthly table's grain became weekly, its
--     monthly rollups would silently attribute a straddling week to one
--     month only. The two grains are genuinely independent aggregations
--     of the same stream, so they get one table each and neither is
--     derived from the other.
--
--   MOTIVATION: the "Recruitment week in numbers" Slack digest was
--   titled with an ISO week but read AiDigestService's four-month
--   window (month-to-date + 3 months back), because monthly was the
--   only grain available. The header claimed a week, the prose said
--   "I denne måned" and the KPI grid summed a quarter. This table gives
--   the digest real week numbers for its headline while the monthly
--   table keeps serving the trend chart.
--
--   Anonymization-proof BY SCHEMA, exactly as V449: every column is a
--   date, a code, a uuid or a number — there is nowhere to put a name,
--   an email or free text. person_uuid only ever holds EMPLOYEE uuids
--   (interviewers, referrers), never candidate identity.
--
--   Dimension columns use '' (empty string) instead of NULL as the
--   "not applicable" sentinel — MariaDB unique indexes treat NULLs as
--   always-distinct, which would break the upsert accumulation.
--
-- Backfill:
--   None here by design. The table is rebuildable from the event stream
--   via the existing admin endpoint POST /recruitment/reports/rebuild,
--   which now resets both grains and replays. Until that is run the
--   table is simply empty and the digest reports a zeroed week — no
--   wrong numbers, just absent ones.
--
-- Collation: utf8mb4_general_ci — module convention since V315/V433.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is IF NOT EXISTS.
--
-- Author: Claude Code
-- Date:   2026-08-22
-- Rollback: additive and inert without the new projector image.
--   Full removal:
--     DROP TABLE recruitment_fact_weekly;
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_fact_weekly (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,

    week DATE NOT NULL
        COMMENT 'Monday (ISO-8601) of the week the fact belongs to; from the event''s occurred_at.',
    fact VARCHAR(40) NOT NULL
        COMMENT 'Java enum ReportingFact — same vocabulary as recruitment_fact_monthly.fact',

    -- Dimensions ('' = not applicable for this fact; never NULL — see header)
    position_uuid VARCHAR(36) NOT NULL DEFAULT ''
        COMMENT 'Soft-FK recruitment_positions.uuid',
    practice_uuid VARCHAR(36) NOT NULL DEFAULT ''
        COMMENT 'Soft-FK practice.uuid, resolved from the position at projection time',
    hiring_track VARCHAR(20) NOT NULL DEFAULT ''
        COMMENT 'PRACTICE_TEAM | PARTNER | STAFF_ROLE — lets read queries keep partner-track data k-safe',
    source VARCHAR(40) NOT NULL DEFAULT ''
        COMMENT 'CandidateSource enum code',
    stage_from VARCHAR(20) NOT NULL DEFAULT ''
        COMMENT 'RecruitmentStage code being left (stage moves / terminals)',
    stage_to VARCHAR(20) NOT NULL DEFAULT ''
        COMMENT 'RecruitmentStage code being entered (stage moves)',
    outcome VARCHAR(40) NOT NULL DEFAULT ''
        COMMENT 'Fact-specific code: direction | terminal kind | origin | consent kind | anonymization mode',
    detail VARCHAR(40) NOT NULL DEFAULT ''
        COMMENT 'Fact-specific secondary code: rejection reason | origin | Art.14 channel',
    person_uuid VARCHAR(36) NOT NULL DEFAULT ''
        COMMENT 'EMPLOYEE uuid only (interviewer / referrer) — never a candidate',

    -- Measures
    cnt BIGINT NOT NULL DEFAULT 0
        COMMENT 'Occurrence count',
    sum_days DECIMAL(14,2) NOT NULL DEFAULT 0
        COMMENT 'Accumulated days (time-in-stage on STAGE_MOVED/TERMINAL rows); avg = sum_days / cnt',

    UNIQUE KEY uq_rfw_dims (week, fact, position_uuid, practice_uuid, hiring_track,
                            source, stage_from, stage_to, outcome, detail, person_uuid),
    KEY ix_rfw_fact_week (fact, week)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_general_ci
    COMMENT = 'ISO-week reporting projection — rebuildable from recruitment_events, no PII by schema';
