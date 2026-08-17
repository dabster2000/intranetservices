-- P0 expense-decision fix (2026-08-17).
--
-- 1) attention_since — when the row last entered NEEDS_ATTENTION. The review queue's
--    age and Overdue math anchor here instead of datemodified, which every decision
--    write resets (a failed approve made a 2024 receipt read "0d" and drop out of the
--    Overdue segment). Maintained by the Expense entity hook (syncDerivedState) and
--    mirrored by ExpenseService.updateStatus's bulk write: set on entry, preserved
--    while the row stays in NEEDS_ATTENTION, cleared on exit.
ALTER TABLE expenses
  ADD COLUMN attention_since DATETIME NULL
    COMMENT 'When the row last entered NEEDS_ATTENTION; queue-age anchor. NULL outside NEEDS_ATTENTION.';

-- Backfill: current inbox rows anchor at their last modification date — the best
-- available signal, even though decision churn may have advanced it.
UPDATE expenses
   SET attention_since = datemodified
 WHERE state = 'NEEDS_ATTENTION';

-- 2) CLOSED_MANUAL — new terminal value on the existing varchar status/state columns
--    (an expense resolved outside the pipeline: booked manually in e-conomic, or
--    written off). No DDL needed; recorded here for the migration timeline. The value
--    is excluded by every batch scan (upload reads state=APPROVED; sync reads
--    VERIFIED_UNBOOKED; orphan detection reads VOUCHER_CREATED/UPLOADED/UP_FAILED/
--    VERIFIED_UNBOOKED; retry reads UP_FAILED).
