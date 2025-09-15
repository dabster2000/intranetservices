-- Migrate BonusEligibilityGroup from period-based to financial year based
-- 1) Add financial_year column
ALTER TABLE invoice_bonus_eligibility_group
    ADD COLUMN financial_year INT NULL;

-- 2) Backfill financial_year from existing period_start
--    Financial year runs July 1 to June 30. FY = year of July 1 start.
UPDATE invoice_bonus_eligibility_group
SET financial_year = CASE
    WHEN MONTH(period_start) >= 7 THEN YEAR(period_start)
    ELSE YEAR(period_start) - 1
END;

-- 3) Enforce NOT NULL after backfill
ALTER TABLE invoice_bonus_eligibility_group
    MODIFY COLUMN financial_year INT NOT NULL;

-- 4) Drop old period columns
ALTER TABLE invoice_bonus_eligibility_group
    DROP COLUMN period_start,
    DROP COLUMN period_end;
