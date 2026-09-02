-- ===================================================================
-- V559: Recruitment referrals — optional CV attachment
-- ===================================================================
-- Feature: "Refer a candidate" (/recruitment/refer) may now carry a CV
-- Domain:  recruitmentservice (referrals aggregate)
--
-- Purpose:
--   An employee referring someone often already holds their CV. Before
--   this, the only way to get it to the recruiter was e-mail — outside
--   the ATS, outside the audit trail and outside the GDPR sweeps. The
--   referral row now carries an OPTIONAL pointer to one stored file so
--   the recruiter can read it while triaging, and so the file becomes
--   an ordinary candidate document the moment the referral is triaged
--   into a candidate.
--
-- Tables:
--   recruitment_referrals — five additive nullable columns
--
-- Design notes:
--   * The bytes live in S3 next to every other recruitment file
--     (`files` row, `relateduuid` = THIS referral's uuid while the
--     referral is pre-candidate). At triage CREATE_CANDIDATE the file's
--     relateduuid is re-pointed at the new candidate, which is what
--     puts it on the P8 Documents tab and inside the GDPR anonymizer's
--     `deleteAllCandidateFiles` reach. At triage DISMISS the object is
--     deleted outright — no candidate will ever exist, so there is no
--     basis to keep a third party's CV.
--   * cv_filename is PII (it routinely contains the person's name) and
--     joins candidate_name / linkedin_url / email / why_text /
--     external_referrer_name as an anonymization target
--     (RecruitmentAnonymizerService.scrubReferrals).
--   * cv_file_uuid is a soft FK to files.uuid — the `files` table is the
--     shared file store outside this module, so the module convention
--     (real FKs only inside the module, V436 idiom) makes it soft.
--   * No CHECK on cv_content_type: the allowlist is enforced in code
--     (PublicApplyDocuments.ALLOWED_MIME_TYPES + magic-byte check), the
--     same guard the public apply forms and the Documents-tab upload
--     already share. A DB constraint here would only duplicate it and
--     fossilise the list.
--
-- Collation: table default (utf8mb4_general_ci, V438).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   all DDL is IF NOT EXISTS.
--
-- Author: Claude Code
-- Date:   2026-09-02
-- Rollback: inert without the accompanying backend image (the columns
--   stay NULL). Full removal:
--     ALTER TABLE recruitment_referrals
--       DROP COLUMN cv_file_uuid, DROP COLUMN cv_filename,
--       DROP COLUMN cv_content_type, DROP COLUMN cv_size_bytes,
--       DROP COLUMN cv_uploaded_at;
-- ===================================================================

ALTER TABLE recruitment_referrals
    ADD COLUMN IF NOT EXISTS cv_file_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK files.uuid — the CV the referrer attached; NULL when none. relateduuid points at this referral until triage re-points it at the candidate.'
        AFTER why_text;

ALTER TABLE recruitment_referrals
    ADD COLUMN IF NOT EXISTS cv_filename VARCHAR(255) NULL
        COMMENT 'PII. Sanitised original filename of the attached CV — anonymization target alongside candidate_name.'
        AFTER cv_file_uuid;

ALTER TABLE recruitment_referrals
    ADD COLUMN IF NOT EXISTS cv_content_type VARCHAR(100) NULL
        COMMENT 'Stored MIME type of the attached CV (application/pdf | image/jpeg | image/png).'
        AFTER cv_filename;

ALTER TABLE recruitment_referrals
    ADD COLUMN IF NOT EXISTS cv_size_bytes INT NULL
        COMMENT 'Size in bytes of the attached CV — shown in the triage queue.'
        AFTER cv_content_type;

ALTER TABLE recruitment_referrals
    ADD COLUMN IF NOT EXISTS cv_uploaded_at DATETIME(3) NULL
        COMMENT 'UTC. When the CV was attached (always just after submission — the form uploads it as a second step).'
        AFTER cv_size_bytes;
