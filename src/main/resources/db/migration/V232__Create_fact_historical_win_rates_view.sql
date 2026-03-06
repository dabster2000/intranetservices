-- =============================================================================
-- Migration V232: Create fact_historical_win_rates view
--
-- Purpose:
--   Derives actual (calibrated) win probabilities from historically resolved
--   sales leads (WON and LOST). Compares calibrated rates against the static
--   probability assumptions used in pipeline weighting, enabling:
--   - Forecasting model calibration (are static probabilities accurate?)
--   - Practice-specific and deal-type-specific conversion analysis
--   - Pipeline health assessment (which stages leak most?)
--
-- Logic:
--   - Stage order: DETECTED(1) -> QUALIFIED(2) -> SHORTLISTED(3) ->
--     PROPOSAL(4) -> NEGOTIATION(5)
--   - WON leads are assumed to have passed through ALL stages (1-5)
--   - LOST leads reached stages up to their lost_at_stage
--     (NULL defaults to DETECTED only)
--   - calibrated_win_rate = WON count / total leads that reached that stage
--
-- Grain: Stage-Practice-DealType
--
-- Output columns:
--   stage_id, stage_label, practice, deal_type, won_count, reached_count,
--   calibrated_win_rate_pct, static_probability_pct, delta_pct, sample_size
--
-- Dependencies:
--   - sales_lead table (status, practice, extension, lost_at_stage, won_date)
--
-- Rollback: DROP VIEW IF EXISTS fact_historical_win_rates;
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_historical_win_rates` AS

WITH
    -- 1) Define the pipeline stage sequence with ordinals
    stage_sequence AS (
        SELECT 'DETECTED'    AS stage, 1 AS seq, 'Lead Detected'    AS stage_label, 10 AS static_pct UNION ALL
        SELECT 'QUALIFIED',           2,         'Qualified',                        25              UNION ALL
        SELECT 'SHORTLISTED',        3,         'Shortlisted',                      40              UNION ALL
        SELECT 'PROPOSAL',           4,         'Proposal Sent',                    50              UNION ALL
        SELECT 'NEGOTIATION',        5,         'In Negotiation',                   75
    ),

    -- 2) All resolved leads with their deal type and max stage reached
    resolved_leads AS (
        SELECT
            sl.uuid,
            sl.practice,
            CASE WHEN sl.extension = 1 THEN 'EXTENSION' ELSE 'NEW' END AS deal_type,
            sl.status AS outcome,
            -- Determine the highest stage ordinal this lead reached
            CASE
                -- WON leads passed through all stages
                WHEN sl.status = 'WON' THEN 5
                -- LOST leads reached up to their lost_at_stage
                WHEN sl.status = 'LOST' THEN COALESCE(
                    (SELECT ss.seq FROM stage_sequence ss WHERE ss.stage = sl.lost_at_stage),
                    1  -- Default to DETECTED if lost_at_stage is NULL
                )
            END AS max_stage_reached
        FROM sales_lead sl
        WHERE sl.status IN ('WON', 'LOST')
    ),

    -- 3) Cross-join resolved leads with stages they reached
    leads_by_stage AS (
        SELECT
            rl.uuid,
            rl.practice,
            rl.deal_type,
            rl.outcome,
            ss.stage,
            ss.seq,
            ss.stage_label,
            ss.static_pct
        FROM resolved_leads rl
        CROSS JOIN stage_sequence ss
        WHERE ss.seq <= rl.max_stage_reached
    ),

    -- 4) Aggregate: count WON vs total per stage/practice/deal_type
    stage_stats AS (
        SELECT
            lbs.stage,
            lbs.seq,
            lbs.stage_label,
            lbs.static_pct,
            lbs.practice,
            lbs.deal_type,
            SUM(CASE WHEN lbs.outcome = 'WON' THEN 1 ELSE 0 END) AS won_count,
            COUNT(*) AS reached_count
        FROM leads_by_stage lbs
        GROUP BY lbs.stage, lbs.seq, lbs.stage_label, lbs.static_pct,
                 lbs.practice, lbs.deal_type
    )

SELECT
    ss.stage AS stage_id,
    ss.stage_label,
    ss.practice,
    ss.deal_type,
    ss.won_count,
    ss.reached_count,
    ROUND(ss.won_count * 100.0 / ss.reached_count, 1) AS calibrated_win_rate_pct,
    ss.static_pct AS static_probability_pct,
    ROUND((ss.won_count * 100.0 / ss.reached_count) - ss.static_pct, 1) AS delta_pct,
    ss.reached_count AS sample_size

FROM stage_stats ss
ORDER BY ss.seq, ss.practice, ss.deal_type;
