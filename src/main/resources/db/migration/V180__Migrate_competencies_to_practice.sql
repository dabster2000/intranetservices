-- =============================================================================
-- Migration V180: Migrate sales_lead.competencies to practice (PrimarySkillType)
--
-- Purpose:
-- - Unify the sales lead competency concept with the user practice concept
-- - Map old ConsultantCompetencies values to PrimarySkillType values
-- - Rename column from 'competencies' to 'practice'
--
-- Mapping (ConsultantCompetencies -> PrimarySkillType):
--   BA  -> BA   (Business Analyst)
--   PM  -> PM   (Project Manager)
--   LA  -> SA   (Solution Architect)
--   DEV -> DEV  (Developer)
--   OPS -> DEV  (merged into Developer)
--   SA  -> DEV  (merged into Developer)
--   CYB -> CYB  (Cyber Security)
--
-- CHANGE LOG:
-- 2026-02-15: Initial migration
-- =============================================================================

-- Step 1: Map old competency values to new practice values
UPDATE sales_lead SET competencies = CASE
    WHEN competencies = 'BA'  THEN 'BA'
    WHEN competencies = 'PM'  THEN 'PM'
    WHEN competencies = 'LA'  THEN 'SA'
    WHEN competencies = 'DEV' THEN 'DEV'
    WHEN competencies = 'OPS' THEN 'DEV'
    WHEN competencies = 'SA'  THEN 'DEV'
    WHEN competencies = 'CYB' THEN 'CYB'
    ELSE competencies
END
WHERE competencies IS NOT NULL;

-- Step 2: Rename column from 'competencies' to 'practice'
ALTER TABLE sales_lead CHANGE COLUMN competencies practice VARCHAR(36);

-- Step 3: Recreate fact_pipeline view to reference new column name
DROP VIEW IF EXISTS fact_pipeline;

CREATE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_pipeline` AS

WITH

    stage_mapping AS (
        SELECT 'DETECTED' AS status, 'LEAD' AS stage_category, 10 AS probability_pct UNION ALL
        SELECT 'QUALIFIED', 'QUALIFIED', 25 UNION ALL
        SELECT 'SHORTLISTED', 'QUALIFIED', 40 UNION ALL
        SELECT 'PROPOSAL', 'PROPOSAL', 50 UNION ALL
        SELECT 'NEGOTIATION', 'NEGOTIATION', 75 UNION ALL
        SELECT 'WON', 'WON', 100 UNION ALL
        SELECT 'LOST', 'LOST', 0
    ),

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
            sl.practice AS service_line_code,
            sl.status,
            sl.extension,
            sl.created,
            GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid),
                    1
                        )) AS consultant_count,
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

    -- Service line mapping simplified: practice values now match PrimarySkillType directly
    service_line_mapping AS (
        SELECT 'PM' AS code, 'PM' AS service_line_id UNION ALL
        SELECT 'BA', 'BA' UNION ALL
        SELECT 'SA', 'SA' UNION ALL
        SELECT 'DEV', 'DEV' UNION ALL
        SELECT 'CYB', 'CYB' UNION ALL
        SELECT 'JK', 'JK' UNION ALL
        SELECT 'UD', 'UD'
    )

SELECT
    CONCAT(
            pbm.opportunity_uuid, '-',
            LPAD(pbm.year_val, 4, '0'),
            LPAD(pbm.month_val, 2, '0')
    ) AS pipeline_id,

    pbm.opportunity_uuid AS opportunity_id,
    pbm.client_uuid AS client_id,
    pbm.company_uuid AS company_id,
    COALESCE(slm.service_line_id, 'UD') AS service_line_id,
    COALESCE(c.segment, 'OTHER') AS sector_id,
    'PERIOD' AS contract_type_id,

    pbm.status AS stage_id,
    COALESCE(sm.stage_category, 'LEAD') AS stage_category,

    CONCAT(LPAD(pbm.year_val, 4, '0'), LPAD(pbm.month_val, 2, '0')) AS expected_revenue_month_key,
    pbm.year_val AS year,
    pbm.month_val AS month_number,

    pbm.monthly_expected_revenue_dkk AS expected_revenue_dkk,
    COALESCE(sm.probability_pct, 10) AS probability_pct,

    pbm.monthly_expected_revenue_dkk * (COALESCE(sm.probability_pct, 10) / 100.0) AS weighted_pipeline_dkk,

    pbm.consultant_count,
    pbm.allocation AS allocation_pct,
    pbm.rate AS hourly_rate,
    pbm.period_months,
    pbm.extension AS is_extension,

    'CRM' AS data_source

FROM pipeline_by_month pbm
         LEFT JOIN client c ON pbm.client_uuid = c.uuid
         LEFT JOIN stage_mapping sm ON pbm.status = sm.status
         LEFT JOIN service_line_mapping slm ON pbm.service_line_code = slm.code
ORDER BY pbm.year_val, pbm.month_val, pbm.opportunity_uuid;
