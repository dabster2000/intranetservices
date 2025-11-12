-- V110: Add work period fields to invoices_v2
-- Purpose: Track which work period/batch an invoice belongs to (independent of invoice date)
-- This allows grouping invoices by work period and correlating credit notes with parent invoices

-- Add work period tracking fields (business categorization)
ALTER TABLE invoices_v2
ADD COLUMN work_year SMALLINT NOT NULL DEFAULT 2025 COMMENT 'Work period year for grouping/filtering',
ADD COLUMN work_month TINYINT NOT NULL DEFAULT 1 COMMENT 'Work period month 1-12 for grouping/filtering';

-- Backfill from old invoices table
-- IMPORTANT: Convert old month (0-11) to new format (1-12)
UPDATE invoices_v2 v2
INNER JOIN invoices old ON v2.uuid = old.uuid
SET v2.work_year = old.year,
    v2.work_month = old.month + 1;

-- Add index for filtering by work period (common query pattern)
ALTER TABLE invoices_v2
ADD INDEX idx_work_period (work_year, work_month);

-- Update comments to clarify the difference between auto-generated and manual fields
ALTER TABLE invoices_v2
MODIFY COLUMN invoice_year SMALLINT AS (YEAR(invoicedate)) PERSISTENT
    COMMENT 'Auto-generated: year extracted from invoicedate',
MODIFY COLUMN invoice_month TINYINT AS (MONTH(invoicedate)) PERSISTENT
    COMMENT 'Auto-generated: month extracted from invoicedate (1-12)';

-- Remove defaults after backfill (new invoices must explicitly set work period)
ALTER TABLE invoices_v2
ALTER COLUMN work_year DROP DEFAULT,
ALTER COLUMN work_month DROP DEFAULT;
