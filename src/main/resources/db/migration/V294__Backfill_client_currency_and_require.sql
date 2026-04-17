-- V294__Backfill_client_currency_and_require.sql
--
-- Backfill NULL currencies to 'DKK' and make the column NOT NULL with a default.
-- Part of the "client currency and VAT rate" feature. Before this, InvoiceGenerator
-- hardcoded currency to "DKK" when building drafts, so all existing invoices are
-- already effectively DKK — backfilling to DKK preserves behaviour exactly.

UPDATE clients
SET currency = 'DKK'
WHERE currency IS NULL;

ALTER TABLE clients
    MODIFY COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'DKK';
