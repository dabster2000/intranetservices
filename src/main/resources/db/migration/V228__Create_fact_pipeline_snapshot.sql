-- =============================================================================
-- Migration V228: Create fact_pipeline_snapshot table
--
-- Purpose:
--   Store monthly point-in-time snapshots of the sales pipeline. Each row
--   captures the state of one opportunity at a given snapshot month, enabling:
--   - Historical pipeline trend analysis (month-over-month growth/decline)
--   - Pipeline coverage ratio tracking
--   - Forecasting model calibration with historical data
--
-- Grain: Opportunity-SnapshotMonth (one row per lead per monthly snapshot)
--
-- Population:
--   - sp_snapshot_pipeline(YYYYMM) captures current state
--   - sp_backfill_pipeline_snapshots() reconstructs 12 months of history
--   - ev_monthly_pipeline_snapshot event runs on 1st of each month
--
-- Key metrics:
--   expected_revenue_dkk  = rate * 160.33 * (allocation/100) * consultant_count
--   weighted_pipeline_dkk = expected_revenue_dkk * (probability_pct/100)
--
-- Rollback: DROP TABLE IF EXISTS fact_pipeline_snapshot;
-- =============================================================================

CREATE TABLE IF NOT EXISTS fact_pipeline_snapshot (
    snapshot_id VARCHAR(80) NOT NULL PRIMARY KEY,
    snapshot_month CHAR(6) NOT NULL,
    opportunity_uuid VARCHAR(36) NOT NULL,
    client_uuid VARCHAR(36) NULL,
    company_uuid VARCHAR(36) NULL,
    stage_id VARCHAR(20) NOT NULL,
    practice VARCHAR(10) NULL,
    rate DOUBLE DEFAULT 0,
    period_months INT DEFAULT 0,
    allocation INT DEFAULT 0,
    consultant_count INT DEFAULT 1,
    is_extension TINYINT(1) DEFAULT 0,
    expected_revenue_dkk DOUBLE DEFAULT 0,
    probability_pct INT DEFAULT 0,
    weighted_pipeline_dkk DOUBLE DEFAULT 0,
    INDEX idx_fps_month (snapshot_month),
    INDEX idx_fps_month_stage (snapshot_month, stage_id),
    INDEX idx_fps_month_practice (snapshot_month, practice)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
