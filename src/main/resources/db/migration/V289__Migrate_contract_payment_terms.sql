-- =============================================================================
-- Migration V289: Backfill contracts.payment_terms_uuid from invoice patterns
-- =============================================================================
-- Spec: docs/specs/economics-invoice-api-migration-spec.md §3.5 step 3,
--       §5.5 payment terms migration.
--
-- Strategy:
--   For each contract, compute the median (duedate - invoicedate) days across
--   its historical invoices. Map to the closest mapping in
--   payment_terms_mapping where payment_terms_type = 'NET'. Contracts with
--   no invoices or with inconsistent patterns are left NULL (admin resolves).
--
-- Approach:
--   Use AVG (not median; MariaDB has no built-in MEDIAN) — close enough for
--   this purpose. Round to nearest exact mapping that exists for the contract's
--   company (with global fallback when no per-company mapping exists).
--
-- Idempotency: WHERE payment_terms_uuid IS NULL.
--
-- MariaDB does NOT support referencing outer-query aliases through a derived
-- table (the inner `SELECT pd FROM (SELECT ... FROM payment_terms_mapping ...)
-- candidates` pattern). The prior version used that shape and failed fast with
-- `ERROR 1054 (42S22): Unknown column 'c.companyuuid' in 'WHERE'`, leaving a
-- success=0 row in flyway_schema_history. Rewritten as a plain correlated
-- subquery (no derived-table wrapper) so `c` and `avg_dd` stay visible.
-- =============================================================================

UPDATE contracts c
JOIN (
    SELECT i.contractuuid,
           ROUND(AVG(DATEDIFF(i.duedate, i.invoicedate))) AS avg_days
    FROM invoices i
    WHERE i.duedate IS NOT NULL
      AND i.invoicedate IS NOT NULL
      AND DATEDIFF(i.duedate, i.invoicedate) BETWEEN 1 AND 120
    GROUP BY i.contractuuid
    HAVING COUNT(*) >= 2
) avg_dd ON avg_dd.contractuuid = c.uuid
JOIN payment_terms_mapping ptm
  ON ptm.payment_terms_type = 'NET'
 AND (ptm.company_uuid = c.companyuuid OR ptm.company_uuid IS NULL)
 AND ptm.payment_days = (
        -- Closest payment_days available for this contract's company
        -- (company-scoped mappings preferred; global mappings accepted as
        -- fallback). Distance tie-broken by smaller payment_days.
        SELECT inner_ptm.payment_days
        FROM payment_terms_mapping inner_ptm
        WHERE inner_ptm.payment_terms_type = 'NET'
          AND inner_ptm.payment_days IS NOT NULL
          AND (inner_ptm.company_uuid = c.companyuuid OR inner_ptm.company_uuid IS NULL)
        ORDER BY ABS(inner_ptm.payment_days - avg_dd.avg_days), inner_ptm.payment_days
        LIMIT 1
    )
SET c.payment_terms_uuid = ptm.uuid
WHERE c.payment_terms_uuid IS NULL;
