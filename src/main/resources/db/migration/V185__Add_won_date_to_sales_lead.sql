-- ============================================================================
-- V185: Add won_date column to sales_lead
--
-- The "Won Leads (7 days)" KPI currently queries last_updated to determine
-- recently-won leads. This is incorrect because last_updated is refreshed
-- by ANY row modification (ON UPDATE current_timestamp()). This migration
-- adds a dedicated won_date column that tracks when a lead status changed
-- to WON.
--
-- Backfill: Sets won_date = last_updated for existing WON leads. This is
-- a best-effort approximation; known mass-update on 2026-02-15 means ~121
-- WON leads will have inaccurate won_date until they age out of view.
-- ============================================================================

ALTER TABLE sales_lead ADD COLUMN IF NOT EXISTS won_date DATETIME DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_sales_lead_won_date ON sales_lead(won_date);

-- Backfill existing WON leads with last_updated as best approximation
UPDATE sales_lead SET won_date = last_updated WHERE status = 'WON';
