-- =============================================================================
-- Migration V298: Require company_uuid on economics mapping tables
-- =============================================================================
-- Spec: docs/superpowers/specs/2026-04-20-economics-mappings-company-scoping-design.md
--
-- Payment-term numbers and VAT-zone numbers are IDs scoped to a single
-- e-conomic agreement. The NULL-company "global default" fallback could
-- silently push a meaningless ID to the wrong agreement at finalization.
-- This migration removes the NULL escape hatch.
--
-- Preconditions (must hold on every env):
--   SELECT COUNT(*) FROM payment_terms_mapping WHERE company_uuid IS NULL; -- 0
--   SELECT COUNT(*) FROM vat_zone_mapping      WHERE company_uuid IS NULL; -- 0
-- If preconditions fail, the MODIFY NOT NULL below will fail loud.
-- Verified 2026-04-20: 0 NULL rows on staging and production.
-- =============================================================================

ALTER TABLE payment_terms_mapping
    MODIFY company_uuid VARCHAR(36) NOT NULL;

ALTER TABLE payment_terms_mapping
    DROP INDEX uq_payment_terms_mapping_type_days_company,
    ADD UNIQUE KEY uq_payment_terms_mapping_type_days_company
        (payment_terms_type, payment_days, company_uuid);

ALTER TABLE vat_zone_mapping
    MODIFY company_uuid VARCHAR(36) NOT NULL;

ALTER TABLE vat_zone_mapping
    DROP INDEX uq_vat_zone_mapping_currency_company,
    ADD UNIQUE KEY uq_vat_zone_mapping_currency_company
        (currency, company_uuid);
