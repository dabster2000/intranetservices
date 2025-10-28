-- Add retry tracking columns to expenses table
-- These columns help manage retry attempts and detect orphaned vouchers
START TRANSACTION;
ALTER TABLE expenses
ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0 COMMENT 'Number of upload retry attempts',
ADD COLUMN IF NOT EXISTS last_retry_at DATETIME NULL COMMENT 'Timestamp of last retry attempt',
ADD COLUMN IF NOT EXISTS is_orphaned BOOLEAN DEFAULT FALSE COMMENT 'Whether voucher reference is orphaned';

-- Index for finding orphaned vouchers efficiently
-- This helps the scheduled job find expenses with voucher numbers that need verification
CREATE INDEX IF NOT EXISTS idx_expenses_orphaned
ON expenses(is_orphaned, vouchernumber);

-- Index for finding expenses that need retry
-- Helps identify VOUCHER_CREATED and UP_FAILED expenses for retry processing
CREATE INDEX IF NOT EXISTS idx_expenses_retry_status
ON expenses(status, retry_count);

-- Add comment to document the new VOUCHER_CREATED status
ALTER TABLE expenses MODIFY COLUMN status VARCHAR(50)
COMMENT 'Status values: CREATED, VALIDATED, PROCESSING, VOUCHER_CREATED, UPLOADED, VERIFIED_UNBOOKED, VERIFIED_BOOKED, UP_FAILED, NO_FILE, NO_USER, DELETED';
COMMIT ;