-- ============================================================================
-- V169: Add missing database indexes for invoice-related tables
-- Phase 1.1 of invoicing optimization plan
--
-- Adds indexes on frequently queried columns that were missing,
-- improving performance for invoice listings, cross-company analysis,
-- fact view refresh, and finance queries.
-- ============================================================================

-- invoiceitems: FK column used in every invoice JOIN (~8,500 rows)
CREATE INDEX idx_invoiceitems_invoiceuuid ON invoiceitems(invoiceuuid);

-- invoices: common filter columns
CREATE INDEX idx_invoices_companyuuid ON invoices(companyuuid);
CREATE INDEX idx_invoices_projectuuid ON invoices(projectuuid);
CREATE INDEX idx_invoices_invoicedate ON invoices(invoicedate);
CREATE INDEX idx_invoices_type ON invoices(type);

-- finances: monthly expense aggregation queries (~555 rows)
CREATE INDEX idx_finances_period ON finances(period);
CREATE INDEX idx_finances_expensetype ON finances(expensetype);

-- finance_details: GL entry lookups (~130,000 rows)
CREATE INDEX idx_finance_details_expensedate ON finance_details(expensedate);
CREATE INDEX idx_finance_details_accountnumber ON finance_details(accountnumber);
