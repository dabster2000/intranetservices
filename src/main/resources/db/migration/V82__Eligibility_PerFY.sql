-- V82: Per-Financial-Year eligibility for invoice bonuses
-- 1) Drop unique index on useruuid (so a user can have multiple eligibilities across FYs)
ALTER TABLE invoice_bonus_eligibility
    DROP INDEX useruuid;

-- 2) Add financial_year column (temporary NULL for backfill)
ALTER TABLE invoice_bonus_eligibility
    ADD COLUMN financial_year INT NULL;

-- 3) Backfill financial_year by joining to the group table
UPDATE invoice_bonus_eligibility e
SET e.financial_year = 2024;

-- 4) Delete legacy rows with NULL group reference (no way to infer FY)
DELETE FROM invoice_bonus_eligibility WHERE group_uuid IS NULL;

-- 5) Enforce NOT NULL on financial_year now that NULL rows are removed
ALTER TABLE invoice_bonus_eligibility
    MODIFY COLUMN financial_year INT NOT NULL;

-- 6) Create unique index per (useruuid, financial_year)
CREATE UNIQUE INDEX uq_eligibility_user_fy ON invoice_bonus_eligibility (useruuid, financial_year);
