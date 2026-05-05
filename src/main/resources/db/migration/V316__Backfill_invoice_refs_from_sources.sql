-- =============================================================================
-- Migration V316: Backfill invoice references from contract / project sources
-- =============================================================================
-- Spec: docs/superpowers/specs/2026-05-05-invoice-refs-redesign-design.md §4.
--
-- For every existing invoice, populate `contractref` from contract.billing_ref
-- and `projectref` from project.customerreference — but ONLY when the target
-- column is currently NULL or empty string. Never overwrite an existing value
-- (an admin might have edited it manually).
--
-- Already-booked e-conomic invoices keep their customer-facing PDF unchanged;
-- this migration only cleans our internal records.
-- =============================================================================

-- Backfill Invoice.contractref from Contract.billing_ref (null-safe)
UPDATE invoices i
JOIN contracts c ON c.uuid = i.contractuuid
SET i.contractref = c.billing_ref
WHERE (i.contractref IS NULL OR i.contractref = '')
  AND c.billing_ref IS NOT NULL
  AND c.billing_ref <> '';

-- Backfill Invoice.projectref from Project.customerreference (null-safe)
UPDATE invoices i
JOIN project p ON p.uuid = i.projectuuid
SET i.projectref = p.customerreference
WHERE (i.projectref IS NULL OR i.projectref = '')
  AND p.customerreference IS NOT NULL
  AND p.customerreference <> '';
