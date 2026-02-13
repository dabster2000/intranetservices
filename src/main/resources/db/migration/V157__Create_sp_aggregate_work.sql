-- =============================================================================
-- Migration V157: Create sp_aggregate_work
--
-- Purpose:
--   Set-based work aggregation that replaces the Java WorkAggregateService
--   row-by-row logic. Updates bi_data_per_day with registered billable hours
--   and revenue from the work_full view.
--
-- Parameters:
--   p_start_date  - Start of recalculation window (inclusive)
--   p_end_date    - End of recalculation window (exclusive)
--
-- Business rules:
--   - registered_billable_hours = SUM(workduration WHERE rate > 0)
--   - registered_amount = SUM(workduration * rate WHERE rate > 0)
--   - Only updates existing rows (created by sp_recalculate_availability)
--   - Resets to 0 for dates with no billable work
-- =============================================================================

DELIMITER //

CREATE PROCEDURE sp_aggregate_work(
    IN p_start_date DATE,
    IN p_end_date   DATE
)
BEGIN
    -- First, reset billable hours and revenue for the date range.
    -- This ensures dates with no work entries get 0 values.
    UPDATE bi_data_per_day
    SET registered_billable_hours = 0,
        registered_amount = 0,
        last_update = NOW()
    WHERE document_date >= p_start_date
      AND document_date < p_end_date;

    -- Then update with actual work data from work_full view.
    UPDATE bi_data_per_day bdd
    JOIN (
        SELECT
            wf.useruuid,
            wf.registered,
            SUM(CASE WHEN wf.rate > 0 AND wf.workduration > 0 THEN wf.workduration ELSE 0 END) AS billable_hours,
            SUM(CASE WHEN wf.rate > 0 AND wf.workduration > 0 THEN wf.workduration * wf.rate ELSE 0 END) AS revenue
        FROM work_full wf
        WHERE wf.registered >= p_start_date
          AND wf.registered < p_end_date
        GROUP BY wf.useruuid, wf.registered
    ) w ON bdd.useruuid = w.useruuid AND bdd.document_date = w.registered
    SET bdd.registered_billable_hours = w.billable_hours,
        bdd.registered_amount = w.revenue,
        bdd.last_update = NOW()
    WHERE bdd.document_date >= p_start_date
      AND bdd.document_date < p_end_date;
END //

DELIMITER ;
