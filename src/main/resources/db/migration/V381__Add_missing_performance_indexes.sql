-- V381__Add_missing_performance_indexes.sql
--
-- Performance audit (2026-06-25): add indexes on hot, frequently-filtered columns
-- that currently have no covering index (verified against V1..V380 and the entity classes).
-- All statements are additive secondary indexes built with ALGORITHM=INPLACE, LOCK=NONE
-- so they apply online (concurrent reads + writes continue) and are safe during a
-- rolling/canary deploy — no table rewrite, no exclusive lock.
--
-- Verify on staging with EXPLAIN before promoting to production.

-- expenses: every "my expenses" load filters on useruuid (+ expensedate range);
-- the fact_project_financials refresh groups by projectuuid. No existing index covers these.
ALTER TABLE expenses
    ADD INDEX idx_expenses_useruuid     (useruuid),
    ADD INDEX idx_expenses_user_date    (useruuid, expensedate),
    ADD INDEX idx_expenses_projectuuid  (projectuuid),
    ALGORITHM=INPLACE, LOCK=NONE;

-- invoices: monthly-close queries filter (year, month); invoice generation and contract
-- views filter contractuuid; the self-billed settlement duplicate-guard filters all four
-- settlement_* columns (the existing idx_invoices_settlement_group covers only the first two).
ALTER TABLE invoices
    ADD INDEX idx_invoices_year_month   (year, month),
    ADD INDEX idx_invoices_contractuuid (contractuuid),
    ADD INDEX idx_invoices_settlement_period
        (settlement_billing_client_uuid, settlement_debtor_companyuuid, settlement_year, settlement_month),
    ALGORITHM=INPLACE, LOCK=NONE;

-- invoiceitems: the fact_company_revenue / fact_project_financials views JOIN on
-- consultantuuid; this runs on every 5-minute BI refresh. No existing index on the column.
ALTER TABLE invoiceitems
    ADD INDEX idx_invoiceitems_consultantuuid (consultantuuid),
    ALGORITHM=INPLACE, LOCK=NONE;

-- work: sp_recalculate_availability runs three constant-taskuuid + registered-range
-- sub-selects per dirty user-month (every 5 minutes). Existing work indexes lead with
-- `registered` or `useruuid`; none leads with taskuuid for these selective task filters.
ALTER TABLE work
    ADD INDEX idx_work_taskuuid_registered (taskuuid, registered),
    ALGORITHM=INPLACE, LOCK=NONE;
