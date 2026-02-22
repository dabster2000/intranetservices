-- =============================================================================
-- Add source_reference column to salary_lump_sum for idempotent payouts
-- Pattern: tw_bonus_{fiscalYear}_{useruuid}
-- Unique index allows ON DUPLICATE KEY UPDATE for idempotency
-- =============================================================================
ALTER TABLE salary_lump_sum ADD COLUMN source_reference VARCHAR(100) DEFAULT NULL;

CREATE UNIQUE INDEX uk_salary_lump_sum_source_ref ON salary_lump_sum (source_reference);
