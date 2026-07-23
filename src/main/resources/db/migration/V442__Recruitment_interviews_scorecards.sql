-- ===================================================================
-- V442: Recruitment ATS expansion — Phase 11: interviews & scorecards
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P11, spec §4.1/§5.3)
-- Domain:  recruitmentservice (interview loop)
--
-- Purpose:
--   The interview loop goes live: interviews (rounds 1–3 plus the
--   INFORMAL "uformel snak") are scheduled per application, and each
--   assigned interviewer submits exactly one blind scorecard per
--   interview. The blind rule and debrief unlock are enforced in
--   RecruitmentInterviewService, never in SQL.
--
-- Tables:
--   recruitment_interviews — one row per scheduled interview
--   recruitment_scorecards — one row per interviewer per interview
--
-- Design notes:
--   * UUIDs are VARCHAR(36) — schema-wide convention (V433 notes).
--   * application_uuid / interview_uuid are REAL FKs inside the module
--     (V436 idiom), ON DELETE RESTRICT: recruitment rows are never
--     hard-deleted (GDPR anonymizes).
--   * interviewer_uuids is a JSON array of users.uuid (soft FKs, module
--     convention) — assignment grants per-candidate involvement in
--     RecruitmentVisibility (JSON_CONTAINS lookup, spec §7.2
--     "Interviewer = per-candidate assignment").
--   * kind=INFORMAL is Airtable's "Uformel interview dato": schedulable
--     at any point without advancing the stage, round IS NULL, no
--     scorecard allowed (a plain note suffices — spec §5.3).
--   * graph_event_id is the nullable Outlook linkage. Graph calendar
--     scheduling ships dark behind the config property
--     dk.trustworks.recruitment.graph.calendar.enabled (tenant
--     permissions pending — plan §P11 manual-scheduling fallback);
--     manual scheduling leaves the column NULL.
--   * scores keys are the position's scorecard_template attribute
--     codes (snapshotted at position create, P2); values 1..4.
--     Scorecard free-text notes are NOT a column — they live in the
--     SCORECARD_SUBMITTED event's pii block (spec §4.1 design note:
--     free-text personal content only in event pii).
--   * status PLANNED is reserved (spec enum) — the P11 API always
--     schedules with a time, so rows are created SCHEDULED; HELD is
--     set by the first scorecard submission; CANCELLED via cancel.
--   * overdue_nudged is P17's SLA-nudge bookkeeping (data only here).
--   * Audit columns follow the house Auditable pattern (V421),
--     populated by AuditEntityListener from X-Requested-By.
--
-- Collation: utf8mb4_general_ci (V315 lesson — JOINs against legacy
--   tables fail on unicode_ci with "Illegal mix of collations").
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   all DDL is IF NOT EXISTS, the page_registry seed is INSERT ... ON
--   DUPLICATE KEY UPDATE (V430 convention).
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: inert without the P11 backend image; the tables are
--   additive and harmless to leave in place. Full removal (only if the
--   programme is abandoned):
--     DROP TABLE recruitment_scorecards;
--     DROP TABLE recruitment_interviews;
--     DELETE FROM page_registry WHERE page_key = 'recruitment-interviews';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Interviews
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_interviews (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted interview identity',

    application_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_applications.uuid — interviews hang on the pipeline run',

    kind VARCHAR(10) NOT NULL
        COMMENT 'ROUND (counts toward the stage machine) or INFORMAL (uformel snak)',

    round TINYINT NULL
        COMMENT '1..3 for kind=ROUND (maps to stage INTERVIEW_n); NULL for INFORMAL',

    scheduled_at DATETIME NULL
        COMMENT 'UTC. The agreed interview time',

    graph_event_id VARCHAR(255) NULL
        COMMENT 'Outlook calendar event id when Graph scheduling is enabled; NULL = manual',

    interviewer_uuids JSON NOT NULL
        COMMENT 'JSON array of users.uuid — the assigned interviewers',

    location VARCHAR(200) NULL
        COMMENT 'PII-free: room name or "Teams" (spec §4.1)',

    status VARCHAR(10) NOT NULL DEFAULT 'SCHEDULED'
        COMMENT 'PLANNED (reserved) / SCHEDULED / HELD / CANCELLED',

    -- Audit columns (house Auditable pattern)
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    modified_by VARCHAR(36) NULL,

    CONSTRAINT fk_recr_interview_application
        FOREIGN KEY (application_uuid) REFERENCES recruitment_applications (uuid)
        ON DELETE RESTRICT,
    CONSTRAINT chk_recr_interview_kind
        CHECK (kind IN ('INFORMAL', 'ROUND')),
    CONSTRAINT chk_recr_interview_status
        CHECK (status IN ('PLANNED', 'SCHEDULED', 'HELD', 'CANCELLED')),
    CONSTRAINT chk_recr_interview_round
        CHECK ((kind = 'ROUND' AND round BETWEEN 1 AND 3)
            OR (kind = 'INFORMAL' AND round IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS P11: interviews per application (spec §4.1)';

CREATE INDEX IF NOT EXISTS idx_recr_interviews_application
    ON recruitment_interviews (application_uuid);
CREATE INDEX IF NOT EXISTS idx_recr_interviews_scheduled
    ON recruitment_interviews (status, scheduled_at);

-- -------------------------------------------------------------------
-- 2. Scorecards
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_scorecards (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted scorecard identity',

    interview_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_interviews.uuid',

    interviewer_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK users.uuid — the submitting interviewer (one scorecard each)',

    scores JSON NOT NULL
        COMMENT 'attribute code -> 1..4; codes from the position scorecard_template',

    recommendation VARCHAR(10) NOT NULL
        COMMENT 'STRONG_NO / NO / YES / STRONG_YES',

    submitted_at DATETIME NOT NULL
        COMMENT 'UTC. Blind rule pivots on submission, not creation',

    overdue_nudged TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'P17 SLA-nudge bookkeeping (max 2 nudges); data-only in P11',

    -- Audit columns (house Auditable pattern)
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    modified_by VARCHAR(36) NULL,

    CONSTRAINT fk_recr_scorecard_interview
        FOREIGN KEY (interview_uuid) REFERENCES recruitment_interviews (uuid)
        ON DELETE RESTRICT,
    CONSTRAINT uq_recr_scorecard_per_interviewer
        UNIQUE (interview_uuid, interviewer_uuid),
    CONSTRAINT chk_recr_scorecard_recommendation
        CHECK (recommendation IN ('STRONG_NO', 'NO', 'YES', 'STRONG_YES'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS P11: blind scorecards, one per interviewer per interview';

CREATE INDEX IF NOT EXISTS idx_recr_scorecards_interviewer
    ON recruitment_scorecards (interviewer_uuid);

-- -------------------------------------------------------------------
-- 3. Page registry: the interviewer surface (all employees — the
--    backend scopes every row to the callers own assignments)
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-interviews', 'My interviews', 1, '/recruitment/interviews', 'USER', 136, 'COMPANY', 'CalendarDays', 0, NULL)
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
