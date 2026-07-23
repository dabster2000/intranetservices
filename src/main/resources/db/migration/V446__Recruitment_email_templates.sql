-- ===================================================================
-- V446: Recruitment ATS expansion — Phase 15: candidate emails &
--       templates
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P15)
-- Domain:  recruitmentservice (candidate communication)
--
-- Purpose:
--   1. recruitment_email_templates — Danish candidate-email templates
--      with merge fields ({{candidate_first_name}}, {{position_title}},
--      ...). template_key is the stable trigger identity: the reserved
--      keys ACKNOWLEDGEMENT / REJECTION_SCREENING /
--      REJECTION_POST_INTERVIEW / STAGE_<stage> are picked up by the
--      CandidateMailerReactor; any other key is a manual-send-only
--      template. auto_send=0 means review-first: the reactor queues the
--      rendered email for recruiter approval instead of sending.
--
--   2. recruitment_pending_emails — the review-before-send queue.
--      Rows hold the RENDERED subject/body/recipient snapshot (personal
--      data — P19's anonymizer must scrub or delete a candidate's
--      PENDING rows). One-shot: PENDING → APPROVED (mail row + an
--      EMAIL_SENT event) or PENDING → DISMISSED.
--
--   3. Seed the three templates the plan's DoD requires, in Danish,
--      editable on /recruitment/settings:
--        ACKNOWLEDGEMENT          auto_send=1 (public applications)
--        REJECTION_SCREENING      auto_send=1 (early, low-stakes)
--        REJECTION_POST_INTERVIEW auto_send=0 (late-stage default
--                                 review-first per plan §P15)
--      Partner-referral rejections NEVER auto-send regardless of the
--      template's auto_send value — enforced in the reactor, not here.
--
--   4. Register /recruitment/settings in page_registry (recruiter-tier
--      page: template management + Slack channel routing — the P12
--      carry-over finally lands its UI).
--
-- Design notes:
--   * Template bodies are PLAIN TEXT with merge fields; the mailer
--     HTML-escapes and converts newlines at send time. No HTML
--     authoring surface (Outlook-safe, injection-safe).
--   * trigger_event_uuid + template_key carry a UNIQUE key as
--     belt-and-braces against duplicate queue rows for the same event;
--     the reactor chassis' durable delivery dedupe is the primary
--     idempotency guarantee (P1).
--   * FK to recruitment_candidates: a pending email never outlives its
--     candidate row (anonymization keeps the row, so RESTRICT is safe).
--
-- Collation: utf8mb4_general_ci — module convention since V315/V433.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is IF NOT EXISTS, template seeds are INSERT IGNORE (never
--   clobber TA edits), the page_registry seed is ON DUPLICATE KEY
--   UPDATE (registry convention, V430/V434/V438/V439).
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: inert without the P15 images; tables are additive.
--   Full removal (only if the programme is abandoned):
--     DROP TABLE recruitment_pending_emails;
--     DROP TABLE recruitment_email_templates;
--     DELETE FROM page_registry WHERE page_key = 'recruitment-settings';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Email templates
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_email_templates (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted template identity',

    template_key VARCHAR(60) NOT NULL
        COMMENT 'Stable identity + trigger: ACKNOWLEDGEMENT | REJECTION_SCREENING | REJECTION_POST_INTERVIEW | STAGE_<stage> are reactor triggers; any other key is manual-send only. Never rename a key that has EMAIL_SENT events.',

    name VARCHAR(120) NOT NULL
        COMMENT 'Display name on /recruitment/settings',

    subject VARCHAR(300) NOT NULL
        COMMENT 'Danish subject line with merge fields',

    body TEXT NOT NULL
        COMMENT 'Danish plain-text body with merge fields; HTML-escaped + newline-converted at send time',

    auto_send TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '1 = the reactor sends immediately; 0 = review-first (queued in recruitment_pending_emails)',

    active TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '0 = trigger ignored and hidden from the compose picker (soft delete)',

    -- Audit columns (house Auditable pattern)
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    modified_by VARCHAR(36) NULL,

    CONSTRAINT uk_ret_template_key UNIQUE (template_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS P15: Danish candidate-email templates with merge fields (plan §P15)';

-- -------------------------------------------------------------------
-- 2. Review-before-send queue
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_pending_emails (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY
        COMMENT 'Server-minted queue-row identity',

    candidate_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_candidates.uuid — the recipient candidate',

    application_uuid VARCHAR(36) NULL
        COMMENT 'Soft FK recruitment_applications.uuid — the application context, when the trigger had one',

    template_uuid VARCHAR(36) NULL
        COMMENT 'Soft FK recruitment_email_templates.uuid at queue time (template may be edited later; this row keeps its rendered snapshot)',

    template_key VARCHAR(60) NOT NULL
        COMMENT 'Template key at queue time — survives template edits/deactivation',

    reason VARCHAR(60) NOT NULL
        COMMENT 'Why this email required review: REVIEW_FIRST_TEMPLATE | PARTNER_REFERRAL',

    to_email VARCHAR(255) NOT NULL
        COMMENT 'Recipient snapshot at queue time (personal data — P19 scrubs PENDING rows)',

    subject VARCHAR(300) NOT NULL
        COMMENT 'Rendered subject snapshot (personal data — P19 scrubs PENDING rows)',

    body TEXT NOT NULL
        COMMENT 'Rendered plain-text body snapshot (personal data — P19 scrubs PENDING rows)',

    status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / APPROVED (sent) / DISMISSED — one-shot resolution',

    trigger_event_uuid VARCHAR(36) NULL
        COMMENT 'recruitment_events.event_id that queued this row — provenance + duplicate guard',

    resolved_at DATETIME NULL
        COMMENT 'UTC. When the row left PENDING',

    resolved_by VARCHAR(36) NULL
        COMMENT 'users.uuid of the approver/dismisser',

    -- Audit columns (house Auditable pattern)
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    modified_by VARCHAR(36) NULL,

    CONSTRAINT fk_rpe_candidate
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates (uuid)
        ON DELETE RESTRICT,
    CONSTRAINT chk_rpe_status
        CHECK (status IN ('PENDING', 'APPROVED', 'DISMISSED')),
    CONSTRAINT uk_rpe_trigger_template
        UNIQUE (trigger_event_uuid, template_key),
    INDEX idx_rpe_status (status),
    INDEX idx_rpe_candidate (candidate_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS P15: review-before-send queue for review-first candidate emails (plan §P15)';

-- -------------------------------------------------------------------
-- 3. Seed the DoD templates (Danish; TA edits them on the settings
--    page — INSERT IGNORE never clobbers those edits on re-run)
-- -------------------------------------------------------------------
INSERT IGNORE INTO recruitment_email_templates
    (uuid, template_key, name, subject, body, auto_send, active,
     created_at, updated_at, created_by)
VALUES
    (UUID(), 'ACKNOWLEDGEMENT', 'Bekræftelse på modtaget ansøgning',
     'Vi har modtaget din ansøgning – {{position_title}}',
     'Kære {{candidate_first_name}}\n\nTak for din ansøgning til stillingen som {{position_title}} hos Trustworks.\n\nVi har modtaget din ansøgning og dit CV og vender tilbage til dig, så snart vi har haft mulighed for at gennemgå materialet.\n\nHar du spørgsmål i mellemtiden, er du altid velkommen til at skrive til os på hr@trustworks.dk.\n\nMed venlig hilsen\nTrustworks',
     1, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v446'),

    (UUID(), 'REJECTION_SCREENING', 'Afslag – efter screening',
     'Vedrørende din ansøgning – {{position_title}}',
     'Kære {{candidate_first_name}}\n\nTak for din interesse for stillingen som {{position_title}} hos Trustworks og for den tid, du har brugt på din ansøgning.\n\nVi har gennemgået din ansøgning grundigt, men må desværre meddele, at vi ikke går videre med din ansøgning denne gang.\n\nVi ønsker dig alt det bedste i din videre jobsøgning.\n\nMed venlig hilsen\nTrustworks',
     1, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v446'),

    (UUID(), 'REJECTION_POST_INTERVIEW', 'Afslag – efter samtale',
     'Tak for samtalen – {{position_title}}',
     'Kære {{candidate_first_name}}\n\nMange tak, fordi du tog dig tid til at komme til samtale om stillingen som {{position_title}} hos Trustworks. Vi satte stor pris på at møde dig.\n\nEfter grundig overvejelse er vi desværre nået frem til, at vi ikke går videre med dig i processen denne gang. Det er ikke en nem beslutning, og den er ikke et udtryk for manglende kvaliteter hos dig.\n\nVi ønsker dig alt det bedste fremover, og du er altid velkommen til at søge hos os igen.\n\nMed venlig hilsen\nTrustworks',
     0, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v446');

-- -------------------------------------------------------------------
-- 4. Register /recruitment/settings (recruiter tier: template
--    management + Slack channel routing)
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-settings', 'Recruitment Settings', 1, '/recruitment/settings', 'ADMIN,HR,CXO', 874, 'ADMIN', 'SlidersHorizontal', 0, NULL)
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
