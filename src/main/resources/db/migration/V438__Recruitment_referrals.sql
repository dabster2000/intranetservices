-- ===================================================================
-- V438: Recruitment ATS expansion — Phase 6: referral intake
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P6, spec §4.1/§5.2)
-- Domain:  recruitmentservice (referrals aggregate)
--
-- Purpose:
--   The referral channel goes live: any employee submits a 60-second
--   referral; a recruiter triages it into a candidate (source=REFERRAL
--   or PARTNER_REFERRAL, optional immediate position attach) or
--   dismisses it. The referral row is the durable link between the
--   submitting employee and the eventual candidate — "My referrals"
--   derives its milestone status live from the candidate/application
--   state, never by mirroring pipeline terminals onto this row.
--
-- Tables:
--   recruitment_referrals — one row per submitted referral
--
-- Design notes:
--   * UUIDs are VARCHAR(36) — schema-wide convention (see V433 notes).
--   * referrer_uuid / triaged_by_useruuid are soft FKs to users
--     (module convention); candidate_uuid is a REAL FK inside the
--     module (V436 idiom) — set at triage, NULL while SUBMITTED/CLOSED.
--     ON DELETE RESTRICT: recruitment rows are never hard-deleted
--     (GDPR anonymizes).
--   * candidate_name / external_referrer_name / linkedin_url / email /
--     why_text are PII — named P19 anonymization targets alongside the
--     candidate row columns (spec §4.1 design note applies: the
--     referral is the pre-candidate intake record, split happens at
--     triage).
--   * Status semantics (locked in code + tests): SUBMITTED = awaiting
--     triage; TRIAGED = candidate created, no position attach at
--     triage; CONVERTED = candidate created AND application attached
--     at triage; CLOSED = dismissed at triage (closed_reason set).
--     Pipeline terminal-ness is NOT mirrored back onto this row —
--     display status derives live from candidate/application state.
--   * version is the optimistic lock (Hibernate @Version) guarding the
--     one-shot triage against concurrent recruiters (the future P14
--     Slack double-click): both transactions pass the plain
--     status='SUBMITTED' read, but the loser's versioned UPDATE matches
--     zero rows and its whole transaction — candidate creation included
--     — rolls back as a 409. Declared in the CREATE TABLE for fresh
--     schemas AND as ALTER ... IF NOT EXISTS below for databases where
--     an earlier run of this version already created the table.
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
-- Rollback: inert without the P6 backend image; the table is additive
--   and harmless to leave in place. Full removal (only if the
--   programme is abandoned):
--     DROP TABLE recruitment_referrals;
--     DELETE FROM page_registry WHERE page_key = 'recruitment-refer';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Referrals
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_referrals (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted referral identity',

    referrer_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK users.uuid — the submitting employee (X-Requested-By)',

    referrer_relation VARCHAR(20) NOT NULL
        COMMENT 'Java enum RecruitmentReferralRelation: COLLEAGUE | FORMER_COLLEAGUE | EXTERNAL_PARTNER | OTHER — how the referrer knows the person',

    external_referrer_name VARCHAR(200) NULL
        COMMENT 'PII. Set when the real reference is not the submitting employee (Airtable shows references are often external) — carried onto the candidate at triage',

    candidate_name VARCHAR(200) NOT NULL
        COMMENT 'PII. Full name as submitted — the recruiter splits it into first/last at triage',

    linkedin_url VARCHAR(500) NULL
        COMMENT 'PII. Optional profile link — saves the recruiter a search',

    email VARCHAR(255) NULL
        COMMENT 'PII. Optional — aids dedupe at triage',

    why_text TEXT NOT NULL
        COMMENT 'PII. The referrer''s context/why — free text, max 2000 chars (service-enforced)',

    candidate_uuid VARCHAR(36) NULL
        COMMENT 'FK recruitment_candidates.uuid — set at triage (CREATE_CANDIDATE leg); NULL while SUBMITTED and on CLOSED',

    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED'
        COMMENT 'Java enum RecruitmentReferralStatus: SUBMITTED | TRIAGED | CONVERTED | CLOSED. Pipeline state is never mirrored here — "My referrals" derives it live.',

    closed_reason VARCHAR(32) NULL
        COMMENT 'Java enum RecruitmentReferralClosedReason: DUPLICATE | NOT_RELEVANT | OTHER — mandatory on the DISMISS leg, NULL otherwise',

    submitted_at DATETIME(3) NOT NULL
        COMMENT 'UTC. Set at submission.',

    triaged_at DATETIME(3) NULL
        COMMENT 'UTC. Set when a recruiter triages (create or dismiss); NULL while SUBMITTED.',

    triaged_by_useruuid VARCHAR(36) NULL
        COMMENT 'Soft-FK users.uuid — the recruiter who triaged',

    version BIGINT NOT NULL DEFAULT 0
        COMMENT 'Optimistic lock (Hibernate @Version) — makes the one-shot triage race-safe',

    -- Audit columns (house Auditable pattern, V421)
    created_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL
        COMMENT 'users.uuid from X-Requested-By',
    modified_by VARCHAR(36) NULL
        COMMENT 'users.uuid from X-Requested-By',

    CONSTRAINT chk_rr_relation_enum
        CHECK (referrer_relation IN ('COLLEAGUE','FORMER_COLLEAGUE','EXTERNAL_PARTNER','OTHER')),
    CONSTRAINT chk_rr_status_enum
        CHECK (status IN ('SUBMITTED','TRIAGED','CONVERTED','CLOSED')),
    CONSTRAINT chk_rr_closed_reason_enum
        CHECK (closed_reason IS NULL
               OR closed_reason IN ('DUPLICATE','NOT_RELEVANT','OTHER')),

    CONSTRAINT fk_rr_candidate_uuid
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates (uuid) ON DELETE RESTRICT

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: employee referrals awaiting/after recruiter triage (spec §4.1/§5.2)';

-- Databases where an earlier run of this version already created the
-- table (CREATE ... IF NOT EXISTS skips it) still get the optimistic
-- lock column — idempotent on every re-run.
ALTER TABLE recruitment_referrals
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0
        COMMENT 'Optimistic lock (Hibernate @Version) — makes the one-shot triage race-safe'
        AFTER triaged_by_useruuid;

-- "My referrals" (per referrer, newest first), the recruiter triage
-- queue (SUBMITTED, oldest first) and "which referral produced this
-- candidate" are the three hot queries.
CREATE INDEX IF NOT EXISTS idx_rr_referrer ON recruitment_referrals (referrer_uuid, submitted_at);
CREATE INDEX IF NOT EXISTS idx_rr_status ON recruitment_referrals (status, submitted_at);
CREATE INDEX IF NOT EXISTS idx_rr_candidate ON recruitment_referrals (candidate_uuid);

-- -------------------------------------------------------------------
-- 2. Sidebar / route registration for /recruitment/refer.
--    Visible to ALL employees (USER role) — the only recruitment page
--    most employees ever see (spec §6.1). COMPANY section, next to
--    organization (110) and conferences (130). The registry row is UI
--    routing + sidebar visibility, not the security boundary.
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-refer', 'Refer a candidate', 1, '/recruitment/refer', 'USER', 135, 'COMPANY', 'Handshake', 0, NULL)
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
