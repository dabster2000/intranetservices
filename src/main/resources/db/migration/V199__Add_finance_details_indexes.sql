-- =============================================================================
-- Migration V199: Add composite indexes on finance_details
--
-- Purpose:
--   Add 3 composite indexes on finance_details to support the distribution
--   calculation queries introduced by fact_operating_cost_distribution (V197)
--   and to improve performance for fact_opex (V125) aggregation queries.
--
-- Background:
--   finance_details holds ~130,000+ GL transaction rows from e-conomic ERP.
--   The distribution view (V197) runs GROUP BY on companyuuid + accountnumber
--   + expensedate.  Without composite indexes, every distribution query
--   performs a full table scan.
--
-- Existing indexes (from V169 — do NOT recreate):
--   idx_finance_details_expensedate   ON finance_details(expensedate)
--   idx_finance_details_accountnumber ON finance_details(accountnumber)
--   finance_details_companies_uuid_fk ON finance_details(companyuuid)
--
-- New indexes (this migration):
--   idx_fd_company_date         — supports: WHERE companyuuid = ? ORDER BY expensedate
--                                 and monthly aggregation filtered by company
--   idx_fd_account_date         — supports: WHERE accountnumber = ? AND expensedate BETWEEN ?
--                                 and fact_opex account-range scans
--   idx_fd_company_account_date — covering index for the distribution view CTE
--                                 (gl_shared): filters on companyuuid AND accountnumber
--                                 with GROUP BY including expensedate functions
--
-- Index naming:
--   Following project convention: idx_<table_abbrev>_<columns>
--   fd = finance_details
--
-- Locking note:
--   CREATE INDEX in MariaDB 10 uses online DDL (InnoDB) for non-unique indexes.
--   Table remains readable during index build; write operations may experience
--   brief latency spikes.  Schedule during off-peak hours if possible.
--
-- Idempotent: IF NOT EXISTS — safe to re-run if partially applied.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Composite index: company + date
--    Optimises queries that filter by company and scan or sort by month.
--    Used by: fact_opex (V125), fact_operating_cost_distribution (V197).
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_fd_company_date
    ON finance_details (companyuuid, expensedate);

-- ---------------------------------------------------------------------------
-- 2. Composite index: account + date
--    Optimises queries that filter by account number within a date range.
--    Used by: fact_opex account-range filter, fact_internal_invoice_cost
--    (V195) specific account lookups (3050/3055/3070/3075/1350).
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_fd_account_date
    ON finance_details (accountnumber, expensedate);

-- ---------------------------------------------------------------------------
-- 3. Covering composite index: company + account + date
--    Highest-value index for the distribution view CTE (gl_shared).
--    The optimizer can satisfy the entire GROUP BY from this index without
--    touching the clustered row data.
--    Also covers fact_opex (V125) which filters on both companyuuid and
--    account_code with YEAR/MONTH aggregation on expensedate.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_fd_company_account_date
    ON finance_details (companyuuid, accountnumber, expensedate);
