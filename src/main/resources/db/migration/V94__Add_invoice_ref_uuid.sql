-- Add UUID-based reference from INTERNAL invoices to their client invoices
-- MariaDB 11 compatible migration

-- 1) Add the new column right after invoice_ref (kept NULLable for backfill)
ALTER TABLE invoices
  ADD COLUMN invoice_ref_uuid VARCHAR(36) NULL AFTER invoice_ref;

-- 2) Backfill invoice_ref_uuid using the legacy numeric invoice_ref
--    Rule:
--      - If invoice_ref = 0, set invoice_ref_uuid = NULL
--      - Otherwise, set invoice_ref_uuid to the UUID of the client invoice whose invoicenumber = invoice_ref
--    Scope: INTERNAL and INTERNAL_SERVICE invoices only
UPDATE invoices i
LEFT JOIN invoices c
  ON c.invoicenumber = i.invoice_ref
SET i.invoice_ref_uuid = CASE
  WHEN i.invoice_ref = 0 THEN NULL
  ELSE c.uuid
END
WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE');

-- 3) Create an index to speed up joins on invoice_ref_uuid
CREATE INDEX idx_invoices_invoice_ref_uuid ON invoices (invoice_ref_uuid);
