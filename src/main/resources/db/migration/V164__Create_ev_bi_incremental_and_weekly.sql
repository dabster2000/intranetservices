-- =============================================================================
-- Migration V164: Create MariaDB events for incremental and weekly refresh
--
-- Purpose:
--   Phase 4 scheduling — two new MariaDB events:
--   1. ev_bi_incremental_refresh — runs every 5 minutes, processes change log
--   2. ev_bi_weekly_full_rebuild — runs every Sunday at 04:00, full 48-month rebuild
--
-- Prerequisites:
--   - event_scheduler = ON in MariaDB configuration
--   - sp_incremental_bi_refresh (V163)
--   - sp_nightly_bi_refresh (V160, updated in V162)
--
-- Together with the existing ev_bi_nightly_refresh (V160.1), the schedule is:
--   - Every 5 min:  sp_incremental_bi_refresh()       [process change log]
--   - Daily 03:00:  sp_nightly_bi_refresh(3, 24)      [3-month lookback]
--   - Sunday 04:00: sp_nightly_bi_refresh(24, 24)     [full 48-month rebuild]
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Incremental refresh: every 5 minutes, process change log
-- ---------------------------------------------------------------------------
CREATE EVENT IF NOT EXISTS ev_bi_incremental_refresh
ON SCHEDULE EVERY 5 MINUTE
ENABLE
DO
BEGIN
    CALL sp_incremental_bi_refresh();
END;

-- ---------------------------------------------------------------------------
-- 2. Weekly full rebuild: Sunday 04:00, 24-month lookback + 24-month forward
-- ---------------------------------------------------------------------------
CREATE EVENT IF NOT EXISTS ev_bi_weekly_full_rebuild
ON SCHEDULE EVERY 1 WEEK
    STARTS CONCAT(
        DATE_ADD(CURDATE(), INTERVAL (8 - DAYOFWEEK(CURDATE())) % 7 DAY),
        ' 04:00:00'
    )
ENABLE
DO
BEGIN
    CALL sp_nightly_bi_refresh(24, 24);
END;
