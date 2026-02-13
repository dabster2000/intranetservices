-- =============================================================================
-- Migration V166: Remove unused columns from bi_data_per_day
--
-- Purpose:
--   Phase 5.6 — Drop columns that are never populated by any stored procedure
--   or Java code:
--     - contract_utilization: never written to
--     - actual_utilization: never written to
--     - helped_colleague_billable_hours: never written to
--
-- Also update the BiDataPerDay entity class to remove the corresponding fields.
-- =============================================================================

ALTER TABLE bi_data_per_day
    DROP COLUMN contract_utilization,
    DROP COLUMN actual_utilization,
    DROP COLUMN helped_colleague_billable_hours;
