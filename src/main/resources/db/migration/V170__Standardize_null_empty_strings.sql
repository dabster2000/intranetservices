-- ============================================================================
-- V170: Standardize NULL vs empty string in invoices table
-- Phase 1.2 of invoicing optimization plan
--
-- Some UUID columns use empty string '' instead of NULL when no value
-- is present. This causes inconsistent query behavior (NULL checks fail
-- on empty strings). Normalizes to NULL for consistent handling.
-- ============================================================================

UPDATE invoices SET contractuuid = NULL WHERE contractuuid = '';
UPDATE invoices SET projectuuid = NULL WHERE projectuuid = '';
UPDATE invoices SET debtor_companyuuid = NULL WHERE debtor_companyuuid = '';
