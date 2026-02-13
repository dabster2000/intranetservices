-- V173: Add foreign key constraints on invoiceitems and invoice_economics_uploads
--
-- Background:
-- Most relationships in the invoice system are "soft" foreign keys (no DB constraint).
-- This migration adds proper FK constraints to ensure referential integrity.
--
-- Pre-requisite cleanup: remove any orphaned records that would block FK creation.

-- Step 1: Clean up orphaned invoice items (items whose invoiceuuid has no matching invoice)
DELETE ii FROM invoiceitems ii
LEFT JOIN invoices i ON ii.invoiceuuid = i.uuid
WHERE i.uuid IS NULL;

-- Step 2: Clean up orphaned economics uploads
DELETE ieu FROM invoice_economics_uploads ieu
LEFT JOIN invoices i ON ieu.invoiceuuid = i.uuid
WHERE i.uuid IS NULL;

-- Step 3: Add FK constraint on invoiceitems → invoices
-- ON DELETE CASCADE: when an invoice is deleted, its line items are also deleted
ALTER TABLE invoiceitems
  ADD CONSTRAINT fk_invoiceitems_invoice
  FOREIGN KEY (invoiceuuid) REFERENCES invoices(uuid)
  ON DELETE CASCADE;

-- Step 4: Add FK constraint on invoice_economics_uploads → invoices
-- ON DELETE CASCADE: when an invoice is deleted, its upload records are also deleted
ALTER TABLE invoice_economics_uploads
  ADD CONSTRAINT fk_uploads_invoice
  FOREIGN KEY (invoiceuuid) REFERENCES invoices(uuid)
  ON DELETE CASCADE;
