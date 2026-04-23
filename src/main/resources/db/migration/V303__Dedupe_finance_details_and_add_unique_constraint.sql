-- =============================================================================
-- Migration V303: Deduplicate finance_details and add unique constraint
--
-- Root cause:
--   finance_details has every row duplicated exactly 2x since at least Jul 2023.
--   Each GL entry (companyuuid, entrynumber, accountnumber, amount, expensedate)
--   appears twice with different auto-increment `id` values. This causes
--   fact_opex, fact_opex_mat, and every consumer of finance_details
--   (CxoFinanceService, ExpenseService, IntercompanyCalcService, etc.) to
--   report double the actual cost values.
--
--   Concrete impact example: Executive Dashboard "Revenue & Cost Trend"
--   TTM Average Cost showed 19.1M DKK/month (actual ~9.5M DKK/month).
--
-- Likely cause (unconfirmed):
--   FinanceLoadJob.scheduleFinanceLoadEconomics (cron "0 0 21 * * ?") in
--   BatchScheduler has no concurrent-execution guard. Two instances (ECS
--   tasks) firing the cron at the same second would both call
--   economicsService.clean() + re-insert — if the first commits its inserts
--   before the second's clean() runs, rows survive twice.
--
--   Separate to this migration, the Java guard is added in the companion
--   code change (BatchScheduler.scheduleFinanceLoadEconomics).
--
-- Verified invariants before writing this migration:
--   - 149,754 total rows, exactly 2.00x duplication factor every month
--     since Jul 2023 (min observed).
--   - No group has count > 2 (confirmed with HAVING COUNT(*) > 2 → 0 rows).
--   - NULL text: 40 rows; amount / expensedate / entrynumber: never NULL/0.
--   - For every (companyuuid, entrynumber, accountnumber, amount, expensedate)
--     tuple, COUNT(DISTINCT text) is always 1 — the 5-column key is
--     sufficient; `text` does not need to be in the unique constraint.
--   - No foreign keys reference finance_details.
--
-- What this migration does:
--   1. DELETE non-MIN(id) duplicate rows, keeping the earliest `id` per
--      (companyuuid, entrynumber, accountnumber, amount, expensedate) group.
--   2. ADD UNIQUE INDEX on those 5 columns to prevent future duplicates
--      (future duplicate INSERT attempts will fail fast instead of silently
--      doubling cost reports).
--   3. Immediately re-materialize fact_opex_mat so the Executive Dashboard
--      and every downstream API reflects correct numbers without waiting
--      for the nightly sp_nightly_bi_refresh() run.
--
-- Expected effect post-migration:
--   - finance_details row count: 149,754 → ~74,877 (halved).
--   - fact_opex_mat opex_amount_dkk totals: halved (matches reality).
--   - Executive Dashboard TTM Avg Cost: 19.1M → ~9.5M DKK.
--   - Any other report previously showing 2x inflated GL cost is now correct.
--
-- Rollback:
--   No schema rollback for deletion of duplicates (the row ids are gone).
--   If the unique index causes problems with a legitimate edge case, drop
--   it with: ALTER TABLE finance_details DROP INDEX uq_fd_logical_key;
--
--   If data needs to be restored, re-run FinanceLoadJob.loadEconomicsData()
--   — it will re-fetch from e-conomic and repopulate finance_details.
--
-- Performance:
--   - DELETE: ~75K rows, completes in seconds on RDS db.r6g.large-class.
--   - ADD UNIQUE INDEX: ~75K rows post-dedup, seconds.
--   - TRUNCATE + INSERT fact_opex_mat: same cost as a normal refresh block.
--
-- Idempotent: yes. Running it again after success is a no-op:
--   - DELETE finds no dupes to remove (all already removed).
--   - ADD UNIQUE INDEX IF NOT EXISTS avoids duplicate index creation.
--   - TRUNCATE + INSERT re-materializes (harmless).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Delete duplicate rows, keeping MIN(id) per logical key
--
--    GROUP BY handles NULL text correctly (groups NULLs together), so NULL-
--    text duplicates (40 rows observed) are also collapsed.
-- ---------------------------------------------------------------------------

DELETE fd
FROM finance_details fd
INNER JOIN (
    SELECT companyuuid,
           entrynumber,
           accountnumber,
           amount,
           expensedate,
           MIN(id) AS keep_id
    FROM finance_details
    GROUP BY companyuuid, entrynumber, accountnumber, amount, expensedate
    HAVING COUNT(*) > 1
) keepers
    ON  fd.companyuuid    = keepers.companyuuid
    AND fd.entrynumber    = keepers.entrynumber
    AND fd.accountnumber  = keepers.accountnumber
    AND fd.amount         = keepers.amount
    AND fd.expensedate    = keepers.expensedate
    AND fd.id            != keepers.keep_id;

-- ---------------------------------------------------------------------------
-- 2. Add UNIQUE INDEX to prevent future duplicates
--
--    The 5-column tuple is sufficient (verified: no distinct-text collisions).
--    A future INSERT of a duplicate GL entry will fail with
--    ErrorCode 1062 (Duplicate entry) instead of silently creating 2x rows.
--
--    Note: IF NOT EXISTS clause is MariaDB-specific (supported since 10.1+).
-- ---------------------------------------------------------------------------

ALTER TABLE finance_details
    ADD UNIQUE INDEX IF NOT EXISTS uq_fd_logical_key
        (companyuuid, entrynumber, accountnumber, amount, expensedate);

-- ---------------------------------------------------------------------------
-- 3. Immediate re-materialization of fact_opex_mat
--
--    Rebuilds from the fact_opex view so every consumer of fact_opex_mat
--    (Executive Dashboard, CXO dashboards, BI routes) reflects deduplicated
--    totals immediately, without waiting for the 03:00 nightly refresh.
--
--    We do NOT call sp_refresh_fact_tables() here because it refreshes all
--    11 mat tables and would hold Flyway too long. Only fact_opex_mat needs
--    refreshing — the other mat tables are derived from sources (bi_data_per_day,
--    fact_*) that are not affected by this migration.
-- ---------------------------------------------------------------------------

TRUNCATE TABLE fact_opex_mat;

INSERT IGNORE INTO fact_opex_mat
    (opex_id, company_id, cost_center_id, expense_category_id,
     expense_subcategory_id, practice_id, sector_id,
     month_key, year, month_number,
     fiscal_year, fiscal_month_number, fiscal_month_key,
     opex_amount_dkk, invoice_count, is_payroll_flag,
     cost_type, data_source)
SELECT
    opex_id, company_id, cost_center_id, expense_category_id,
    expense_subcategory_id, practice_id, sector_id,
    month_key, year, month_number,
    fiscal_year, fiscal_month_number, fiscal_month_key,
    opex_amount_dkk, invoice_count, is_payroll_flag,
    cost_type, data_source
FROM fact_opex;
