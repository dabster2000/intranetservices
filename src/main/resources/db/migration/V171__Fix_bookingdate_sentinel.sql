-- ============================================================================
-- V171: Replace bookingdate sentinel value with NULL
-- Phase 1.3 of invoicing optimization plan
--
-- Unbooked invoices used '1900-01-01' as a sentinel value instead of NULL.
-- This caused misleading date range queries and required special-case logic
-- in application code. NULL correctly represents "not yet booked".
-- ============================================================================

UPDATE invoices SET bookingdate = NULL WHERE bookingdate = '1900-01-01';
