-- ============================================================================
-- V200: Add FK constraint on invoice_bonuses.invoiceuuid → invoices.uuid
-- ============================================================================
-- Purpose: Enforce referential integrity for invoice bonuses.
--          Orphaned rows (referencing deleted invoices) were cleaned up
--          before applying this constraint.
-- ============================================================================

-- Remove any orphaned rows that reference non-existent invoices
DELETE FROM invoice_bonuses
WHERE invoiceuuid NOT IN (SELECT uuid FROM invoices);

-- Add FK with CASCADE delete so bonus rows are removed when invoice is deleted
ALTER TABLE invoice_bonuses
ADD CONSTRAINT fk_invbonus_invoice
FOREIGN KEY (invoiceuuid) REFERENCES invoices(uuid)
ON DELETE CASCADE;
