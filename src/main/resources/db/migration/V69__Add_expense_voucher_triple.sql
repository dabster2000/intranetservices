-- Add journalnumber and accountingyear to expenses for e-conomics voucher addressing
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS journalnumber INT;
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS accountingyear VARCHAR(32);

-- Optional index to speed up lookups by the voucher triple
CREATE INDEX IF NOT EXISTS idx_expenses_voucher_triple ON expenses (journalnumber, accountingyear, vouchernumber);