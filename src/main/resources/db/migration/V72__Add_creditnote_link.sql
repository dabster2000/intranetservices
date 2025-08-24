ALTER TABLE invoices
  ADD COLUMN creditnote_for_uuid VARCHAR(36) NULL AFTER invoice_ref;

CREATE UNIQUE INDEX ux_invoices_creditnote_for_uuid ON invoices (creditnote_for_uuid);
