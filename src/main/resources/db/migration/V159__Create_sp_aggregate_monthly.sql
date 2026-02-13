-- =============================================================================
-- Migration V159: Create sp_aggregate_monthly
--
-- Purpose:
--   Refreshes monthly aggregation tables from daily BI data.
--   Currently refreshes company_work_per_month which is used by
--   company-level utilization endpoints.
--
-- Parameters:
--   p_start_date  - Start of recalculation window (inclusive)
--   p_end_date    - End of recalculation window (exclusive)
--
-- Tables refreshed:
--   - company_work_per_month (company-level monthly work hours and revenue)
-- =============================================================================

DELIMITER //

CREATE PROCEDURE sp_aggregate_monthly(
    IN p_start_date DATE,
    IN p_end_date   DATE
)
BEGIN
    -- Refresh company_work_per_month for the affected date range.
    -- Delete existing rows for months within the range, then re-insert.
    DELETE FROM company_work_per_month
    WHERE (year > YEAR(p_start_date) OR (year = YEAR(p_start_date) AND month >= MONTH(p_start_date)))
      AND (year < YEAR(p_end_date)   OR (year = YEAR(p_end_date)   AND month <= MONTH(p_end_date)));

    INSERT INTO company_work_per_month (uuid, year, month, consultant_company_uuid, hours, billed)
    SELECT
        UUID() AS uuid,
        bdd.year,
        bdd.month,
        bdd.companyuuid AS consultant_company_uuid,
        COALESCE(SUM(bdd.registered_billable_hours), 0) AS hours,
        COALESCE(SUM(bdd.registered_amount), 0) AS billed
    FROM bi_data_per_day bdd
    WHERE bdd.document_date >= p_start_date
      AND bdd.document_date < p_end_date
      AND bdd.consultant_type IN ('CONSULTANT', 'STUDENT')
      AND bdd.status_type = 'ACTIVE'
      AND bdd.companyuuid IS NOT NULL
    GROUP BY bdd.year, bdd.month, bdd.companyuuid
    HAVING SUM(bdd.registered_billable_hours) > 0
        OR SUM(bdd.registered_amount) > 0;
END //

DELIMITER ;
