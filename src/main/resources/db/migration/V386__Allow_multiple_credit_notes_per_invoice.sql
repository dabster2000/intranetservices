-- Multiple credit notes per client invoice (spec 2026-07-02).
-- The V72 UNIQUE index enforced at most one credit note per source invoice.
-- Client INVOICE sources may now carry any number of partial credit notes;
-- the 1:1 rule for INTERNAL sources moves to the application layer
-- (InvoiceService.createCreditNote), and the over-credit race protection the
-- unique index provided moves to the finalization guard
-- (CreditNoteCoverageService, pessimistic lock on the source row).
-- The non-unique replacement keeps the reverse-lookup performance: every
-- "credit notes for invoice X" subquery hits this index.
ALTER TABLE invoices DROP INDEX ux_invoices_creditnote_for_uuid;
CREATE INDEX ix_invoices_creditnote_for_uuid ON invoices (creditnote_for_uuid);
