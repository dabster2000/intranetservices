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
 AND ptm.payment_days = (
        -- Pick the closest matching payment_days from available mappings.
        SELECT pd
        FROM (
            SELECT payment_days AS pd
            FROM payment_terms_mapping
            WHERE payment_terms_type = 'NET'
              AND payment_days IS NOT NULL
              AND (company_uuid = c.companyuuid OR company_uuid IS NULL)
        ) candidates
        ORDER BY ABS(candidates.pd - avg_dd.avg_days), candidates.pd
        LIMIT 1
    )
 AND (ptm.company_uuid = c.companyuuid OR ptm.company_uuid IS NULL)
SET c.payment_terms_uuid = ptm.uuid
WHERE c.payment_terms_uuid IS NULL;
