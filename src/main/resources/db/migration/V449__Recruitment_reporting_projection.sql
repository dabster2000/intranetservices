-- ===================================================================
-- V449: Recruitment ATS expansion — Phase 20: Reporting
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P20)
-- Domain:  recruitmentservice (insight)
--
-- Purpose:
--   1. recruitment_fact_monthly — the monthly-grain reporting projection
--      maintained by the ReportingProjector reactor (spec §3.2 kind 3).
--      One row per (month × fact × dimension combination), counters
--      accumulated via INSERT ... ON DUPLICATE KEY UPDATE.
--
--      Anonymization-proof BY SCHEMA: every column is a date, a code,
--      a uuid or a number — there is nowhere to put a name, an email
--      or free text. person_uuid only ever holds EMPLOYEE uuids
--      (interviewers, referrers), never candidate identity.
--
--      Dimension columns use '' (empty string) instead of NULL as the
--      "not applicable" sentinel: MariaDB unique indexes treat NULLs
--      as always-distinct, which would break the upsert accumulation.
--
--      The table is rebuildable at any time from the event stream
--      (admin endpoint POST /recruitment/reports/rebuild) — losing or
--      truncating it loses nothing.
--
--   2. Register /recruitment/reports in page_registry (spec §6.1:
--      recruiter, partners, COO).
--
-- Collation: utf8mb4_general_ci — module convention since V315/V433.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is IF NOT EXISTS, the page_registry seed is ON DUPLICATE KEY
--   UPDATE (registry convention, V430/V434/V438/V439/V446/V448).
--
-- Author: Claude Code
-- Date:   2026-07-24
-- Rollback: inert without the P20 images; everything is additive.
--   Full removal (only if the programme is abandoned):
--     DROP TABLE recruitment_fact_monthly;
--     DELETE FROM page_registry WHERE page_key = 'recruitment-reports';
--     DELETE FROM recruitment_reactor_offsets WHERE reactor_name = 'reporting-projector';
-- ===================================================================

CREATE TABLE IF NOT EXISTS recruitment_fact_monthly (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,

    month DATE NOT NULL
        COMMENT 'First day of the month (UTC) the fact belongs to; from the event''s occurred_at.',
    fact VARCHAR(40) NOT NULL
        COMMENT 'Java enum ReportingFact: CANDIDATE_CREATED | APPLICATION_CREATED | STAGE_MOVED | TERMINAL | HIRED | SCORECARD_SUBMITTED | REFERRAL_SUBMITTED | REFERRAL_TRIAGED | ART14_NOTICE_SENT | CONSENT_GRANTED | CONSENT_WITHDRAWN | CONSENT_EXPIRED | ANONYMIZED | DSAR_RECEIVED | DSAR_EXPORTED',

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

    UNIQUE KEY uq_rfm_dims (month, fact, position_uuid, practice_uuid, hiring_track,
                            source, stage_from, stage_to, outcome, detail, person_uuid),
    KEY ix_rfm_fact_month (fact, month)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_general_ci
    COMMENT = 'P20 monthly reporting projection — rebuildable from recruitment_events, no PII by schema';

-- -------------------------------------------------------------------
-- Sidebar / route registration (spec §6.1: recruiter, partners, COO)
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-reports', 'Reports', 1, '/recruitment/reports', 'ADMIN,HR,CXO,PARTNER', 860, 'RECRUITMENT', 'BarChart3', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label     = VALUES(page_label),
    is_visible     = VALUES(is_visible),
    react_route    = VALUES(react_route),
    required_roles = VALUES(required_roles),
    display_order  = VALUES(display_order),
    section        = VALUES(section),
    icon_name      = VALUES(icon_name),
    is_external    = VALUES(is_external),
    external_url   = VALUES(external_url);
