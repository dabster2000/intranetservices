-- ===================================================================
-- V547: Agreement registry (Phase 3 of the template-clauses
--       & agreement-registry plan)
-- ===================================================================
-- Feature: Template Clauses & Agreement Registry, Phase 3 — every signed
--          negotiated term becomes a typed, searchable registry row with
--          expiry alerts
--          (spec docs/design/template-clauses-and-agreement-registry-spec.md §4.6–§4.7,
--           plan docs/superpowers/plans/2026-08-31-template-clauses-implementation-plan.md)
-- Domain:  agreementservice (new) — reads signing_case_clauses (V545)
--
-- Purpose:
--   1. agreement_types      — closed, HR-manageable vocabulary the
--                             registry groups by; seeded from the
--                             recurring tillæg patterns.
--   2. employee_agreements  — one row per negotiated term per person.
--                             amount/valid_from/valid_to/effective_date
--                             are first-class columns (MariaDB JSON is
--                             LONGTEXT — never query into JSON);
--                             notified_60d_at/notified_14d_at make the
--                             expiry Slack alerts idempotent.
--   3. Seed the documents.agreements.enabled flag (default OFF — the
--      /hr/agreements page, profile section and context panel ship dark;
--      the completion recorder writes rows from day one so no data is
--      lost while the UI is dark).
--   4. Seed agreements.slack.channel (empty = alerts disabled).
--
-- Subject XOR: a row belongs to a user OR a candidate (onboarding-token
--   pattern). Candidate rows re-key to the user inside the HIRED
--   conversion transaction; candidate_uuid cascades on candidate
--   hard-delete so the GDPR path cannot orphan rows.
--
-- Reserved-word check (MariaDB 10.x): type_key, name, description,
--   time_limited, display_order, active, uuid, user_uuid,
--   candidate_uuid, agreement_type, title, summary, amount, currency,
--   valid_from, valid_to, effective_date, parameters_json, clause_uuid,
--   clause_version_uuid, source, signing_case_key, document_url,
--   status, notified_60d_at, notified_14d_at, created_at, created_by,
--   confirmed_by, updated_at, modified_by — none reserved (the V534
--   LINES lesson).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   CREATE TABLE IF NOT EXISTS, INSERT IGNORE for every seed.

-- 1. Vocabulary --------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agreement_types (
    type_key      VARCHAR(50)  NOT NULL,
    name          VARCHAR(255) NOT NULL COMMENT 'Danish display name',
    description   TEXT         NULL,
    time_limited  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'valid_to expected; drives expiry alerts',
    display_order INT          NOT NULL DEFAULT 0,
    active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (type_key)
);

INSERT IGNORE INTO agreement_types (type_key, name, description, time_limited, display_order) VALUES
    ('GARANTIBONUS',       'Garantibonus',             'Garanteret bonus i en aftalt periode', 1, 10),
    ('PROEVETID_FRAVIGET', 'Fravigelse af prøvetid',   'Prøvetiden er fraveget eller forkortet', 0, 20),
    ('ANCIENNITET',        'Forhøjet anciennitet',     'Anciennitet medregnet fra tidligere ansættelse', 0, 30),
    ('OPSIGELSESVARSEL',   'Forlænget opsigelsesvarsel', 'Opsigelsesvarsel ud over funktionærlovens', 0, 40),
    ('SAERLIGE_VILKAAR',   'Særlige vilkår',           'Øvrige individuelt forhandlede vilkår', 0, 50),
    ('LOYALITETSPROGRAM',  'Loyalitetsprogram',        'Deltagelse i "Din del af Trustworks"', 0, 60),
    ('INDIVIDUEL',         'Individuel aftale',        'Fritekst-aftale uden bibliotek-klausul', 0, 70);

-- 2. The registry ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS employee_agreements (
    uuid                VARCHAR(36)   NOT NULL,
    user_uuid           VARCHAR(36)   NULL COMMENT 'XOR with candidate_uuid; candidate rows transfer here at HIRED conversion',
    candidate_uuid      VARCHAR(36)   NULL,
    agreement_type      VARCHAR(50)   NOT NULL,
    title               VARCHAR(255)  NOT NULL COMMENT 'e.g. "Garantibonus FY25/26"',
    summary             TEXT          NULL COMMENT 'One-paragraph human summary',
    amount              DECIMAL(12,2) NULL COMMENT 'First-class searchable column — never query parameters_json',
    currency            CHAR(3)       NULL,
    valid_from          DATE          NULL,
    valid_to            DATE          NULL COMMENT 'Drives the ACTIVE -> EXPIRED sweep and the 60/14-day alerts',
    effective_date      DATE          NULL,
    parameters_json     JSON          NULL COMMENT 'Everything not mapped to a first-class column',
    clause_uuid         VARCHAR(36)   NULL COMMENT 'NULL for backfill/manual/Individuel aftale',
    clause_version_uuid VARCHAR(36)   NULL COMMENT 'Exactly which wording was signed (D7)',
    source              VARCHAR(20)   NOT NULL COMMENT 'SIGNED_CASE / BACKFILL / MANUAL',
    signing_case_key    VARCHAR(255)  NULL COMMENT 'signing_cases.case_key; idempotency key half for SIGNED_CASE rows',
    document_url        VARCHAR(1000) NULL COMMENT 'Signed PDF (SharePoint/S3); set by backfill/manual entry',
    status              VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / EXPIRED / SUPERSEDED / TERMINATED',
    notified_60d_at     TIMESTAMP     NULL COMMENT 'Expiry-alert idempotency stamp (60 days)',
    notified_14d_at     TIMESTAMP     NULL COMMENT 'Expiry-alert idempotency stamp (14 days)',
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(36)   NULL,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    modified_by         VARCHAR(36)   NULL,
    confirmed_by        VARCHAR(36)   NULL COMMENT 'Set on backfill review (Phase 4)',
    PRIMARY KEY (uuid),
    KEY idx_ea_user (user_uuid),
    KEY idx_ea_candidate (candidate_uuid),
    KEY idx_ea_type_status (agreement_type, status),
    KEY idx_ea_valid_to (status, valid_to),
    KEY idx_ea_case (signing_case_key),
    CONSTRAINT fk_ea_type FOREIGN KEY (agreement_type) REFERENCES agreement_types (type_key),
    CONSTRAINT fk_ea_candidate FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates (uuid) ON DELETE CASCADE,
    CONSTRAINT chk_ea_subject CHECK ((user_uuid IS NULL) <> (candidate_uuid IS NULL))
);

-- 3. Permission catalogue rows + role grants ---------------------------------
-- V464 is regenerated from Permissions.java, but repair-at-start realigns
-- its checksum WITHOUT re-running it in deployed environments (the V514
-- lesson) — the new permission rows must be inserted here, and BEFORE the
-- grants (role_permission.permission_key FKs onto permission).
-- Metadata-only on conflict: never touch state/revoked_at/origin.

INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES
    ('agreements:read',  'Agreements — read',  NULL, 'Agreements', 'CODE', 'ACTIVE'),
    ('agreements:write', 'Agreements — write', NULL, 'Agreements', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

-- Grants: the registry is HR/ADMIN only (D9). Guarded INSERT..SELECT so a
-- UI-deleted role cannot FK-fail a repair re-run (the V465 posture), and
-- the no-op ON DUPLICATE never resurrects a later revocation.
INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
  SELECT rd.name, p.permission_key, 'ALL', NOW(), 'V547'
  FROM role_definition rd, permission p
  WHERE rd.name IN ('ADMIN', 'HR')
    AND p.permission_key IN ('agreements:read', 'agreements:write')
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;

-- 4. Feature flag (default OFF — registry UI ships dark) ---------------------

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('documents.agreements.enabled', 'false', 'documents');

-- 5. Expiry-alert Slack channel (empty = alerts disabled) --------------------

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('agreements.slack.channel', '', 'documents');
