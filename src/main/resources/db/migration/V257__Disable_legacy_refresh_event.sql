-- Disable legacy ev_refresh_facts_daily event if it exists.
-- This event populates _mv tables (fact_project_financials_mv,
-- fact_employee_monthly_mv) which are no longer referenced by any code.
-- The _mat tables (populated by sp_refresh_fact_tables) have replaced them.
-- Note: event was created manually (not via migration), so it may not exist
-- in all environments.

DELIMITER $$

CREATE PROCEDURE _tmp_disable_legacy_event()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    SELECT COUNT(*) INTO v_exists
      FROM information_schema.EVENTS
     WHERE EVENT_SCHEMA = DATABASE()
       AND EVENT_NAME = 'ev_refresh_facts_daily';

    IF v_exists > 0 THEN
        ALTER EVENT ev_refresh_facts_daily DISABLE;
    END IF;
END$$

DELIMITER ;

CALL _tmp_disable_legacy_event();
DROP PROCEDURE _tmp_disable_legacy_event;
