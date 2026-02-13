-- =============================================================================
-- Migration V160b: Create ev_bi_nightly_refresh MariaDB Event
--
-- Purpose:
--   Replaces the Java BatchScheduler.trigger() scheduled method with a
--   MariaDB scheduled event. This keeps scheduling at the database layer
--   alongside the stored procedures, making recalculation resilient to
--   application server restarts/deployments.
--
-- Schedule: Every day at 03:00 (same as the previous Java cron schedule)
-- Parameters: 3-month lookback, 24-month forward projection
--
-- Consistent with existing pattern: ev_refresh_facts_daily already exists
-- for fact table refreshes.
--
-- Prerequisites:
--   - event_scheduler = ON in MariaDB configuration
--   - sp_nightly_bi_refresh must exist (V160)
-- =============================================================================

CREATE EVENT IF NOT EXISTS ev_bi_nightly_refresh
ON SCHEDULE EVERY 1 DAY
    STARTS CONCAT(CURDATE() + INTERVAL 1 DAY, ' 03:00:00')
ENABLE
DO
BEGIN
    CALL sp_nightly_bi_refresh(3, 24);
END;
