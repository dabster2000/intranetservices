-- ===================================================================
-- V435: Recruitment ATS expansion — Phase 3: candidates & talent pool
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P3, spec §4.1)
-- Domain:  recruitmentservice (candidates aggregate, extended in place)
--
-- Purpose:
--   Extends recruitment_candidates (V311, dossier feature) into the ATS
--   candidate/talent-pool aggregate. The table is EXTENDED, not
--   replaced — every add is nullable and the existing dossier flow
--   keeps working untouched (spec §4.1 design note).
--
-- Changes:
--   1. 21 nullable columns: sourcing, pool, qualifications, GDPR
--      bookkeeping (data only — the clock reactor arrives in P19).
--   2. email / target_company_uuid relaxed to NULL: LinkedIn paste
--      imports know only a name, and talent-pool candidates have no
--      target company until an application exists (P4). The dossier
--      create path still requires both — enforced in CandidateService.
--   3. status CHECK extended with POOLED / ANONYMIZED (existing values
--      untouched; DECLINED naming kept backward-compatible, spec §4.1).
--   4. Per-practice specialization catalogs seeded in app_settings,
--      keyed by practice UUID (registry idiom — resolved via SELECT
--      from practice so environment-specific UUIDs are never
--      hardcoded). A practice without a catalog row simply hides the
--      specialization picker in the UI.
--   (page_registry for /recruitment is deliberately NOT touched — see
--   the note at the bottom of this file.)
--
-- NOTE on the plan's "name split": V311 already created first_name /
--   last_name — there was never a single name column, so no split or
--   backfill is needed (recorded in findings §P3).
--
-- Collation: table is utf8mb4_general_ci since V315; new columns inherit.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS + re-ADD (the
--   pair is re-runnable), CREATE INDEX IF NOT EXISTS, INSERT IGNORE.
--
-- Author: Claude Code
-- Date:   2026-07-22
-- Rollback: columns are additive and harmless to leave in place. Full
--   removal (only if the programme is abandoned): drop the 21 columns,
--   restore the V311 CHECKs, and DELETE the recruitment.specializations.%
--   app_settings rows.
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Sourcing & referral columns
-- -------------------------------------------------------------------
ALTER TABLE recruitment_candidates
    ADD COLUMN IF NOT EXISTS linkedin_url VARCHAR(500) NULL
        COMMENT 'PII. Pasted LinkedIn profile URL (no LinkedIn API); dedupe compares normalized /in/ slugs',
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NULL
        COMMENT 'Java enum CandidateSource: REFERRAL | PARTNER_REFERRAL | LINKEDIN_SEARCH | LINKEDIN_AD | WEBSITE | JOBINDEX | SOME | CONFERENCE | TW_EVENT | OTHER. Mandatory for ATS creates; NULL only on legacy dossier-flow rows.',
    ADD COLUMN IF NOT EXISTS source_detail JSON NULL
        COMMENT 'Structured sub-source (PII inside: reference names). Shape depends on source: {channel}, {eventName}, {jobListingRef}, {referenceName}',
    ADD COLUMN IF NOT EXISTS referred_by_user_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK users.uuid — internal referrer (source REFERRAL/PARTNER_REFERRAL)',
    ADD COLUMN IF NOT EXISTS external_referrer_name VARCHAR(200) NULL
        COMMENT 'PII. Non-employee reference name (references are often NOT employees)',
    ADD COLUMN IF NOT EXISTS sponsoring_partner_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK users.uuid — partner-referral mandate; drives the P4 reject-block for non-recruiters',
    ADD COLUMN IF NOT EXISTS relevant_teamlead_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK users.uuid — triage routing for pool candidates without a position';

-- -------------------------------------------------------------------
-- 2. Pool & qualification columns (pool rediscovery is a primary use
--    case for a referral/sourcing-heavy funnel — spec §4.1)
-- -------------------------------------------------------------------
ALTER TABLE recruitment_candidates
    ADD COLUMN IF NOT EXISTS pool_status VARCHAR(20) NULL
        COMMENT 'Java enum CandidatePoolStatus: PROSPECT | CONTACTED | INTERESTED | NOT_NOW | SILVER_MEDALIST. Set while status=POOLED.',
    ADD COLUMN IF NOT EXISTS tags JSON NULL
        COMMENT 'Free-form string tags, e.g. ["cleared","senior-pm"]. JSON array.',
    ADD COLUMN IF NOT EXISTS education_level VARCHAR(20) NULL
        COMMENT 'Java enum CandidateEducationLevel: STUDENT | BACHELOR | MASTER | PHD | OTHER',
    ADD COLUMN IF NOT EXISTS education_other VARCHAR(200) NULL
        COMMENT 'Free text when education_level=OTHER',
    ADD COLUMN IF NOT EXISTS experience_level VARCHAR(20) NULL
        COMMENT 'Java enum CandidateExperienceLevel: GRADUATE | JUNIOR | MID | SENIOR | PRINCIPAL',
    ADD COLUMN IF NOT EXISTS specializations JSON NULL
        COMMENT 'Practice-scoped role tags from the per-practice catalog in app_settings (recruitment.specializations.<practice_uuid>), e.g. ["Projektleder"]',
    ADD COLUMN IF NOT EXISTS security_clearance VARCHAR(10) NULL
        COMMENT 'Java enum CandidateSecurityClearance: NONE | PENDING | CLEARED — Cyber Security relevance',
    ADD COLUMN IF NOT EXISTS security_relevant TINYINT(1) NULL
        COMMENT '1 = candidate open to clearance-required work';

-- -------------------------------------------------------------------
-- 3. GDPR bookkeeping columns (data only in P3; the GdprClock reactor
--    and anonymizer arrive in P19)
-- -------------------------------------------------------------------
ALTER TABLE recruitment_candidates
    ADD COLUMN IF NOT EXISTS lawful_basis VARCHAR(30) NULL
        COMMENT 'Java enum CandidateLawfulBasis: LEGITIMATE_INTEREST | CONSENT. Set LEGITIMATE_INTEREST at ATS create; CONSENT via P19 consent flows.',
    ADD COLUMN IF NOT EXISTS retention_deadline DATETIME(3) NULL
        COMMENT 'UTC. Maintained by the P19 GdprClock; NULL until a retention clock starts',
    ADD COLUMN IF NOT EXISTS art14_required TINYINT(1) NULL
        COMMENT '1 = data was NOT collected from the candidate (sourced/referred) — GDPR Art. 14 notice required',
    ADD COLUMN IF NOT EXISTS art14_deadline DATETIME(3) NULL
        COMMENT 'UTC. created_at + 30d when sourced/referred; satisfied by ART14_NOTICE_SENT (P19)',
    ADD COLUMN IF NOT EXISTS process_ended_at DATETIME(3) NULL
        COMMENT 'UTC. Set when the LAST open application reaches a terminal stage (P4) — starts the 6-month retention clock',
    ADD COLUMN IF NOT EXISTS anonymized_at DATETIME(3) NULL
        COMMENT 'UTC. Set by the P19 anonymizer; row PII columns are rewritten at that moment';

-- -------------------------------------------------------------------
-- 4. Relax NOT NULLs the ATS paths cannot satisfy (dossier path keeps
--    requiring both at the service layer). MODIFY restates the full
--    definition — comments preserved from V311.
-- -------------------------------------------------------------------
ALTER TABLE recruitment_candidates
    MODIFY COLUMN email VARCHAR(255) NULL
        COMMENT 'PII. Candidate email; NULL allowed for LinkedIn imports / pool candidates. Required by the dossier flow (service-enforced).',
    MODIFY COLUMN target_company_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK to companies.uuid. NULL for talent-pool candidates without a dossier; required by the dossier flow (service-enforced).';

-- The V311 email CHECK rejected empty strings on a NOT NULL column;
-- re-shape it to allow NULL but still reject blank strings.
ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_email_not_empty;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_email_not_empty
        CHECK (email IS NULL OR CHAR_LENGTH(TRIM(email)) > 0);

-- -------------------------------------------------------------------
-- 5. Status vocabulary: + POOLED (in the pool, not in a process) and
--    + ANONYMIZED (P19 target state). Existing values untouched.
-- -------------------------------------------------------------------
ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_status_enum;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_status_enum
        CHECK (status IN ('ACTIVE','POOLED','HIRED','DECLINED','WITHDRAWN','ANONYMIZED'));

-- New enum guards (all allow NULL — every P3 column is nullable).
ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_pool_status_enum;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_pool_status_enum
        CHECK (pool_status IS NULL
               OR pool_status IN ('PROSPECT','CONTACTED','INTERESTED','NOT_NOW','SILVER_MEDALIST'));

ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_source_enum;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_source_enum
        CHECK (source IS NULL
               OR source IN ('REFERRAL','PARTNER_REFERRAL','LINKEDIN_SEARCH','LINKEDIN_AD',
                             'WEBSITE','JOBINDEX','SOME','CONFERENCE','TW_EVENT','OTHER'));

ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_education_enum;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_education_enum
        CHECK (education_level IS NULL
               OR education_level IN ('STUDENT','BACHELOR','MASTER','PHD','OTHER'));

ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_experience_enum;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_experience_enum
        CHECK (experience_level IS NULL
               OR experience_level IN ('GRADUATE','JUNIOR','MID','SENIOR','PRINCIPAL'));

ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_clearance_enum;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_clearance_enum
        CHECK (security_clearance IS NULL
               OR security_clearance IN ('NONE','PENDING','CLEARED'));

ALTER TABLE recruitment_candidates
    DROP CONSTRAINT IF EXISTS chk_rc_lawful_basis_enum;
ALTER TABLE recruitment_candidates
    ADD CONSTRAINT chk_rc_lawful_basis_enum
        CHECK (lawful_basis IS NULL
               OR lawful_basis IN ('LEGITIMATE_INTEREST','CONSENT'));

-- -------------------------------------------------------------------
-- 6. Indexes for the pool/source list filters (P8's saved views reuse
--    them). Email dedupe uses idx_rc_email from V311.
-- -------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_rc_pool_status ON recruitment_candidates (pool_status);
CREATE INDEX IF NOT EXISTS idx_rc_source      ON recruitment_candidates (source);

-- -------------------------------------------------------------------
-- 7. Per-practice specialization catalogs (spec §4.1: "catalog per
--    practice lives in settings, keyed by practice uuid"). Keyed by
--    the registry UUID resolved per environment via SELECT — never a
--    hardcoded uuid, never a code. Starter sets mirror the Airtable
--    per-practice faglighed selects; admins edit the JSON on the
--    /settings page. INSERT IGNORE never clobbers an admin's edit.
--    UD (the "no practice" sentinel) and retired practices get no
--    catalog — the picker stays hidden for them.
-- -------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
SELECT CONCAT('recruitment.specializations.', p.uuid),
       '["Projektleder","Programleder","PMO-konsulent","Scrum Master"]',
       'recruitment'
FROM practice p WHERE p.code = 'PM';

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
SELECT CONCAT('recruitment.specializations.', p.uuid),
       '["IT-arkitekt","Løsningsarkitekt","Enterprise-arkitekt"]',
       'recruitment'
FROM practice p WHERE p.code = 'IA';

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
SELECT CONCAT('recruitment.specializations.', p.uuid),
       '["Forretningsudvikler","Forretningsanalytiker","Proceskonsulent"]',
       'recruitment'
FROM practice p WHERE p.code = 'BU';

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
SELECT CONCAT('recruitment.specializations.', p.uuid),
       '["Udvikler","DevOps-konsulent","Data engineer","Testkonsulent"]',
       'recruitment'
FROM practice p WHERE p.code = 'TECH';

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
SELECT CONCAT('recruitment.specializations.', p.uuid),
       '["Sikkerhedskonsulent","GRC-konsulent","Security-arkitekt"]',
       'recruitment'
FROM practice p WHERE p.code = 'CYB';

-- NOTE deliberately absent: no page_registry change for /recruitment.
-- Its required_roles were admin-edited at runtime (live value
-- 'ADMIN,HR,TECHPARTNER' differs from the V318 seed) — a migration
-- UPDATE would clobber that governance. The BFF requireRoles retro-fit
-- shipped with this phase gates on the union
-- ['ADMIN','HR','CXO','TECHPARTNER'] so the current audience keeps
-- working and the spec's recruiter tier (HR/CXO) is included; sidebar
-- visibility for CXO stays an admin decision on /settings.
