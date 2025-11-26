-- =============================================================================
-- Migration V121: Create fact_backlog view
--
-- Purpose:
-- - Track signed backlog (contracted revenue not yet delivered)
-- - Provides monthly expected revenue from active contracts
-- - Enables backlog coverage analysis for revenue forecasting
--
-- Grain: Contract-DeliveryMonth-Company
-- Source: bi_budget_per_day (pre-calculated with availability adjustments)
--
-- CHANGE LOG:
-- 2025-11-26: Refactored to use bi_budget_per_day as data source
--   - FIXED: Double-counting bug from contract_project JOIN (5x inflation)
--   - FIXED: Now excludes weekends (only Mon-Fri counted)
--   - FIXED: Now includes availability adjustment (~28% reduction for vacation, parental leave, etc.)
--   - CHANGED: Grain from Project-Month to Contract-Month (no project-level double-counting)
-- =============================================================================

CREATE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_backlog` AS

WITH
    -- 1) Aggregate bi_budget_per_day to contract-month level
    -- bi_budget_per_day already has:
    --   - Weekend exclusion (only Mon-Fri)
    --   - Availability adjustment (vacation, parental leave, sick leave)
    --   - Daily granularity with pre-calculated hours and rates
    backlog_by_contract_month AS (
        SELECT
            b.contractuuid AS contract_uuid,
            b.clientuuid AS client_uuid,
            COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_uuid,
            b.year AS year_val,
            b.month AS month_val,
            -- Backlog revenue = SUM(adjusted hours × rate) for all days in month
            SUM(b.budgetHours * b.rate) AS backlog_revenue_dkk,
            -- Also track raw hours for transparency
            SUM(b.budgetHours) AS adjusted_hours,
            SUM(b.budgetHoursWithNoAvailabilityAdjustment) AS raw_hours,
            COUNT(DISTINCT b.useruuid) AS consultant_count
        FROM bi_budget_per_day b
        WHERE b.budgetHours > 0
          -- Only include future months (current month and beyond)
          AND b.document_date >= DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01'))
        GROUP BY b.contractuuid, b.clientuuid, b.companyuuid, b.year, b.month
    ),

    -- 2) Get contract metadata (contract type, status)
    contract_metadata AS (
        SELECT
            c.uuid AS contract_uuid,
            c.contracttype AS contract_type,
            c.status AS contract_status
        FROM contracts c
        WHERE c.status IN ('SIGNED', 'TIME', 'BUDGET')
    ),

    -- 3) Get dominant service line per contract (from assigned consultants)
    contract_service_line AS (
        SELECT
            cc.contractuuid AS contract_uuid,
            COALESCE(
                (SELECT u.primaryskilltype
                 FROM contract_consultants cc2
                 JOIN user u ON cc2.useruuid = u.uuid
                 WHERE cc2.contractuuid = cc.contractuuid
                   AND u.primaryskilltype IS NOT NULL
                 GROUP BY u.primaryskilltype
                 ORDER BY COUNT(*) DESC, SUM(cc2.hours) DESC
                 LIMIT 1),
                'UD'
            ) AS service_line_id
        FROM contract_consultants cc
        GROUP BY cc.contractuuid
    )

SELECT
    -- Surrogate key (contract-company-month)
    CONCAT(
        bcm.contract_uuid, '-',
        bcm.company_uuid, '-',
        LPAD(bcm.year_val, 4, '0'),
        LPAD(bcm.month_val, 2, '0')
    ) AS backlog_id,

    -- Dimension keys
    -- Note: Using contract_uuid as project_id for backward compatibility
    -- The downstream code aggregates by month anyway, so the entity type doesn't matter
    bcm.contract_uuid AS project_id,
    bcm.client_uuid AS client_id,
    bcm.company_uuid AS company_id,
    COALESCE(csl.service_line_id, 'UD') AS service_line_id,
    COALESCE(cl.segment, 'OTHER') AS sector_id,
    COALESCE(cm.contract_type, 'PERIOD') AS contract_type_id,

    -- Time dimensions
    CONCAT(LPAD(bcm.year_val, 4, '0'), LPAD(bcm.month_val, 2, '0')) AS delivery_month_key,
    bcm.year_val AS year,
    bcm.month_val AS month_number,

    -- Metrics
    bcm.backlog_revenue_dkk,
    bcm.consultant_count,
    1 AS contract_count,  -- Each row is one contract

    -- Status (all backlog from active contracts)
    'ACTIVE' AS project_status,

    -- Data source (changed from CONTRACTS to BI_BUDGET to indicate new source)
    'BI_BUDGET' AS data_source

FROM backlog_by_contract_month bcm
    LEFT JOIN contract_metadata cm ON bcm.contract_uuid = cm.contract_uuid
    LEFT JOIN client cl ON bcm.client_uuid = cl.uuid
    LEFT JOIN contract_service_line csl ON bcm.contract_uuid = csl.contract_uuid
WHERE bcm.backlog_revenue_dkk > 0
ORDER BY bcm.year_val, bcm.month_val, bcm.contract_uuid;

-- =============================================================================
--
-- Purpose:
-- - Track sales pipeline (potential revenue from opportunities)
-- - Provides expected revenue by opportunity stage and probability
-- - Enables pipeline coverage and forecasting analysis
--
-- Grain: Opportunity-ExpectedRevenueMonth
-- Source: sales_lead + sales_lead_consultant + client
-- =============================================================================

CREATE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_pipeline` AS

WITH

    -- 1) Map sales lead stages to normalized categories and probabilities
    stage_mapping AS (
        SELECT 'DETECTED' AS status, 'LEAD' AS stage_category, 10 AS probability_pct UNION ALL
        SELECT 'QUALIFIED', 'QUALIFIED', 25 UNION ALL
        SELECT 'SHORTLISTED', 'QUALIFIED', 40 UNION ALL
        SELECT 'PROPOSAL', 'PROPOSAL', 50 UNION ALL
        SELECT 'NEGOTIATION', 'NEGOTIATION', 75 UNION ALL
        SELECT 'WON', 'WON', 100 UNION ALL
        SELECT 'LOST', 'LOST', 0
    ),

    -- 2) Generate delivery months based on closedate + period
    month_calendar AS (
        SELECT
            DATE_ADD(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                     INTERVAL n MONTH) AS expected_month
        FROM (
                 SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
                 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
                 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
                 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
                 UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23
             ) months
    ),

    -- 3) Get active pipeline opportunities with consultant count
    pipeline_opportunities AS (
        SELECT
            sl.uuid AS opportunity_uuid,
            sl.clientuuid AS client_uuid,
            sl.leadmanager AS lead_manager_uuid,
            sl.description,
            sl.rate,
            sl.closedate,
            sl.period AS period_months,
            sl.allocation,
            sl.competencies AS service_line_code,
            sl.status,
            sl.extension,
            sl.created,
            -- Count assigned consultants (minimum 1 for calculation)
            GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid),
                    1
                        )) AS consultant_count,
            -- Get lead manager's company (for company attribution)
            COALESCE(
                    (SELECT us.companyuuid
                     FROM userstatus us
                     WHERE us.useruuid = sl.leadmanager
                       AND us.statusdate <= COALESCE(sl.closedate, CURDATE())
                     ORDER BY us.statusdate DESC
                     LIMIT 1),
                    'd8894494-2fb4-4f72-9e05-e6032e6dd691'
            ) AS company_uuid
        FROM sales_lead sl
        WHERE sl.status NOT IN ('WON', 'LOST')
          AND sl.rate > 0
          AND sl.period > 0
          AND (sl.closedate IS NULL OR sl.closedate >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH))
    ),

    -- 4) Expand opportunities across expected delivery months
    pipeline_by_month AS (
        SELECT
            po.opportunity_uuid,
            po.client_uuid,
            po.company_uuid,
            po.service_line_code,
            po.status,
            po.rate,
            po.period_months,
            po.allocation,
            po.consultant_count,
            po.description,
            po.extension,
            po.created,
            mc.expected_month,
            YEAR(mc.expected_month) AS year_val,
            MONTH(mc.expected_month) AS month_val,
            -- Calculate monthly revenue expectation
            -- Formula: rate × hours_per_month × allocation% × consultants
            -- Assuming rate is hourly and 160.33 hours/month standard
            (po.rate * 160.33 * (po.allocation / 100.0) * po.consultant_count) AS monthly_expected_revenue_dkk
        FROM pipeline_opportunities po
                 CROSS JOIN month_calendar mc
        WHERE mc.expected_month >= DATE(CONCAT(YEAR(COALESCE(po.closedate, CURDATE())), '-',
                                               MONTH(COALESCE(po.closedate, CURDATE())), '-01'))
          AND mc.expected_month < DATE_ADD(
                DATE(CONCAT(YEAR(COALESCE(po.closedate, CURDATE())), '-',
                            MONTH(COALESCE(po.closedate, CURDATE())), '-01')),
                INTERVAL po.period_months MONTH
                                  )
    ),

    -- 5) Map competencies to service line IDs
    service_line_mapping AS (
        SELECT 'PM' AS code, 'PM' AS service_line_id UNION ALL
        SELECT 'BA', 'BA' UNION ALL
        SELECT 'LA', 'SA' UNION ALL  -- Lead Architect → Solution Architect
        SELECT 'DEV', 'DEV' UNION ALL
        SELECT 'SA', 'SA' UNION ALL
        SELECT 'OPS', 'OPS' UNION ALL
        SELECT 'CYB', 'CYB'
    )

SELECT
    -- Surrogate key
    CONCAT(
            pbm.opportunity_uuid, '-',
            LPAD(pbm.year_val, 4, '0'),
            LPAD(pbm.month_val, 2, '0')
    ) AS pipeline_id,

    -- Dimension keys
    pbm.opportunity_uuid AS opportunity_id,
    pbm.client_uuid AS client_id,
    pbm.company_uuid AS company_id,
    COALESCE(slm.service_line_id, 'UD') AS service_line_id,
    COALESCE(c.segment, 'OTHER') AS sector_id,
    'PERIOD' AS contract_type_id,  -- Pipeline doesn't have contract type yet

    -- Stage dimensions
    pbm.status AS stage_id,
    COALESCE(sm.stage_category, 'LEAD') AS stage_category,

    -- Time dimensions
    CONCAT(LPAD(pbm.year_val, 4, '0'), LPAD(pbm.month_val, 2, '0')) AS expected_revenue_month_key,
    pbm.year_val AS year,
    pbm.month_val AS month_number,

    -- Metrics
    pbm.monthly_expected_revenue_dkk AS expected_revenue_dkk,
    COALESCE(sm.probability_pct, 10) AS probability_pct,

    -- Weighted pipeline (expected × probability)
    pbm.monthly_expected_revenue_dkk * (COALESCE(sm.probability_pct, 10) / 100.0) AS weighted_pipeline_dkk,

    -- Additional context
    pbm.consultant_count,
    pbm.allocation AS allocation_pct,
    pbm.rate AS hourly_rate,
    pbm.period_months,
    pbm.extension AS is_extension,

    -- Data source
    'CRM' AS data_source

FROM pipeline_by_month pbm
         LEFT JOIN client c ON pbm.client_uuid = c.uuid
         LEFT JOIN stage_mapping sm ON pbm.status = sm.status
         LEFT JOIN service_line_mapping slm ON pbm.service_line_code = slm.code
ORDER BY pbm.year_val, pbm.month_val, pbm.opportunity_uuid;