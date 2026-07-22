-- ===================================================================
-- V436: Recruitment ATS expansion — Phase 4: applications (API-level)
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P4, spec §4.1/§4.2)
-- Domain:  recruitmentservice (applications aggregate + consents)
--
-- Purpose:
--   The pipeline data model goes live. An application is the join of a
--   candidate and a position with a stage machine on top: stage moves
--   follow the position's stage_set, terminal is the ONLY removal
--   mechanism, and every mutation appends an event.
--
-- Tables:
--   recruitment_applications        — one row per candidate↔position
--                                     pipeline run (stage + terminal)
--   recruitment_application_answers — the candidate's own long-form
--                                     form answers (P5 writes, P8
--                                     reads; a named anonymization
--                                     target — spec §4.1 design notes)
--   recruitment_consents            — dated consent records (P4 creates
--                                     REQUESTED rows on return-to-pool;
--                                     the consent page/tokens are P19)
--
-- Design notes:
--   * UUIDs are VARCHAR(36) — schema-wide convention (see V433 notes).
--   * candidate_uuid / position_uuid are REAL FKs inside the module
--     (same idiom as V434's practice FK): an application without its
--     candidate or position is meaningless. ON DELETE RESTRICT —
--     recruitment rows are never hard-deleted (GDPR anonymizes).
--   * assigned_team_uuid is a soft FK (module convention) and accepts
--     ANY team — teams are temporally practice-assigned and may be
--     practice-less; the position's practice is a grouping attribute,
--     never a constraint on team choice (spec §4.1).
--   * stage + terminal are separate columns: stage is where the
--     application was in the pipeline; terminal (REJECTED | WITHDRAWN
--     | RETURNED_TO_POOL) marks why it left. HIRED is a stage, only
--     reachable via the signing-completion bridge (P10), never this API.
--   * stage_entered_at powers idle detection (spec §8.10) and the P7
--     board's idle chips.
--   * recruitment_consents.token_hash / expires_at stay NULL until the
--     P19 consent page mints tokens; until then pool consent is handled
--     manually by the recruiter/DPO (documented plan §P4 limitation).
--   * Audit columns follow the house Auditable pattern (V421),
--     populated by AuditEntityListener from X-Requested-By.
--
-- Collation: utf8mb4_general_ci (V315 lesson — JOINs against legacy
--   tables fail on unicode_ci with "Illegal mix of collations").
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   all DDL is IF NOT EXISTS.
--
-- Author: Claude Code
-- Date:   2026-07-22
-- Rollback: inert without the P4 backend image; tables are additive
--   and harmless to leave in place. Full removal (only if the
--   programme is abandoned):
--     DROP TABLE recruitment_application_answers;
--     DROP TABLE recruitment_applications;
--     DROP TABLE recruitment_consents;
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Applications (candidate ↔ position pipeline runs)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_applications (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted application identity',

    candidate_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_candidates.uuid — the applying candidate',

    position_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_positions.uuid — the position applied to',

    stage VARCHAR(20) NOT NULL
        COMMENT 'Java enum RecruitmentStage: SCREENING | INTERVIEW_1 | INTERVIEW_2 | INTERVIEW_3 | OFFER | HIRED. Moves follow the position stage_set; HIRED only via the P10 signing bridge.',

    terminal VARCHAR(20) NULL
        COMMENT 'Java enum RecruitmentApplicationTerminal: REJECTED | WITHDRAWN | RETURNED_TO_POOL. NULL = open. Terminal is the ONLY removal mechanism (spec §4.2).',

    rejection_reason_code VARCHAR(40) NULL
        COMMENT 'Java enum RecruitmentRejectionReason — mandatory when terminal=REJECTED; coded for reporting, free-text elaboration lives in the event pii block',

    assigned_team_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK team.uuid — set by TEAM_ASSIGNED at OFFER on practice-level positions. Any team is valid (cross-practice / practice-less included).',

    expected_start_date DATE NULL
        COMMENT 'Airtable Ansaettelsesdato — set at OFFER; feeds demand planning (spec Appendix A)',

    stage_entered_at DATETIME(3) NOT NULL
        COMMENT 'UTC. Updated on every stage move — idle detection ("how long has this been stuck")',

    -- Audit columns (house Auditable pattern, V421)
    created_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL
        COMMENT 'users.uuid from X-Requested-By',
    modified_by VARCHAR(36) NULL
        COMMENT 'users.uuid from X-Requested-By',

    CONSTRAINT chk_ra_stage_enum
        CHECK (stage IN ('SCREENING','INTERVIEW_1','INTERVIEW_2','INTERVIEW_3','OFFER','HIRED')),
    CONSTRAINT chk_ra_terminal_enum
        CHECK (terminal IS NULL
               OR terminal IN ('REJECTED','WITHDRAWN','RETURNED_TO_POOL')),

    CONSTRAINT fk_ra_candidate_uuid
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates (uuid) ON DELETE RESTRICT,
    CONSTRAINT fk_ra_position_uuid
        FOREIGN KEY (position_uuid) REFERENCES recruitment_positions (uuid) ON DELETE RESTRICT

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: applications — candidate↔position pipeline runs (spec §4.1/§4.2)';

-- "This candidate's applications" (list rows, profile) and "this
-- position's board grouped by stage" (P7) are the two hot queries.
CREATE INDEX IF NOT EXISTS idx_ra_candidate ON recruitment_applications (candidate_uuid);
CREATE INDEX IF NOT EXISTS idx_ra_position_stage ON recruitment_applications (position_uuid, stage);

-- -------------------------------------------------------------------
-- 2. Application answers (the candidate's own long-form form answers)
--    P5's public form writes them; P8's Application tab reads them.
--    The deliberate state-table PII exception (spec §4.1 design note):
--    a named anonymization target next to candidate PII columns, event
--    pii sections and S3 objects.
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_application_answers (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted answer identity',

    application_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_applications.uuid',

    question_key VARCHAR(40) NOT NULL
        COMMENT 'Stable question code: WHY_TRUSTWORKS | BEST_TASKS | DNA_MATCH | STRENGTHS | ... — reporting/display never interprets question wording',

    answer TEXT NULL
        COMMENT 'PII. The candidate''s own words — scrubbed by the P19 anonymizer',

    created_at DATETIME(3) NOT NULL
        COMMENT 'UTC',

    UNIQUE KEY uk_raa_application_question (application_uuid, question_key),

    CONSTRAINT fk_raa_application_uuid
        FOREIGN KEY (application_uuid) REFERENCES recruitment_applications (uuid) ON DELETE RESTRICT

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: per-application form answers (PII — anonymization target, spec §4.1)';

-- -------------------------------------------------------------------
-- 3. Consents (dated, renewable, withdrawable consent records)
--    P4 creates REQUESTED rows when an application is returned to the
--    pool (silver medalist — spec §4.2 terminal note). Tokens, the
--    public /consent/[token] page and the expiry clock arrive in P19.
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_consents (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted consent identity',

    candidate_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_candidates.uuid',

    kind VARCHAR(30) NOT NULL
        COMMENT 'Java enum RecruitmentConsentKind: TALENT_POOL_RETENTION (the only kind in v1)',

    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        COMMENT 'Java enum RecruitmentConsentStatus: REQUESTED | GRANTED | WITHDRAWN | EXPIRED',

    granted_at DATETIME(3) NULL
        COMMENT 'UTC. Set when the candidate grants (P5 form checkbox / P19 consent page)',

    expires_at DATETIME(3) NULL
        COMMENT 'UTC. granted_at + 12 months — maintained from P19',

    token_hash VARCHAR(64) NULL
        COMMENT 'SHA-256 of the public consent-page token (P19). NULL until a token is minted.',

    -- Audit columns (house Auditable pattern, V421)
    created_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL
        COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL
        COMMENT 'users.uuid from X-Requested-By (SYSTEM flows use the acting user of the command)',
    modified_by VARCHAR(36) NULL
        COMMENT 'users.uuid from X-Requested-By',

    CONSTRAINT chk_rcon_kind_enum
        CHECK (kind IN ('TALENT_POOL_RETENTION')),
    CONSTRAINT chk_rcon_status_enum
        CHECK (status IN ('REQUESTED','GRANTED','WITHDRAWN','EXPIRED')),

    CONSTRAINT fk_rcon_candidate_uuid
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates (uuid) ON DELETE RESTRICT

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: consent records (P4 creates REQUESTED on return-to-pool; engine is P19)';

-- The P19 GdprClock scans by candidate and by status.
CREATE INDEX IF NOT EXISTS idx_rcon_candidate ON recruitment_consents (candidate_uuid);
CREATE INDEX IF NOT EXISTS idx_rcon_status ON recruitment_consents (status);
