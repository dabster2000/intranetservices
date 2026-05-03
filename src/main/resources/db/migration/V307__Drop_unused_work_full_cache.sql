-- Drop the unused work_full_cache table and its refresh procedure.
--
-- Production audit on 2026-05-03 showed:
--   - 0 Java consumers (only WorkCacheRefreshJob wrote to it)
--   - 0 SQL views or stored procedures referenced the table
--   - The refresh procedure produced ~70 duplicate-PK errors per run
--   - The Quarkus job (now deleted) was scheduled every 5m / 15m / 1h
-- Removing the cache eliminates the largest single source of error noise
-- in production logs without any functional impact.
--
-- Original creation:  V83 (2024) — work_full_cache + refresh_work_full_cache
-- Concurrency fix:    V84 (2024) — added GET_LOCK guard to the procedure
-- Both can be safely retired now that the cache is unused.

DROP PROCEDURE IF EXISTS refresh_work_full_cache;
DROP TABLE IF EXISTS work_full_cache;
