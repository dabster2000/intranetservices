-- =============================================================================
-- V342: Annotate invoice_phantom_dkk with its real semantic
--
-- Why this migration:
--   The column name `invoice_phantom_dkk` predates the Phase 1 (May 2026)
--   "PHANTOM-reuse pivot" — at original creation (V201/V202) it summed only
--   INVOICE + manually-authored PHANTOM rows. Phase 1 V338-V340 added
--   auto-imported e-conomic PHANTOMs (Vattenfall, Energinet, EU, Kantine on
--   Trustworks A/S, ~170 rows). The column now sums all of those together
--   without renaming, so the literal name underreports its scope.
--
--   Renaming the physical column would touch ~45 references across V201,
--   V202, V209, V220 + every recreation of sp_refresh_fact_tables in
--   V206-V336, plus Java callers. The risk/value trade-off is poor.
--
--   Instead, attach a SQL COMMENT that defines the column's true semantic.
--   Anyone using a DB tool (DBeaver, DataGrip, mysql DESCRIBE) sees the
--   comment alongside the column name. The methodology doc at
--   docs/finalized/executive-dashboard/consolidation-methodology.md is the
--   authoritative explanation.
--
-- Affected tables:
--   - fact_company_revenue_mat (V202) — primary materialization
--   - fact_client_revenue_mat (V220)  — client-grain sibling
--
-- Idempotent: yes. ALTER TABLE MODIFY COLUMN is metadata-only when the type
-- doesn't change. Running it again is a no-op (the COMMENT just gets re-set
-- to the same string). No data is touched.
-- =============================================================================

ALTER TABLE fact_company_revenue_mat
    MODIFY COLUMN invoice_phantom_dkk DECIMAL(14,2)
    COMMENT 'Gross invoice revenue: SUM of CREATED invoices.type IN (INVOICE, PHANTOM). Includes Phase 1 (2026-05) auto-imported e-conomic PHANTOMs on Trustworks A/S (Vattenfall/Energinet/EU/Kantineordning). Excludes credit_note_dkk and internal_dkk. Authoritative semantic: docs/finalized/executive-dashboard/consolidation-methodology.md.';

ALTER TABLE fact_client_revenue_mat
    MODIFY COLUMN invoice_phantom_dkk DECIMAL(14,2)
    COMMENT 'Gross invoice revenue at client grain: SUM of CREATED invoices.type IN (INVOICE, PHANTOM). Includes Phase 1 (2026-05) auto-imported e-conomic PHANTOMs on Trustworks A/S. Excludes credit_note_dkk and internal_dkk. Authoritative semantic: docs/finalized/executive-dashboard/consolidation-methodology.md.';

-- Verification (manual, post-deploy):
--   SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT
--   FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE()
--     AND TABLE_NAME IN ('fact_company_revenue_mat', 'fact_client_revenue_mat')
--     AND COLUMN_NAME = 'invoice_phantom_dkk';
--   -- Expect: both rows show the COMMENT string above.
