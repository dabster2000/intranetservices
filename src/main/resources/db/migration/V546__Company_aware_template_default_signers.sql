-- ===================================================================
-- V546: Company-aware template default signers
-- ===================================================================
-- Feature: Template Clauses & Agreement Registry — makes the counter-signer
--          a property of (template, company) instead of a company fact
--          (design: docs/design/company-aware-template-signers-design.md)
-- Domain:  documentservice (e-signature templates)
--
-- Purpose:
--   Phase 1 consolidates the three per-company template copies (TW / TWC /
--   TWT) into one shared template. Everything that differs between the
--   companies became a company_fact in V544 — except the counter-signer,
--   which cannot be one: the signer set varies by *template within a
--   company* (TW's standard contract is countersigned by the CEO + COO,
--   its junior-consultant contract by the COO + CCuO), runs 1–3 people,
--   carries a per-company role label (CEO & Managing Partner vs Managing
--   Director) and may include a non-signing recipient (TWT copies HR).
--   V544's SIGNATORY_NAME/SIGNATORY_EMAIL facts were removed for exactly
--   that reason; this column replaces them.
--
--   company_uuid semantics:
--     NULL  -> the row applies to every company (today's behaviour)
--     set   -> the row applies only when the derived company matches
--   A template's effective signer list for company C is therefore
--   `company_uuid IS NULL OR company_uuid = C`.
--
-- Reserved-word check (MariaDB 10.x): company_uuid, template_uuid,
--   signer_group, display_order — none reserved (the V534 LINES lesson).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts, so the
--   column and index use IF NOT EXISTS and the foreign key is added only
--   when absent (information_schema guard + PREPARE, the pattern used by
--   V429). Purely additive: every existing row keeps company_uuid NULL and
--   therefore its current "applies to all companies" behaviour.

-- 1. The company dimension ---------------------------------------------------

ALTER TABLE template_default_signers
    ADD COLUMN IF NOT EXISTS company_uuid VARCHAR(36) NULL
        COMMENT 'NULL = applies to every company; set = only when the derived company matches';

-- 2. Lookup index for the per-company filter ---------------------------------

ALTER TABLE template_default_signers
    ADD KEY IF NOT EXISTS idx_tds_template_company (template_uuid, company_uuid);

-- 3. Referential integrity, added only when absent ---------------------------

SET @tds_fk_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'template_default_signers'
      AND CONSTRAINT_NAME = 'fk_tds_company'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);

SET @tds_fk_sql := IF(@tds_fk_missing,
    'ALTER TABLE template_default_signers
        ADD CONSTRAINT fk_tds_company FOREIGN KEY (company_uuid) REFERENCES companies (uuid)',
    'DO 0');

PREPARE tds_fk_stmt FROM @tds_fk_sql;
EXECUTE tds_fk_stmt;
DEALLOCATE PREPARE tds_fk_stmt;
