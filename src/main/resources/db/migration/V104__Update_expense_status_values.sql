-- Update expense status values to distinguish upload success from ledger verification
-- This migration updates existing PROCESSED expenses to VERIFIED_BOOKED (the safest assumption)
-- Going forward:
--   UPLOADED = Successfully uploaded to e-conomics
--   VERIFIED_UNBOOKED = Verified in journal (not yet booked to ledger)
--   VERIFIED_BOOKED = Verified in e-conomics and booked to general ledger

-- Update existing PROCESSED expenses to VERIFIED_BOOKED
-- Reasoning: If an expense is marked PROCESSED, it has been successfully uploaded
-- and likely has been in the system long enough to be booked to the ledger
UPDATE expenses
SET status = 'VERIFIED_BOOKED'
WHERE status = 'PROCESSED';

-- Add index on status column for faster filtering
CREATE INDEX IF NOT EXISTS idx_expenses_status ON expenses (status);
