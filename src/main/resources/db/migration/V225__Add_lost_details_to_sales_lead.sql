-- V225: Add lost_notes and lost_at_stage to sales_lead for lost lead tracking
-- Also fixes the NO_RESSOURCES -> NO_RESOURCES enum typo

ALTER TABLE sales_lead
  ADD COLUMN IF NOT EXISTS lost_notes TEXT NULL
    COMMENT 'Optional freetext note captured when lead is marked lost',
  ADD COLUMN IF NOT EXISTS lost_at_stage VARCHAR(12) NULL
    COMMENT 'Pipeline stage the lead was in when marked lost';

-- Fix enum typo (future-proofing; all current rows are NULL)
UPDATE sales_lead SET lost_reason = 'NO_RESOURCES' WHERE lost_reason = 'NO_RESSOURCES';

CREATE INDEX IF NOT EXISTS idx_sales_lead_lost_reason ON sales_lead(lost_reason);
CREATE INDEX IF NOT EXISTS idx_sales_lead_status_modified ON sales_lead(status, last_updated);
