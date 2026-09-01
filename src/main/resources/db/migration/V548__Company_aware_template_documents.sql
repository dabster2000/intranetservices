-- ===================================================================
-- V548: Company-aware template documents
-- ===================================================================
-- Feature: Template Clauses & Agreement Registry — lets ONE template carry
--          every company's documents, the last thing blocking the
--          TW/TWC/TWT triplet merge
--          (design: docs/design/company-aware-template-signers-design.md)
-- Domain:  documentservice (e-signature templates)
--
-- Purpose:
--   V546 gave template_default_signers a company dimension and the
--   Ansættelseskontrakter signer lists merged cleanly. The documents did
--   not: a template carries one fixed document set, but each company has
--   its own files —
--
--     TW   Ansættelseskontrakt   + Din del af Trustworks
--     TWC  Ansættelsesaftale     + Din del af Tw Cyber Security
--     TWT  Ansættelseskontrakt   + Din del af Trustworks Technology
--
--   The appendices are three genuinely different loyalty programmes, not
--   one text with the name swapped, so they cannot collapse into a single
--   {{COMPANY_*}}-tagged file. This column lets them live side by side on
--   one template instead.
--
--   company_uuid semantics — identical to V546's signers:
--     NULL  -> the document goes to every company
--     set   -> only when the derived company matches
--   Effective set for company C: `company_uuid IS NULL OR company_uuid = C`.
--
--   The two can be mixed on one template, which is the intended end state:
--   a single shared Ansættelseskontrakt (NULL, using {{COMPANY_LEGAL_NAME}}
--   and {{COMPANY_CVR}}) alongside three company-scoped appendices.
--
-- Reserved-word check (MariaDB 10.x): company_uuid, template_uuid,
--   document_name, display_order, file_uuid — none reserved (V534 LINES).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts, so the
--   column and index use IF NOT EXISTS and the foreign key is added only
--   when absent (information_schema guard + PREPARE, the V429/V546
--   pattern). Purely additive: every existing row keeps company_uuid NULL
--   and therefore its current "goes to every company" behaviour.

-- 1. The company dimension ---------------------------------------------------

ALTER TABLE template_documents
    ADD COLUMN IF NOT EXISTS company_uuid VARCHAR(36) NULL
        COMMENT 'NULL = document goes to every company; set = only when the derived company matches';

-- 2. Lookup index for the per-company filter ---------------------------------

ALTER TABLE template_documents
    ADD KEY IF NOT EXISTS idx_td_template_company (template_uuid, company_uuid);

-- 3. Referential integrity, added only when absent ---------------------------

SET @td_fk_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'template_documents'
      AND CONSTRAINT_NAME = 'fk_td_company'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);

SET @td_fk_sql := IF(@td_fk_missing,
    'ALTER TABLE template_documents
        ADD CONSTRAINT fk_td_company FOREIGN KEY (company_uuid) REFERENCES companies (uuid)',
    'DO 0');

PREPARE td_fk_stmt FROM @td_fk_sql;
EXECUTE td_fk_stmt;
DEALLOCATE PREPARE td_fk_stmt;
