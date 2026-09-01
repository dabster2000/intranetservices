-- ===================================================================
-- V544: Company facts + template placeholder source_field (Phase 1 of
--       the template-clauses & agreement-registry plan)
-- ===================================================================
-- Feature: Template Clauses & Agreement Registry, Phase 1 — company
--          handling collapses to data; forms fill themselves
--          (spec docs/design/template-clauses-and-agreement-registry-spec.md §4.9/§5.1,
--           plan docs/superpowers/plans/2026-08-31-template-clauses-implementation-plan.md)
-- Domain:  documentservice (e-signature templates)
--
-- Purpose:
--   1. company_facts — per-company key-value store resolved by the
--      COMPANY placeholder DataSource. Every observed inter-company
--      difference in the signed contract corpus is a FACT, not wording
--      (legal name, genitive form, CVR, address, pension, insurance,
--      lunch price, signatory), so one shared template set + this table
--      replaces the three per-company template copies.
--   2. template_placeholders.source_field — the named field a
--      placeholder's `source` resolves (e.g. source=COMPANY,
--      source_field=NAME_GENITIVE). Additive; NULL keeps the legacy
--      keyword-matching fallback so existing templates work unchanged.
--   3. Seed company_facts from `companies` for the facts the DB already
--      knows (LEGAL_NAME, SHORT_NAME, NAME_GENITIVE, CVR, ADDRESS).
--      Pension/insurance/lunch/signatory values are entered by HR under
--      Settings → Selskaber (a missing fact fails closed at prepare
--      time and points there).
--   4. Seed the documents.prefill.enabled flag (default OFF — form
--      regrouping + prefill ship dark; fact-based COMPANY resolution is
--      un-flagged because it replaces like-for-like values).
--   5. Register the Settings → Selskaber tab in page_registry.
--
-- Reserved-word check (MariaDB 10.x): uuid, company_uuid, fact_key,
--   fact_value, source_field, created_at/updated_at/created_by/
--   modified_by — none reserved (the V534 LINES lesson).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, INSERT IGNORE
--   for fact + settings seeds (never overwrite admin edits),
--   page_registry seed is INSERT ... ON DUPLICATE KEY UPDATE.

-- 1. Per-company fact store --------------------------------------------------

CREATE TABLE IF NOT EXISTS company_facts (
    uuid         VARCHAR(36)  NOT NULL,
    company_uuid VARCHAR(36)  NOT NULL,
    fact_key     VARCHAR(50)  NOT NULL,
    fact_value   VARCHAR(500) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by   VARCHAR(36)  NULL,
    modified_by  VARCHAR(36)  NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_cf_company_fact (company_uuid, fact_key),
    KEY idx_cf_company (company_uuid),
    CONSTRAINT fk_cf_company FOREIGN KEY (company_uuid) REFERENCES companies (uuid)
);

-- 2. Explicit source field on template placeholders --------------------------

ALTER TABLE template_placeholders
    ADD COLUMN IF NOT EXISTS source_field VARCHAR(50) NULL
        COMMENT 'Named field the source resolves (e.g. NAME_GENITIVE for COMPANY, CPR for USER); NULL = legacy keyword matching';

-- 3. Seed facts the companies table already knows ----------------------------
-- INSERT IGNORE: uq_cf_company_fact keeps re-runs and admin edits intact.

INSERT IGNORE INTO company_facts (uuid, company_uuid, fact_key, fact_value, created_by)
SELECT UUID(), c.uuid, 'LEGAL_NAME', c.name, 'system'
FROM companies c
WHERE c.name IS NOT NULL AND c.name <> '';

-- SHORT_NAME seeded as the legal name minus a trailing legal suffix; HR
-- refines it. (The corpus short names are e.g. "Trustworks" /
-- "Trustworks Technology".)
INSERT IGNORE INTO company_facts (uuid, company_uuid, fact_key, fact_value, created_by)
SELECT UUID(), c.uuid, 'SHORT_NAME',
       TRIM(TRAILING ' A/S' FROM TRIM(TRAILING ' ApS' FROM c.name)),
       'system'
FROM companies c
WHERE c.name IS NOT NULL AND c.name <> '';

-- NAME_GENITIVE is STORED, never computed at render time — the Danish
-- genitive is irregular around a trailing S ("Trustworks A/S'" but
-- "Trustworks Technologys"). The seed applies the standard rule
-- (trailing s/S/x/z → apostrophe, else append s); HR verifies per company.
INSERT IGNORE INTO company_facts (uuid, company_uuid, fact_key, fact_value, created_by)
SELECT UUID(), c.uuid, 'NAME_GENITIVE',
       CASE
           WHEN RIGHT(c.name, 1) IN ('s', 'S', 'x', 'z') THEN CONCAT(c.name, '''')
           ELSE CONCAT(c.name, 's')
       END,
       'system'
FROM companies c
WHERE c.name IS NOT NULL AND c.name <> '';

INSERT IGNORE INTO company_facts (uuid, company_uuid, fact_key, fact_value, created_by)
SELECT UUID(), c.uuid, 'CVR', c.cvr, 'system'
FROM companies c
WHERE c.cvr IS NOT NULL AND c.cvr <> '';

INSERT IGNORE INTO company_facts (uuid, company_uuid, fact_key, fact_value, created_by)
SELECT UUID(), c.uuid, 'ADDRESS',
       CONCAT_WS(', ',
                 NULLIF(TRIM(c.address), ''),
                 NULLIF(TRIM(CONCAT_WS(' ', NULLIF(TRIM(c.zipcode), ''), NULLIF(TRIM(c.city), ''))), '')),
       'system'
FROM companies c
WHERE (c.address IS NOT NULL AND TRIM(c.address) <> '')
   OR (c.city IS NOT NULL AND TRIM(c.city) <> '');

-- 4. Feature flag (default OFF — prefill/regrouping ship dark) ---------------

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('documents.prefill.enabled', 'false', 'documents');

-- 5. Settings → Selskaber tab ------------------------------------------------
-- HR maintains the facts; ADMIN keeps access via role. display_order 170
-- follows settings-employee-documents (160).

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-companies', 'Selskaber', 1, '/settings?tab=companies', 'ADMIN,HR', 170, 'SETTINGS', 'Building2', 0, NULL)
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
