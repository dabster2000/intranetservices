-- =============================================================================
-- Migration V297: Defensive repair for V291/V292/V293 partial application
-- =============================================================================
-- Background:
--   The same systematic partial-application issue that hit V286 on staging
--   also hit:
--     - V291 (Add invoice send_by) — column missing despite success=1
--     - V292 (Drop clientdatauuid from contracts and project) — only project
--             column dropped, contracts column remained
--     - V293 (Drop clientdata table) — table still present despite success=1
--
--   All three were recorded as success in flyway_schema_history but the SQL
--   did not (or only partially) take effect. Cause unclear; the statements
--   work fine when re-run individually.
--
-- Effect:
--   - Staging (already patched manually for send_by): no-op for send_by;
--     completes V292/V293 if not already done.
--   - Production (clean V285 → runs V286-V296 then V297): no-op if all
--     prior migrations succeed fully, safety net otherwise.
-- =============================================================================

-- V291 repair: invoices.send_by
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS send_by VARCHAR(10) NULL
    COMMENT 'Delivery method used at booking time: ean, Email, or NULL (no delivery)';

-- V292 repair: drop legacy clientdatauuid columns (idempotent)
ALTER TABLE contracts DROP COLUMN IF EXISTS clientdatauuid;
ALTER TABLE project   DROP COLUMN IF EXISTS clientdatauuid;

-- V293 repair: drop legacy clientdata table (idempotent)
DROP TABLE IF EXISTS clientdata;
