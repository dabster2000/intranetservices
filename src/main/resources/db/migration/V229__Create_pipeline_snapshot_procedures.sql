-- =============================================================================
-- Migration V229: Create pipeline snapshot stored procedures
--
-- Purpose:
--   Two stored procedures for managing pipeline snapshots:
--
--   1. sp_snapshot_pipeline(p_snapshot_month CHAR(6))
--      - Captures a point-in-time snapshot of the current active pipeline
--      - Idempotent: deletes existing rows for that month before inserting
--      - Only captures leads NOT WON/LOST with rate > 0 and period > 0
--      - Revenue formula: rate * 160.33 * (allocation/100) * consultant_count
--      - Probability mapping: DETECTED=10, QUALIFIED=25, SHORTLISTED=40,
--        PROPOSAL=50, NEGOTIATION=75
--
--   2. sp_backfill_pipeline_snapshots()
--      - Reconstructs 12 months of historical snapshots
--      - Only backfills months that have no existing rows
--      - For each past month, captures leads that were active at that time:
--        * Active = created <= end-of-month AND not yet resolved at that point
--        * WON leads (won after that month) use NEGOTIATION as stage
--        * LOST leads (lost after that month) use lost_at_stage (or DETECTED)
--        * Still-active leads use current status
--
-- Dependencies:
--   - sales_lead table (practice column, post-V180 rename)
--   - sales_lead_consultant table
--   - userstatus table (for company_uuid derivation)
--   - fact_pipeline_snapshot table (V228)
--
-- Rollback:
--   DROP PROCEDURE IF EXISTS sp_snapshot_pipeline;
--   DROP PROCEDURE IF EXISTS sp_backfill_pipeline_snapshots;
-- =============================================================================

DELIMITER $$

-- ---------------------------------------------------------------------------
-- sp_snapshot_pipeline: Capture current pipeline state for a given month
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_snapshot_pipeline$$

CREATE PROCEDURE sp_snapshot_pipeline(IN p_snapshot_month CHAR(6))
BEGIN
    -- Delete existing snapshot for idempotency
    DELETE FROM fact_pipeline_snapshot WHERE snapshot_month = p_snapshot_month;

    INSERT INTO fact_pipeline_snapshot (
        snapshot_id, snapshot_month, opportunity_uuid, client_uuid, company_uuid,
        stage_id, practice, rate, period_months, allocation, consultant_count,
        is_extension, expected_revenue_dkk, probability_pct, weighted_pipeline_dkk
    )
    SELECT
        CONCAT(p_snapshot_month, '-', sl.uuid) AS snapshot_id,
        p_snapshot_month,
        sl.uuid,
        sl.clientuuid,
        -- Company from lead manager's userstatus (same pattern as fact_pipeline V180)
        COALESCE(
            (SELECT us.companyuuid
             FROM userstatus us
             WHERE us.useruuid = sl.leadmanager
               AND us.statusdate <= COALESCE(sl.closedate, CURDATE())
             ORDER BY us.statusdate DESC
             LIMIT 1),
            'd8894494-2fb4-4f72-9e05-e6032e6dd691'
        ) AS company_uuid,
        sl.status AS stage_id,
        sl.practice,
        sl.rate,
        sl.period AS period_months,
        sl.allocation,
        GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid), 1
        )) AS consultant_count,
        sl.extension AS is_extension,
        -- Expected monthly revenue = rate * standard_hours * allocation% * consultants
        (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc2 WHERE slc2.leaduuid = sl.uuid), 1
        ))) AS expected_revenue_dkk,
        CASE sl.status
            WHEN 'DETECTED'    THEN 10
            WHEN 'QUALIFIED'   THEN 25
            WHEN 'SHORTLISTED' THEN 40
            WHEN 'PROPOSAL'    THEN 50
            WHEN 'NEGOTIATION' THEN 75
            ELSE 10
        END AS probability_pct,
        -- Weighted = expected * probability / 100
        (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc3 WHERE slc3.leaduuid = sl.uuid), 1
        ))) * (CASE sl.status
            WHEN 'DETECTED'    THEN 10
            WHEN 'QUALIFIED'   THEN 25
            WHEN 'SHORTLISTED' THEN 40
            WHEN 'PROPOSAL'    THEN 50
            WHEN 'NEGOTIATION' THEN 75
            ELSE 10
        END / 100.0) AS weighted_pipeline_dkk
    FROM sales_lead sl
    WHERE sl.status NOT IN ('WON', 'LOST')
      AND sl.rate > 0
      AND sl.period > 0;
END$$


-- ---------------------------------------------------------------------------
-- sp_backfill_pipeline_snapshots: Reconstruct 12 months of history
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_backfill_pipeline_snapshots$$

CREATE PROCEDURE sp_backfill_pipeline_snapshots()
BEGIN
    DECLARE v_counter INT DEFAULT 0;
    DECLARE v_snapshot_month CHAR(6);
    DECLARE v_month_start DATE;
    DECLARE v_month_end DATE;
    DECLARE v_existing INT;

    -- Iterate from 12 months ago up to (and including) the current month
    WHILE v_counter <= 12 DO
        SET v_month_start = DATE_ADD(
            DATE(CONCAT(YEAR(CURDATE()), '-', LPAD(MONTH(CURDATE()), 2, '0'), '-01')),
            INTERVAL (v_counter - 12) MONTH
        );
        SET v_month_end   = LAST_DAY(v_month_start);
        SET v_snapshot_month = DATE_FORMAT(v_month_start, '%Y%m');

        -- Only backfill if no rows exist for this month
        SELECT COUNT(*) INTO v_existing
        FROM fact_pipeline_snapshot
        WHERE snapshot_month = v_snapshot_month;

        IF v_existing = 0 THEN
            INSERT INTO fact_pipeline_snapshot (
                snapshot_id, snapshot_month, opportunity_uuid, client_uuid,
                company_uuid, stage_id, practice, rate, period_months,
                allocation, consultant_count, is_extension,
                expected_revenue_dkk, probability_pct, weighted_pipeline_dkk
            )
            SELECT
                CONCAT(v_snapshot_month, '-', sl.uuid) AS snapshot_id,
                v_snapshot_month,
                sl.uuid,
                sl.clientuuid,
                COALESCE(
                    (SELECT us.companyuuid
                     FROM userstatus us
                     WHERE us.useruuid = sl.leadmanager
                       AND us.statusdate <= v_month_end
                     ORDER BY us.statusdate DESC
                     LIMIT 1),
                    'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                ) AS company_uuid,
                -- Determine the stage the lead was in at that point in time
                CASE
                    -- WON leads that were won AFTER this month: they were likely in NEGOTIATION
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 'NEGOTIATION'
                    -- LOST leads that were lost AFTER this month: use lost_at_stage
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN COALESCE(sl.lost_at_stage, 'DETECTED')
                    -- Still-active leads: use current status
                    ELSE sl.status
                END AS stage_id,
                sl.practice,
                sl.rate,
                sl.period AS period_months,
                sl.allocation,
                GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid), 1
                )) AS consultant_count,
                sl.extension AS is_extension,
                -- Expected revenue
                (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc2 WHERE slc2.leaduuid = sl.uuid), 1
                ))) AS expected_revenue_dkk,
                -- Probability based on reconstructed stage
                CASE
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 75  -- NEGOTIATION probability
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN CASE COALESCE(sl.lost_at_stage, 'DETECTED')
                            WHEN 'DETECTED'    THEN 10
                            WHEN 'QUALIFIED'   THEN 25
                            WHEN 'SHORTLISTED' THEN 40
                            WHEN 'PROPOSAL'    THEN 50
                            WHEN 'NEGOTIATION' THEN 75
                            ELSE 10
                        END
                    ELSE CASE sl.status
                        WHEN 'DETECTED'    THEN 10
                        WHEN 'QUALIFIED'   THEN 25
                        WHEN 'SHORTLISTED' THEN 40
                        WHEN 'PROPOSAL'    THEN 50
                        WHEN 'NEGOTIATION' THEN 75
                        ELSE 10
                    END
                END AS probability_pct,
                -- Weighted pipeline
                (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc3 WHERE slc3.leaduuid = sl.uuid), 1
                ))) * (CASE
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 75
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN CASE COALESCE(sl.lost_at_stage, 'DETECTED')
                            WHEN 'DETECTED'    THEN 10
                            WHEN 'QUALIFIED'   THEN 25
                            WHEN 'SHORTLISTED' THEN 40
                            WHEN 'PROPOSAL'    THEN 50
                            WHEN 'NEGOTIATION' THEN 75
                            ELSE 10
                        END
                    ELSE CASE sl.status
                        WHEN 'DETECTED'    THEN 10
                        WHEN 'QUALIFIED'   THEN 25
                        WHEN 'SHORTLISTED' THEN 40
                        WHEN 'PROPOSAL'    THEN 50
                        WHEN 'NEGOTIATION' THEN 75
                        ELSE 10
                    END
                END / 100.0) AS weighted_pipeline_dkk
            FROM sales_lead sl
            WHERE sl.created <= v_month_end
              AND sl.rate > 0
              AND sl.period > 0
              AND (
                  -- Currently active leads (not WON/LOST)
                  sl.status NOT IN ('WON', 'LOST')
                  -- WON leads that were won AFTER this month (so they were active during it)
                  OR (sl.status = 'WON' AND sl.won_date > v_month_end)
                  -- LOST leads that were lost AFTER this month
                  OR (sl.status = 'LOST' AND sl.last_updated > v_month_end)
              );
        END IF;

        SET v_counter = v_counter + 1;
    END WHILE;
END$$

DELIMITER ;
