-- =============================================================================
-- Migration V167: Add unique constraint on bi_budget_per_day
--
-- Purpose:
--   Phase 5.7 — Ensure data integrity by preventing duplicate budget entries
--   for the same (user, date, contract) combination. The current delete-then-
--   insert pattern in sp_recalculate_budgets works, but without a unique
--   constraint, concurrent inserts or incomplete deletes could create duplicates.
--
-- Strategy:
--   1. Delete duplicate rows, keeping the one with the highest id per group
--   2. Add the unique constraint
-- =============================================================================

-- Step 1: Remove duplicates — keep the row with the highest id for each
-- (useruuid, document_date, contractuuid) combination
DELETE b FROM bi_budget_per_day b
INNER JOIN (
    SELECT useruuid, document_date, contractuuid, MAX(id) AS max_id
    FROM bi_budget_per_day
    GROUP BY useruuid, document_date, contractuuid
    HAVING COUNT(*) > 1
) dups ON b.useruuid = dups.useruuid
      AND b.document_date = dups.document_date
      AND b.contractuuid = dups.contractuuid
      AND b.id < dups.max_id;

-- Step 2: Add the unique constraint
ALTER TABLE bi_budget_per_day
    ADD UNIQUE KEY uniq_budget_day (useruuid, document_date, contractuuid);
