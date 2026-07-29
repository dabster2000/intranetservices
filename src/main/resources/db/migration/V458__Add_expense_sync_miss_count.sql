-- =============================================================================
-- Migration V458: Grace-period counter for the nightly e-conomic expense sync
--
-- Background (2026-07-28 prod incident)
--   ExpenseSyncBatchlet marked 224 still-valid expenses DELETED in a single
--   run. The accountant had moved unbooked postings dated after the
--   fiscal-year start (1 Jul) into NEW temporary e-conomic journals so voucher
--   numbering can restart for the new year — a legitimate, recurring year-end
--   workflow. The sync's journal lookup is pinned to the stored journal
--   number, so every moved voucher was "not found" (HTTP 200, empty result)
--   and immediately marked DELETED on the first miss.
--
-- Fix
--   expenses.sync_miss_count counts CONSECUTIVE sync runs in which the voucher
--   was found neither in ANY journal nor booked under the accounting year.
--   ExpenseSyncBatchlet only marks DELETED once the counter reaches a
--   configurable threshold (dk.trustworks.expense.economics-sync.
--   delete-miss-threshold, default 3) and resets it to 0 whenever the voucher
--   is found again. Server-managed; never exposed to or accepted from clients.
-- =============================================================================

ALTER TABLE expenses
    ADD COLUMN sync_miss_count INT NULL DEFAULT 0;
