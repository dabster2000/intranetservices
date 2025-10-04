-- V84: Fix Work Cache Concurrency Issues
-- This migration adds advisory locking to prevent concurrent cache refresh operations
-- that cause deadlocks and duplicate key violations

-- =========================================================================
-- Recreate the refresh_work_full_cache procedure with advisory locking
-- =========================================================================

DROP PROCEDURE IF EXISTS `refresh_work_full_cache`;

DELIMITER $$

CREATE PROCEDURE `refresh_work_full_cache`(IN from_date DATE, IN to_date DATE)
BEGIN
    DECLARE lock_acquired INT DEFAULT 0;
    DECLARE lock_name VARCHAR(64) DEFAULT 'work_full_cache_refresh';

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        -- Always release lock on error
        DO RELEASE_LOCK(lock_name);
        ROLLBACK;
        RESIGNAL;
    END;

    -- Try to acquire global lock with immediate timeout (0 seconds)
    -- This prevents concurrent refresh operations from running simultaneously
    SET lock_acquired = GET_LOCK(lock_name, 0);

    IF lock_acquired = 1 THEN
        -- Lock acquired successfully, proceed with cache refresh
        START TRANSACTION;

        -- Delete existing cache entries for the date range
        DELETE FROM work_full_cache
        WHERE registered >= from_date AND registered < to_date;

        -- Insert fresh data from the optimized view
        INSERT INTO work_full_cache (
            uuid, useruuid, workas, taskuuid, workduration, registered,
            billable, paid_out, rate, discount, name, projectuuid,
            clientuuid, contractuuid, contract_company_uuid,
            consultant_company_uuid, type, comments, updated_at, cache_updated_at
        )
        SELECT
            uuid, useruuid, workas, taskuuid, workduration, registered,
            billable, paid_out, rate, discount, name, projectuuid,
            clientuuid, contractuuid, contract_company_uuid,
            consultant_company_uuid, type, comments, updated_at, NOW()
        FROM work_full_optimized
        WHERE registered >= from_date AND registered < to_date;

        COMMIT;

        -- Release the lock
        DO RELEASE_LOCK(lock_name);
    ELSE
        -- Lock not acquired (another refresh is in progress)
        -- Signal a specific error that the application can handle gracefully
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Cache refresh already in progress, skipping this execution',
            MYSQL_ERRNO = 1205; -- Lock wait timeout error code
    END IF;
END$$

DELIMITER ;
