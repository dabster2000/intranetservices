-- V172: Standardize invoice month field from 0-indexed (0-11) to 1-indexed (1-12)
--
-- Background:
-- The invoices.month column historically used 0-indexed values (January=0, December=11)
-- for most invoice types. However, INTERNAL_SERVICE invoices were stored as 1-indexed
-- (January=1, December=12) due to a bug that was consistently applied to both writes and reads.
--
-- This migration standardizes ALL invoices to use 1-indexed months (1-12),
-- matching Java's LocalDate.getMonthValue() convention.
--
-- INTERNAL_SERVICE invoices are excluded because they are already 1-indexed.

UPDATE invoices
SET month = month + 1
WHERE type != 'INTERNAL_SERVICE'
  AND month BETWEEN 0 AND 11;
