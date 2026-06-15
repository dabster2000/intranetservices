-- =============================================================================
-- Migration V375: intercompany_account_mapping
-- =============================================================================
-- Spec: docs/superpowers/specs/2026-06-15-internal-invoice-debtor-cost-account-mapping-design.md
--
-- Maps a (debtor company, issuer company) pair to the e-conomic cost account in
-- the DEBTOR's chart of accounts for an inter-company purchase. Used by the
-- debtor-side SupplierInvoice voucher so the issuer's cost lands on the correct
-- account (Technology -> 3050, Cyber -> 3055) instead of the debtor's own
-- invoice-account-number (2101).
--
-- Seeds exactly the two in-scope pairs. Unmapped pairs keep today's behaviour
-- (the application falls back to invoice-account-number and logs a warning).
-- UUID provenance: companies.uuid values verified in AgreementDefaultsRegistry
-- (A/S, Trustworks Technology ApS, Trustworks Cyber Security ApS).
-- =============================================================================

CREATE TABLE intercompany_account_mapping (
    uuid                            VARCHAR(36)  NOT NULL,
    debtor_company_uuid             VARCHAR(36)  NOT NULL,
    issuer_company_uuid             VARCHAR(36)  NOT NULL,
    economics_cost_account_number   INT          NOT NULL,
    economics_cost_account_name     VARCHAR(100) NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_intercompany_account_mapping_debtor_issuer (debtor_company_uuid, issuer_company_uuid),
    CONSTRAINT fk_intercompany_account_mapping_debtor FOREIGN KEY (debtor_company_uuid) REFERENCES companies(uuid),
    CONSTRAINT fk_intercompany_account_mapping_issuer FOREIGN KEY (issuer_company_uuid) REFERENCES companies(uuid)
);

INSERT INTO intercompany_account_mapping
    (uuid, debtor_company_uuid, issuer_company_uuid, economics_cost_account_number, economics_cost_account_name)
VALUES
    (UUID(), 'd8894494-2fb4-4f72-9e05-e6032e6dd691', '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3', 3050, 'Koeb hos Trustworks Technology'),
    (UUID(), 'd8894494-2fb4-4f72-9e05-e6032e6dd691', 'e4b0a2a4-0963-4153-b0a2-a409637153a2', 3055, 'Koeb hos Trustworks Cyber Security');
