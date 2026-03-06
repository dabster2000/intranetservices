-- =============================================================================
-- Migration V233: Create monthly pipeline snapshot event + initial backfill
--
-- Purpose:
--   1. Create a MariaDB event that automatically takes a pipeline snapshot
--      on the 1st of each month at 05:00
--   2. Backfill 12 months of historical snapshots using sp_backfill
--   3. Take an immediate snapshot of the current month
--
-- Prerequisites:
--   - fact_pipeline_snapshot table (V228)
--   - sp_snapshot_pipeline procedure (V229)
--   - sp_backfill_pipeline_snapshots procedure (V229)
--   - MariaDB event_scheduler must be ON (SET GLOBAL event_scheduler = ON)
--
-- Notes:
--   - The event uses STARTS on the 1st of the next month at 05:00 UTC
--   - If event_scheduler is OFF, the event exists but will not fire;
--     enable it via: SET GLOBAL event_scheduler = ON;
--   - The backfill is safe to re-run (only fills months with no existing data)
--
-- Rollback:
--   DROP EVENT IF EXISTS ev_monthly_pipeline_snapshot;
--   DELETE FROM fact_pipeline_snapshot;
-- =============================================================================

-- 1. Create monthly event to take pipeline snapshot on 1st of each month
DELIMITER $$

CREATE EVENT IF NOT EXISTS ev_monthly_pipeline_snapshot
ON SCHEDULE EVERY 1 MONTH
    STARTS CONCAT(DATE_FORMAT(DATE_ADD(LAST_DAY(CURDATE()), INTERVAL 1 DAY), '%Y-%m-%d'), ' 05:00:00')
ENABLE
DO
BEGIN
    CALL sp_snapshot_pipeline(DATE_FORMAT(CURDATE(), '%Y%m'));
END$$

DELIMITER ;

-- 2. Backfill 12 months of historical snapshots
CALL sp_backfill_pipeline_snapshots();

-- 3. Take immediate snapshot of the current month
CALL sp_snapshot_pipeline(DATE_FORMAT(CURDATE(), '%Y%m'));
