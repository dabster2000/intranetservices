-- Disable legacy ev_refresh_facts_daily event.
-- This event populates _mv tables (fact_project_financials_mv,
-- fact_employee_monthly_mv) which are no longer referenced by any code.
-- The _mat tables (populated by sp_refresh_fact_tables) have replaced them.

ALTER EVENT ev_refresh_facts_daily DISABLE;
