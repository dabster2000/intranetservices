-- intranetservices/src/main/resources/db/migration/V358__Add_ai_outcome_mode_and_thresholds.sql
-- Phase 1 of the expense-flow redesign. Additive only.

-- Per-rule AI tier config: how a fired rule resolves + the confidence needed to BLOCK.
ALTER TABLE ai_rule_catalog
  ADD COLUMN outcome_mode         VARCHAR(16)  NOT NULL DEFAULT 'BLOCK'
    COMMENT 'BLOCK|SOFT_FLAG|OFF — Phase 1 AI tier for this rule when it FAILS.',
  ADD COLUMN confidence_threshold DECIMAL(4,3) NOT NULL DEFAULT 0.000
    COMMENT 'Min AI confidence (0.000-1.000) to BLOCK; below this a BLOCK rule SOFT_FLAGs.';

-- Reframe the photo-readability gate: receipt is evidence, not the data source. It no longer
-- hard-blocks; the real signal (extracted != entered amount) is handled in code as AMOUNT_MISMATCH.
UPDATE ai_rule_catalog SET outcome_mode = 'SOFT_FLAG', confidence_threshold = 0.000,
       updated_at = NOW(3), updated_by = 'V358'
 WHERE rule_id = 'R_RECEIPT_READABLE';

-- Date mismatch becomes a soft-flag unless clearly over tolerance (handled in prompt/rule).
UPDATE ai_rule_catalog SET outcome_mode = 'SOFT_FLAG', confidence_threshold = 0.000,
       updated_at = NOW(3), updated_by = 'V358'
 WHERE rule_id = 'R_DATE_MISMATCH';

-- All other REJECT/JUDGMENT rules keep blocking, but only at high confidence once the model is
-- calibrated. Until then threshold stays 0.000 (= always block when fired = today's behavior).
-- (Left at the column default 'BLOCK'/0.000 by the ADD COLUMN above; no per-rule UPDATE needed.)

-- Amount-mismatch delta thresholds (extracted vs entered amount). See Open Question 2.
INSERT INTO ai_validation_parameter (parameter_key, parameter_value, value_type, description, updated_at, updated_by) VALUES
  ('amount_mismatch_soft_pct',  '0.15', 'DECIMAL',
   'Relative |extracted-entered|/entered above which AI raises a soft AMOUNT_MISMATCH flag.', NOW(3), 'V358'),
  ('amount_mismatch_block_pct', '0.40', 'DECIMAL',
   'Relative |extracted-entered|/entered above which AI BLOCKs as AMOUNT_MISMATCH (employee fix).', NOW(3), 'V358');
