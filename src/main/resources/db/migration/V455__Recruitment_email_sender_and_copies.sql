-- ===================================================================
-- V455: Recruitment ATS expansion — candidate-email sender identity,
--       reply routing and internal copies (CC/BCC)
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P15 follow-up —
--          the email loose-ends round agreed with the programme owner
--          on 2026-07-24)
-- Domain:  communicationsservice (the shared mail outbox) +
--          recruitmentservice (template copy policy, review queue)
--
-- Purpose:
--   1. mail — the shared outbox row grows the four fields the queued
--      send path has always dropped on the floor:
--        from_name  display name in front of quarkus.mailer.from
--        reply_to   where a reply actually goes
--        cc / bcc   internal copies
--      Until now only MailResource.sendWithAttachments (the IMMEDIATE
--      path) honoured Reply-To, because TrustworksMail.replyTo was
--      @Transient — which is why the dossier review email gives up the
--      outbox's retry just to keep a reply address. Every column is
--      NULLABLE with no default: every existing caller (payslips,
--      expenses, conference mails, bulk mail) is byte-identical after
--      this migration.
--
--   2. mail.subject VARCHAR(255) -> VARCHAR(300). The recruitment
--      module validates subjects at 300 characters in three places
--      (RecruitmentEmailService.SUBJECT_MAX_LENGTH, both recruitment
--      tables below, the compose dialog's maxLength) but the outbox
--      column was 255. Production sql_mode carries STRICT_TRANS_TABLES,
--      so a 256-300 character subject validated, rendered and previewed
--      fine and then died at mail.persist() with error 1406 — a 500 on
--      the manual path, a silently SKIPPED delivery on the reactor path.
--      Widening the column is the cheapest fix and makes all four
--      numbers agree. (Longest subject ever sent in production: 75.)
--
--   3. recruitment_email_templates — per-template copy policy:
--        copy_roles  which internal people are copied (CSV of
--                    INTERVIEWERS | SENDER | HIRING_OWNER; '' = nobody)
--        copy_mode   BCC (invisible to the candidate — the default) or
--                    CC (visible; opt-in per template)
--      BCC is the default because a rejection that openly CCs the
--      interview panel tells the candidate exactly who judged them and
--      invites a Reply-All into an internal thread. CC exists for the
--      templates where naming people IS the message.
--
--   4. recruitment_pending_emails — the resolved copy list is snapshotted
--      at queue time next to the rendered subject/body, the same
--      philosophy as §P15 deviation 5: approving sends what the
--      recruiter reviewed, even if the template's policy changed since.
--      Holds user UUIDs, never addresses — no new PII surface for P19.
--
--   5. app_settings — recruitment.email.reply-to-fallback, the Reply-To
--      used when no human sent the mail (the reactor's acknowledgements
--      and auto-rejections, the GDPR sweep's consent renewals). Seeded
--      to hr@trustworks.dk, which the seeded ACKNOWLEDGEMENT body
--      already names in its prose — the header and the text finally
--      agree. Editable on /recruitment/settings; blank means no
--      Reply-To at all (back to today's behaviour).
--
--   No new recruitment_* TABLE, so V450's staging-sync exclusion list
--   needs no extension — the two tables touched here are already on it.
--
-- Collation: inherited from the existing tables (utf8mb4_general_ci for
--   the recruitment tables, utf8mb4_general_ci for mail).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   every DDL statement is IF NOT EXISTS / IF EXISTS (MariaDB 10.11
--   supports both on ALTER TABLE), the seed is INSERT IGNORE against the
--   uq_app_settings_key unique key, so an operator's later edit of the
--   fallback address survives a re-run.
--
-- Rollback (manual):
--     ALTER TABLE mail
--         DROP COLUMN IF EXISTS bcc,
--         DROP COLUMN IF EXISTS cc,
--         DROP COLUMN IF EXISTS reply_to,
--         DROP COLUMN IF EXISTS from_name,
--         MODIFY COLUMN subject VARCHAR(255) NULL;
--     ALTER TABLE recruitment_email_templates
--         DROP COLUMN IF EXISTS copy_mode,
--         DROP COLUMN IF EXISTS copy_roles;
--     ALTER TABLE recruitment_pending_emails
--         DROP COLUMN IF EXISTS copy_mode,
--         DROP COLUMN IF EXISTS copy_user_uuids;
--     DELETE FROM app_settings
--      WHERE setting_key = 'recruitment.email.reply-to-fallback';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1 + 2. The shared mail outbox
-- -------------------------------------------------------------------
ALTER TABLE mail
    MODIFY COLUMN IF EXISTS subject VARCHAR(300) DEFAULT NULL
        COMMENT 'Widened from 255 by V455 to match RecruitmentEmailService.SUBJECT_MAX_LENGTH and both recruitment email tables',

    ADD COLUMN IF NOT EXISTS from_name VARCHAR(120) DEFAULT NULL
        COMMENT 'Optional display name rendered in front of quarkus.mailer.from ("Trustworks Rekruttering <no-reply@trustworks.dk>"). NULL = the bare configured address, i.e. every pre-V455 caller unchanged. The envelope address is never overridden — SES verifies identities, and a per-recruiter From would risk a 554.',

    ADD COLUMN IF NOT EXISTS reply_to VARCHAR(255) DEFAULT NULL
        COMMENT 'Where a reply goes. NULL = no Reply-To header (pre-V455 behaviour). Recruitment sets the acting recruiter, falling back to app_settings recruitment.email.reply-to-fallback.',

    ADD COLUMN IF NOT EXISTS cc VARCHAR(1000) DEFAULT NULL
        COMMENT 'Comma-separated visible copies. Personal data (employee addresses) — P19 scrubs recruitment rows via the mail-retention question recorded in findings §P15.',

    ADD COLUMN IF NOT EXISTS bcc VARCHAR(1000) DEFAULT NULL
        COMMENT 'Comma-separated invisible copies. Same personal-data note as cc.';

-- -------------------------------------------------------------------
-- 3. Per-template copy policy
-- -------------------------------------------------------------------
ALTER TABLE recruitment_email_templates
    ADD COLUMN IF NOT EXISTS copy_roles VARCHAR(120) NOT NULL DEFAULT ''
        COMMENT 'CSV of RecruitmentEmailCopyRole: INTERVIEWERS | SENDER | HIRING_OWNER. Empty = copy nobody (the default, and what every pre-V455 template keeps). Resolved to people at send time and filtered through canReadCandidateProfile, so a copy can never reveal a candidate the recipient may not read.',

    ADD COLUMN IF NOT EXISTS copy_mode VARCHAR(3) NOT NULL DEFAULT 'BCC'
        COMMENT 'BCC = invisible to the candidate (default, and the only safe choice for rejections); CC = visible, for templates where naming the people IS the message.';

-- -------------------------------------------------------------------
-- 4. Review-queue snapshot of the resolved copy list
-- -------------------------------------------------------------------
ALTER TABLE recruitment_pending_emails
    ADD COLUMN IF NOT EXISTS copy_user_uuids VARCHAR(1000) DEFAULT NULL
        COMMENT 'CSV of users.uuid resolved at queue time (snapshot semantics — §P15 deviation 5). UUIDs only, never addresses: no new PII surface for the P19 scrub.',

    ADD COLUMN IF NOT EXISTS copy_mode VARCHAR(3) NOT NULL DEFAULT 'BCC'
        COMMENT 'Copy mode snapshot at queue time; the approver may override it before sending.';

-- -------------------------------------------------------------------
-- 5. Reply-To fallback for sends with no human actor
-- -------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category, updated_by)
VALUES ('recruitment.email.reply-to-fallback', 'hr@trustworks.dk', 'recruitment', 'migration-v452');
