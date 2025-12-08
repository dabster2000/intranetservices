-- ===================================================================
-- Migration: V131__Add_async_processing_fields_to_signing_cases.sql
-- Description: Add fields to track async status fetching from NextSign
-- Author: Claude Code
-- Date: 2025-12-08
-- ===================================================================
--
-- Purpose:
-- NextSign API has a race condition where newly created cases return 404
-- when fetched immediately. This migration adds fields to support async
-- background processing pattern where status is fetched by batch job.
--
-- Processing Status Values:
-- - PENDING_FETCH: Case created, awaiting first status fetch
-- - FETCHING: Status fetch in progress (prevents duplicate processing)
-- - COMPLETED: Status successfully fetched and stored
-- - FAILED: Status fetch failed after max retries
--
-- ===================================================================

-- Add processing state tracking columns
ALTER TABLE signing_cases
  ADD COLUMN processing_status VARCHAR(50) DEFAULT 'PENDING_FETCH'
    COMMENT 'Async processing state: PENDING_FETCH, FETCHING, COMPLETED, FAILED'
    AFTER status,
  ADD COLUMN last_status_fetch TIMESTAMP NULL
    COMMENT 'When status was last fetched from NextSign',
  ADD COLUMN status_fetch_error TEXT NULL
    COMMENT 'Last error message if status fetch failed',
  ADD COLUMN retry_count INT DEFAULT 0
    COMMENT 'Number of failed fetch attempts';

-- Add index for efficient batch job queries
-- Batch job needs to find PENDING_FETCH and FAILED cases quickly
CREATE INDEX idx_processing_status ON signing_cases(processing_status, created_at);

-- Update existing records to COMPLETED (they already have status fetched)
UPDATE signing_cases
SET processing_status = 'COMPLETED'
WHERE status IS NOT NULL AND status != '';

-- Verify migration
SELECT
  'signing_cases schema updated' AS message,
  COUNT(*) AS total_cases,
  SUM(CASE WHEN processing_status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_cases,
  SUM(CASE WHEN processing_status = 'PENDING_FETCH' THEN 1 ELSE 0 END) AS pending_cases
FROM signing_cases;
