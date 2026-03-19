-- ============================================================================
-- V255: Add created_at column to itbudget table
-- ============================================================================
-- Purpose: Track when IT equipment records were created, used for the
--          48-hour edit lock feature (users can only modify/delete equipment
--          within 48 hours of creation from their profile page).
--
-- Default: CURRENT_TIMESTAMP for new rows; NULL for existing rows (treated
--          as "always editable" since we don't know the original creation time).
-- ============================================================================

ALTER TABLE itbudget
    ADD COLUMN created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP;
