-- ====================================================================
-- V367: Backfill NULL salary.type to NORMAL.
--
-- A small number of legacy `salary` rows have a NULL `type`. They were
-- created before the column was consistently populated: the Salary(...)
-- entity constructors never set `type`, and the DB column historically
-- allowed NULL even though the entity declares it non-null.
--
-- A NULL type makes Salary.getType() return null, which produced a
-- NullPointerException in SalaryResource.listPayments (now additionally
-- null-guarded in code). NORMAL is the standard salaried type; HOURLY is
-- the rare exception, so NORMAL is the correct default for these legacy
-- rows.
--
-- Idempotent: affects no rows once none remain NULL.
-- Rollback: not reversible — the original NULLs are not recorded.
-- ====================================================================

UPDATE salary
SET type = 'NORMAL'
WHERE type IS NULL;
