-- =============================================================================
-- Migration V121: Create fact_backlog view
--
-- Purpose:
-- - Track signed backlog (contracted revenue not yet delivered)
-- - Provides monthly expected revenue from active contracts
-- - Enables backlog coverage analysis for revenue forecasting
--
-- Grain: Project-DeliveryMonth-Company
-- Source: contracts + contract_consultants + contract_project + project
-- =============================================================================

CREATE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_backlog` AS

WITH
    -- 1) Generate a calendar of months from now to 24 months ahead
    month_calendar AS (
        SELECT
            DATE_ADD(DATE(CONCAT(YEAR(CURDATE()), '-', MONTH(CURDATE()), '-01')),
                     INTERVAL n MONTH) AS delivery_month
        FROM (
                 SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
                 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
                 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
                 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
                 UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23
             ) months
    ),

    -- 2) Get active contracts with consultant allocations
    contract_consultants_active AS (
        SELECT
            c.uuid AS contract_uuid,
            c.clientuuid AS client_uuid,
            c.companyuuid AS company_uuid,
            c.contracttype AS contract_type,
            c.status AS contract_status,
            cp.projectuuid AS project_uuid,
            cc.useruuid AS consultant_uuid,
            cc.activefrom,
            cc.activeto,
            cc.rate,
            cc.hours AS allocated_hours_per_period,
            -- Calculate monthly hours (hours field is typically per-month or total)
            -- Using standard 160.33 hours/month as baseline
            CASE
                WHEN cc.hours = 0 THEN 160.33  -- Unlimited allocation = full month
                WHEN cc.hours <= 50 THEN cc.hours * 4.33  -- Weekly hours → monthly
                ELSE cc.hours  -- Already monthly
                END AS monthly_hours
        FROM contracts c
                 INNER JOIN contract_consultants cc ON cc.contractuuid = c.uuid
                 INNER JOIN contract_project cp ON cp.contractuuid = c.uuid
        WHERE c.status IN ('SIGNED', 'TIME', 'BUDGET')
          AND cc.activeto >= CURDATE()  -- Only future/current periods
          AND cc.rate > 0
    ),

    -- 3) Cross-join with calendar to get monthly backlog
    backlog_by_month AS (
        SELECT
            cca.contract_uuid,
            cca.client_uuid,
            cca.company_uuid,
            cca.contract_type,
            cca.contract_status,
            cca.project_uuid,
            cca.consultant_uuid,
            mc.delivery_month,
            YEAR(mc.delivery_month) AS year_val,
            MONTH(mc.delivery_month) AS month_val,
            cca.rate,
            cca.monthly_hours,
            -- Calculate backlog revenue for this month
            -- Pro-rate for partial months at start/end
            CASE
                -- Full month within period
                WHEN cca.activefrom <= mc.delivery_month
                    AND cca.activeto >= LAST_DAY(mc.delivery_month)
                    THEN cca.rate * cca.monthly_hours
                -- Partial start month
                WHEN YEAR(cca.activefrom) = YEAR(mc.delivery_month)
                    AND MONTH(cca.activefrom) = MONTH(mc.delivery_month)
                    THEN cca.rate * cca.monthly_hours *
                         (DATEDIFF(LAST_DAY(mc.delivery_month), cca.activefrom) + 1) /
                         DAY(LAST_DAY(mc.delivery_month))
                -- Partial end month
                WHEN YEAR(cca.activeto) = YEAR(mc.delivery_month)
                    AND MONTH(cca.activeto) = MONTH(mc.delivery_month)
                    THEN cca.rate * cca.monthly_hours *
                         DAY(cca.activeto) / DAY(LAST_DAY(mc.delivery_month))
                ELSE 0
                END AS backlog_revenue_dkk
        FROM contract_consultants_active cca
                 CROSS JOIN month_calendar mc
        WHERE mc.delivery_month >= DATE(CONCAT(YEAR(cca.activefrom), '-', MONTH(cca.activefrom), '-01'))
          AND mc.delivery_month <= LAST_DAY(cca.activeto)
    ),

    -- 4) Aggregate to project-month-company grain
    backlog_aggregated AS (
        SELECT
            bm.project_uuid,
            bm.client_uuid,
            COALESCE(bm.company_uuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_uuid,
            bm.contract_type,
            bm.year_val,
            bm.month_val,
            SUM(bm.backlog_revenue_dkk) AS backlog_revenue_dkk,
            COUNT(DISTINCT bm.consultant_uuid) AS consultant_count,
            COUNT(DISTINCT bm.contract_uuid) AS contract_count
        FROM backlog_by_month bm
        WHERE bm.backlog_revenue_dkk > 0
        GROUP BY bm.project_uuid, bm.client_uuid, bm.company_uuid, bm.contract_type,
                 bm.year_val, bm.month_val
    ),

    -- 5) Get dominant service line per project (from historical work or consultants)
    project_service_line AS (
        SELECT
            p.uuid AS project_uuid,
            COALESCE(
                    (SELECT u.primaryskilltype
                     FROM contract_project cp2
                              JOIN contract_consultants cc2 ON cp2.contractuuid = cc2.contractuuid
                              JOIN user u ON cc2.useruuid = u.uuid
                     WHERE cp2.projectuuid = p.uuid
                       AND u.primaryskilltype IS NOT NULL
                     GROUP BY u.primaryskilltype
                     ORDER BY COUNT(*) DESC, SUM(cc2.hours) DESC
                     LIMIT 1),
                    'UD'
            ) AS service_line_id
        FROM project p
    )

SELECT
    -- Surrogate key
    CONCAT(
            ba.project_uuid, '-',
            ba.company_uuid, '-',
            LPAD(ba.year_val, 4, '0'),
            LPAD(ba.month_val, 2, '0')
    ) AS backlog_id,

    -- Dimension keys
    ba.project_uuid AS project_id,
    ba.client_uuid AS client_id,
    ba.company_uuid AS company_id,
    COALESCE(psl.service_line_id, 'UD') AS service_line_id,
    COALESCE(c.segment, 'OTHER') AS sector_id,
    COALESCE(ba.contract_type, 'PERIOD') AS contract_type_id,

    -- Time dimensions
    CONCAT(LPAD(ba.year_val, 4, '0'), LPAD(ba.month_val, 2, '0')) AS delivery_month_key,
    ba.year_val AS year,
    ba.month_val AS month_number,

    -- Metrics
    ba.backlog_revenue_dkk,
    ba.consultant_count,
    ba.contract_count,

    -- Status (all backlog from active contracts)
    'ACTIVE' AS project_status,

    -- Data source
    'CONTRACTS' AS data_source

FROM backlog_aggregated ba
         LEFT JOIN client c ON ba.client_uuid = c.uuid
         LEFT JOIN project_service_line psl ON ba.project_uuid = psl.project_uuid
ORDER BY ba.year_val, ba.month_val, ba.project_uuid;

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