-- ===================================================================
-- V448: Recruitment ATS expansion — Phase 19: GDPR engine
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P19)
-- Domain:  recruitmentservice (compliance automation)
--
-- Purpose:
--   1. recruitment_consents — token bookkeeping for the public
--      /consent/[token] page: token_expires_at (the SHA-256 token_hash
--      column exists since V436) plus a UNIQUE lookup index. The token
--      itself is never stored — only its hash; the sweep mints a token
--      when it sends a renewal email and stamps its expiry here.
--
--   2. Sweep indexes on recruitment_candidates — the nightly
--      recruitment-gdpr-sweep scans by retention_deadline (renewal
--      windows + auto-anonymization) and the DPO queue scans by
--      art14_deadline. Both columns exist since V435; the table is
--      small, but the indexes make the sweep's intent explicit and
--      keep it cheap forever.
--
--   3. Seed the two P19 email templates (Danish, editable on
--      /recruitment/settings, INSERT IGNORE so TA/DPO edits survive
--      re-runs):
--        CONSENT_RENEWAL  auto_send=1 — sent by the sweep at
--                         retention−30d and once more at −7d, with the
--                         tokenized {{consent_link}} merge field.
--        ART14_NOTICE     auto_send=0 — sent only by the explicit DPO
--                         "send notice" action (never by a reactor);
--                         auto_send=0 documents that posture.
--      DPO sign-off on the Danish copy is a P19 prerequisite (plan) —
--      the copy ships seeded but everything is dark behind
--      recruitment.gdpr.enabled=false, and the templates are editable
--      in the settings UI before flag-on.
--
--   4. Seed the sweep cadence parameters in app_settings (the V447
--      thresholds idiom — read per sweep, missing row = built-in
--      default, admin-tunable without redeploy):
--        recruitment.gdpr.renewal-first-days   (default 30)
--        recruitment.gdpr.renewal-second-days  (default 7)
--        recruitment.gdpr.art14-warning-days   (default 7)
--      Deliberately NOT settings: the 6-month retention and 12-month
--      consent periods. Those are the DPO-signed-off policy constants
--      (spec §5.5); changing them is a reviewed code change
--      (RecruitmentApplicationService.RETENTION_MONTHS / P19's
--      CONSENT_MONTHS), not an admin toggle.
--
--   5. Register /recruitment/gdpr in page_registry (DPO + ADMIN —
--      spec §6.1: the DPO exception queue).
--
--   Deliberately NOT touched: recruitment.gdpr.enabled. Seeded false
--   by V433; it was manually flipped in both environments on
--   2026-07-23 and turned OFF again pre-deploy (staging done in this
--   phase; production flip is a documented pre-promotion step).
--   Enabling it is the moment automatic deletion starts (plan §P19).
--
-- Collation: utf8mb4_general_ci — module convention since V315/V433.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is IF NOT EXISTS, template/settings seeds are INSERT IGNORE,
--   the page_registry seed is ON DUPLICATE KEY UPDATE (registry
--   convention, V430/V434/V438/V439/V446).
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: inert without the P19 images; everything is additive.
--   Full removal (only if the programme is abandoned):
--     ALTER TABLE recruitment_consents DROP COLUMN token_expires_at;
--     DROP INDEX uq_rcon_token_hash ON recruitment_consents;
--     DROP INDEX idx_rc_retention_deadline ON recruitment_candidates;
--     DROP INDEX idx_rc_art14_deadline ON recruitment_candidates;
--     DELETE FROM recruitment_email_templates
--       WHERE template_key IN ('CONSENT_RENEWAL','ART14_NOTICE')
--         AND created_by = 'migration-v448';
--     DELETE FROM app_settings WHERE setting_key LIKE 'recruitment.gdpr.%'
--       AND setting_key <> 'recruitment.gdpr.enabled';
--     DELETE FROM page_registry WHERE page_key = 'recruitment-gdpr';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Consent-token bookkeeping
-- -------------------------------------------------------------------
ALTER TABLE recruitment_consents
    ADD COLUMN IF NOT EXISTS token_expires_at DATETIME(3) NULL
        COMMENT 'UTC. The public consent-page token is refused after this moment. Stamped at mint (= the retention deadline at mint time); NULL when no live token exists.'
        AFTER token_hash;

-- Token → consent lookup for the public page; UNIQUE doubles as a
-- collision guard (multiple NULLs are allowed in MariaDB unique keys).
CREATE UNIQUE INDEX IF NOT EXISTS uq_rcon_token_hash
    ON recruitment_consents (token_hash);

-- -------------------------------------------------------------------
-- 2. Sweep indexes
-- -------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_rc_retention_deadline
    ON recruitment_candidates (retention_deadline);

CREATE INDEX IF NOT EXISTS idx_rc_art14_deadline
    ON recruitment_candidates (art14_deadline);

-- -------------------------------------------------------------------
-- 3. P19 email templates (Danish; DPO reviews/edits before flag-on)
-- -------------------------------------------------------------------
INSERT IGNORE INTO recruitment_email_templates
    (uuid, template_key, name, subject, body, auto_send, active,
     created_at, updated_at, created_by)
VALUES
    (UUID(), 'CONSENT_RENEWAL', 'Samtykke – fornyelse af kandidatbank',
     'Må vi fortsat gemme din profil hos Trustworks?',
     'Kære {{candidate_first_name}}\n\nDu er registreret i Trustworks\' kandidatbank, fordi du tidligere har været i kontakt med os om et job — og vi vil gerne kunne kontakte dig igen, hvis den rette mulighed dukker op.\n\nVores tilladelse til at gemme dine oplysninger udløber snart. Hvis du fortsat vil være med i kandidatbanken, skal du give dit samtykke via linket herunder — det tager under et minut:\n\n{{consent_link}}\n\nHvis du ikke foretager dig noget, sletter vi automatisk dine oplysninger ved fristens udløb. Du kan også bruge linket til at bede os slette dine oplysninger med det samme.\n\nHar du spørgsmål, kan du altid skrive til hr@trustworks.dk.\n\nMed venlig hilsen\nTrustworks',
     1, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v448'),

    (UUID(), 'ART14_NOTICE', 'Art. 14-underretning – oplysninger indsamlet fra andre',
     'Trustworks har registreret oplysninger om dig',
     'Kære {{candidate_first_name}}\n\nVi skriver til dig, fordi Trustworks A/S har registreret oplysninger om dig i forbindelse med rekruttering — typisk fordi en af vores medarbejdere har anbefalet dig, eller fordi vi selv er stødt på din profil (fx på LinkedIn).\n\nEfter databeskyttelsesforordningens artikel 14 skal vi oplyse dig om det:\n\n- Vi behandler almindelige oplysninger som navn, kontaktoplysninger og erhvervserfaring.\n- Formålet er at vurdere et muligt jobmatch hos Trustworks.\n- Retsgrundlaget er vores legitime interesse i rekruttering (artikel 6, stk. 1, litra f).\n- Oplysningerne slettes senest 6 måneder efter, at en eventuel rekrutteringsproces er afsluttet, medmindre du giver samtykke til, at vi gemmer dem længere.\n\nDu kan til enhver tid få indsigt i, rette eller få slettet dine oplysninger — skriv blot til hr@trustworks.dk. Du kan også klage til Datatilsynet.\n\nHvis du ikke ønsker at være registreret hos os, sletter vi naturligvis dine oplysninger med det samme.\n\nMed venlig hilsen\nTrustworks',
     0, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v448');

-- -------------------------------------------------------------------
-- 4. Sweep cadence parameters (V447 thresholds idiom)
-- -------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.gdpr.renewal-first-days',  '30', 'recruitment'),
    ('recruitment.gdpr.renewal-second-days',  '7', 'recruitment'),
    ('recruitment.gdpr.art14-warning-days',   '7', 'recruitment');

-- -------------------------------------------------------------------
-- 5. Sidebar / route registration for /recruitment/gdpr (DPO surface)
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-gdpr', 'GDPR', 1, '/recruitment/gdpr', 'DPO,ADMIN', 875, 'ADMIN', 'ShieldCheck', 0, NULL)
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
