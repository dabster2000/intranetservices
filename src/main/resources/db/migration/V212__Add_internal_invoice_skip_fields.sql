-- =============================================================================
-- Migration V212: Add internal_invoice_skip fields to invoices table
--
-- Purpose:
--   Adds four columns to the `invoices` table to support the ability to mark
--   an internal invoice as deliberately skipped (i.e., not to be booked or
--   processed), together with an audit trail of who skipped it and when, and
--   an optional free-text reason.
--
-- New columns:
--   internal_invoice_skip      TINYINT(1)  NOT NULL DEFAULT 0
--     Boolean flag. 0 = not skipped, 1 = skipped.
--
--   internal_invoice_skip_note TEXT        DEFAULT NULL
--     Free-text explanation of why the invoice was skipped.
--     Optional; NULL when the invoice has not been skipped.
--
--   internal_invoice_skip_at   DATETIME    DEFAULT NULL
--     UTC timestamp of when the skip flag was set.
--     NULL until the invoice is skipped.
--
--   internal_invoice_skip_by   VARCHAR(36) DEFAULT NULL
--     UUID of the user who set the skip flag (foreign-key style reference to
--     the users table, stored as a plain VARCHAR to avoid cross-service FK
--     constraints). NULL until the invoice is skipped.
--
-- Backwards compatibility:
--   All columns are additive (no existing column is altered or dropped).
--   Existing rows will have internal_invoice_skip = 0 (not skipped) and
--   NULL for the remaining three audit columns. No data is migrated.
--
-- Affected entity:
--   dk.trustworks.intranet.aggregates.invoice.model.Invoice
--   The four corresponding Java fields must be added to that class to expose
--   the columns via Hibernate/Panache.
--
-- Idempotency:
--   ALTER TABLE ADD COLUMN is NOT idempotent in MariaDB 10 by default.
--   This migration must only run once via Flyway's version control.
--
-- Rollback strategy:
--   ALTER TABLE invoices
--     DROP COLUMN internal_invoice_skip,
--     DROP COLUMN internal_invoice_skip_note,
--     DROP COLUMN internal_invoice_skip_at,
--     DROP COLUMN internal_invoice_skip_by;
--   (Run manually if a rollback is needed; Flyway does not auto-rollback DDL.)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Add internal invoice skip columns to invoices
-- ---------------------------------------------------------------------------

ALTER TABLE invoices
    ADD COLUMN internal_invoice_skip      TINYINT(1)  NOT NULL DEFAULT 0,
    ADD COLUMN internal_invoice_skip_note TEXT        DEFAULT NULL,
    ADD COLUMN internal_invoice_skip_at   DATETIME    DEFAULT NULL,
    ADD COLUMN internal_invoice_skip_by   VARCHAR(36) DEFAULT NULL;


-- ---------------------------------------------------------------------------
-- 2. Index: fast lookup of all skipped invoices
--    Supports queries such as:
--      SELECT * FROM invoices WHERE internal_invoice_skip = 1
--    The column has very low cardinality (0/1) but the index is still useful
--    when filtering the small minority of skipped invoices from a large table.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_invoices_internal_skip
    ON invoices (internal_invoice_skip);


-- ---------------------------------------------------------------------------
-- 3. Validation queries
--    Run these after applying the migration to confirm correctness:
--
--    -- All four columns exist and have the expected defaults:
--    SELECT column_name, column_type, is_nullable, column_default
--    FROM information_schema.columns
--    WHERE table_schema = DATABASE()
--      AND table_name   = 'invoices'
--      AND column_name IN (
--            'internal_invoice_skip',
--            'internal_invoice_skip_note',
--            'internal_invoice_skip_at',
--            'internal_invoice_skip_by'
--          )
--    ORDER BY column_name;
--
--    -- No existing row has been accidentally flipped to skipped:
--    SELECT COUNT(*) AS should_be_zero
--    FROM invoices
--    WHERE internal_invoice_skip = 1;
--
--    -- Index is present:
--    SHOW INDEX FROM invoices WHERE Key_name = 'idx_invoices_internal_skip';
-- ---------------------------------------------------------------------------
