-- V269: Add NextSign detail fields to signing_cases for enhanced admin view
-- These fields are populated at case creation and updated during batch sync.
-- They allow the admin list to show title, folder, and expiry without per-row API calls.

ALTER TABLE signing_cases ADD COLUMN title VARCHAR(500) NULL;
ALTER TABLE signing_cases ADD COLUMN availability_days INT NULL;
ALTER TABLE signing_cases ADD COLUMN availability_unlimited TINYINT(1) NOT NULL DEFAULT 0;
