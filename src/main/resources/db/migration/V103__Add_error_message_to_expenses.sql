-- Add error_message column to expenses table for detailed error tracking
-- This allows storing detailed error information when expense upload to e-conomics fails
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS error_message TEXT;

-- Add index for quickly finding expenses with errors
CREATE INDEX IF NOT EXISTS idx_expenses_error_message ON expenses (status, error_message(255));
