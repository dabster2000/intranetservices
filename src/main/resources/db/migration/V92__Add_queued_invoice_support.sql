-- Add support for queued internal invoices
-- This feature allows INTERNAL invoices to be queued until the referenced external invoice is PAID

-- Add debtor company field to track the company receiving/paying the internal invoice
ALTER TABLE invoices
ADD COLUMN debtor_companyuuid VARCHAR(36) NULL
COMMENT 'Company receiving/paying invoice (for INTERNAL invoices awaiting payment)';

-- Add indexes to optimize batch job queries
CREATE INDEX idx_invoices_status_type ON invoices(status, type);
CREATE INDEX idx_invoices_invoice_ref ON invoices(invoice_ref);

-- No data migration needed - this is for new functionality only
