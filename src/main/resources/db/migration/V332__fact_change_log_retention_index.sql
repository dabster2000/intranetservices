-- ============================================================================
-- V332: Add retention index on fact_change_log
-- ============================================================================
-- Purpose
--   Support the daily retention DELETE issued by ChangeLogRetentionBatchlet
--   (`processed_at IS NOT NULL AND processed_at < DATE_SUB(NOW(), INTERVAL :days DAY)`).
--
--   The existing idx_unprocessed (processed_at, affected_date, useruuid) helps
--   the IS NOT NULL predicate as a leading-column scan, but does not cover
--   created_at and is wider than necessary for the prune path. The new index
--   (processed_at, created_at) is narrower and covering for the retention
--   query — the optimizer can use it to range-scan rows whose processed_at
--   falls below the cutoff without touching the heap.
--
-- Background
--   fact_change_log was renamed from bi_change_log in V168. The change-log
--   queue is drained every 5 minutes by sp_incremental_bi_refresh() but never
--   pruned, so the table grows unbounded until this batchlet starts running.
--
-- Idempotency
--   Online DDL (ALGORITHM=INPLACE, LOCK=NONE) so concurrent reads/writes are
--   not blocked. Safe to re-apply after a failed migration: MariaDB will
--   error out cleanly if the index already exists.
--
-- Related Java
--   - dk.trustworks.intranet.aggregates.bidata.jobs.ChangeLogRetentionBatchlet
--     (daily 02:15 UTC retention DELETE; uses this index)
--   - dk.trustworks.intranet.aggregates.bidata.jobs.FactChangeLogBacklogAlertBatchlet
--     (5-minute backlog monitor; uses idx_unprocessed for pending counts)
-- ============================================================================

ALTER TABLE fact_change_log
    ADD INDEX idx_processed_for_deletion (processed_at, created_at),
    ALGORITHM=INPLACE, LOCK=NONE;
