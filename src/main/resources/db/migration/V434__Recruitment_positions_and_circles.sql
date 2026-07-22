-- ===================================================================
-- V434: Recruitment ATS expansion — Phase 2: positions & hiring tracks
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P2, spec §4.1)
-- Domain:  recruitmentservice (positions aggregate + partner circles)
--
-- Purpose:
--   The first state tables of the ATS expansion. A position is the
--   thing candidates apply to (P4 adds recruitment_applications); the
--   circle restricts partner-track positions to named members — the
--   hard per-viewer filter every later query path applies.
--
-- Tables:
--   recruitment_positions      — one row per opening (three hiring
--                                tracks: PRACTICE_TEAM, PARTNER,
--                                STAFF_ROLE)
--   recruitment_circle_members — named viewers of a PARTNER-track
--                                position (role_in_circle: OWNER,
--                                RECRUITER, PARTICIPANT)
--
-- Design notes:
--   * UUIDs are VARCHAR(36) — schema-wide convention (see V433 notes).
--   * practice_uuid is a REAL FK to the practice(uuid) UNIQUE key —
--     the registry idiom, exactly like team.practice_uuid (V424).
--     Recruitment never stores practice codes; DTOs derive
--     practiceCode via the @Formula registry lookup.
--   * team_uuid / hiring_owner_uuid are soft FKs (module convention;
--     teamroles has no FK to team either). team_uuid NULL means
--     "team decided at offer".
--   * stage_set / scorecard_template are JSON snapshots per position,
--     copied from track defaults at create time so later default
--     changes never rewrite in-flight positions.
--   * public_slug is nullable + unique: NULL = no public form (P5).
--   * Audit columns follow the house Auditable pattern (V421),
--     populated by AuditEntityListener from X-Requested-By.
--
-- Collation: utf8mb4_general_ci (V315 lesson — JOINs against legacy
--   tables fail on unicode_ci with "Illegal mix of collations").
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   all DDL is IF NOT EXISTS, seeds are INSERT ... ON DUPLICATE KEY
--   UPDATE (page_registry convention, V430).
--
-- Author: Claude Code
-- Date:   2026-07-22
-- Rollback: inert without the P2 backend image; tables are additive
--   and harmless to leave in place. Full removal (only if the
--   programme is abandoned):
--     DROP TABLE recruitment_circle_members;
--     DROP TABLE recruitment_positions;
--     DELETE FROM page_registry WHERE page_key = 'recruitment-positions';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Positions
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_positions (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted position identity',

    title VARCHAR(200) NOT NULL
        COMMENT 'Job title as shown internally and (P5) on the public form',

    hiring_track VARCHAR(20) NOT NULL
        COMMENT 'Java enum RecruitmentHiringTrack: PRACTICE_TEAM | PARTNER | STAFF_ROLE. Immutable after create.',

    practice_uuid VARCHAR(36) NULL
        COMMENT 'FK practice(uuid) — registry idiom (V424). Required for PRACTICE_TEAM, optional otherwise. Never a code.',

    team_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK team.uuid. NULL = team decided at offer.',

    hiring_owner_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK users.uuid. Required for STAFF_ROLE (named owner), optional otherwise.',

    public_slug VARCHAR(80) NULL
        COMMENT 'URL slug for the public application form (P5). NULL = no public form. Lowercase a-z0-9 and hyphens.',

    stage_set JSON NULL
        COMMENT 'Ordered stage codes for this position, e.g. ["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]. Snapshot of the track default at create time; per-position override allowed.',

    scorecard_template JSON NULL
        COMMENT 'Scorecard attributes for this position: [{"code":"WHY_CONSULTING","label":"Why consulting"},...]. Snapshot of the standard framework at create time.',

    demand_rag VARCHAR(10) NOT NULL DEFAULT 'GREEN'
        COMMENT 'Java enum RecruitmentDemandRag: GREEN | YELLOW | RED — hiring-demand urgency for the positions overview',

    status VARCHAR(10) NOT NULL DEFAULT 'OPEN'
        COMMENT 'Java enum RecruitmentPositionStatus: OPEN | ON_HOLD | CLOSED. CLOSED only via the close endpoint.',

    opened_at DATETIME(3) NOT NULL
        COMMENT 'UTC. Set at create time.',

    closed_at DATETIME(3) NULL
        COMMENT 'UTC. Set by the close endpoint; NULL while OPEN/ON_HOLD.',

    -- Audit columns (house Auditable pattern, V421)
    created_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL
        COMMENT 'users.uuid from X-Requested-By',
    modified_by VARCHAR(36) NULL
        COMMENT 'users.uuid from X-Requested-By',

    UNIQUE KEY uk_recruitment_positions_slug (public_slug),

    CONSTRAINT chk_rp_track_enum
        CHECK (hiring_track IN ('PRACTICE_TEAM','PARTNER','STAFF_ROLE')),
    CONSTRAINT chk_rp_rag_enum
        CHECK (demand_rag IN ('GREEN','YELLOW','RED')),
    CONSTRAINT chk_rp_status_enum
        CHECK (status IN ('OPEN','ON_HOLD','CLOSED')),

    CONSTRAINT fk_rp_practice_uuid
        FOREIGN KEY (practice_uuid) REFERENCES practice (uuid) ON DELETE RESTRICT

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: positions / openings (spec §4.1)';

-- List queries group by practice and filter by track/status.
CREATE INDEX IF NOT EXISTS idx_rp_practice ON recruitment_positions (practice_uuid, status);
CREATE INDEX IF NOT EXISTS idx_rp_track_status ON recruitment_positions (hiring_track, status);

-- -------------------------------------------------------------------
-- 2. Circle members (partner-track confidentiality boundary)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_circle_members (
    position_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK recruitment_positions.uuid (PARTNER track only)',
    user_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK users.uuid — the member who may see this position',
    role_in_circle VARCHAR(15) NOT NULL DEFAULT 'PARTICIPANT'
        COMMENT 'Java enum RecruitmentCircleRole: OWNER | RECRUITER | PARTICIPANT. OWNER/RECRUITER may manage the circle.',
    added_at DATETIME(3) NOT NULL
        COMMENT 'UTC',
    added_by_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK users.uuid — who added the member',

    PRIMARY KEY (position_uuid, user_uuid),

    CONSTRAINT chk_rcm_role_enum
        CHECK (role_in_circle IN ('OWNER','RECRUITER','PARTICIPANT'))

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: named viewers of partner-track positions (hard visibility filter)';

-- "Which positions can this user see?" is asked on every list query.
CREATE INDEX IF NOT EXISTS idx_rcm_user ON recruitment_circle_members (user_uuid);

-- -------------------------------------------------------------------
-- 3. Sidebar / route registration for /recruitment/positions.
--    Same roles the BFF route enforces (requireRoles) — the registry
--    row is UI routing + sidebar visibility, not the security boundary.
--    Sits next to the existing 'recruitment' row (870, section ADMIN).
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-positions', 'Positions', 1, '/recruitment/positions', 'ADMIN,HR,TEAMLEAD,PARTNER,CXO', 871, 'ADMIN', 'BriefcaseBusiness', 0, NULL)
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
