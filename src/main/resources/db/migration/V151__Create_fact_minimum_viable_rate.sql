-- =============================================================================
-- Migration V130: Create fact_minimum_viable_rate view
--
-- Purpose:
-- - Calculate the minimum hourly billing rate per company × skill level
--   that covers all costs (salary, statutory, benefits, staff, overhead)
--   plus a target margin
-- - Provides break-even rates at actual and target (75%) utilization
-- - Includes margin columns at 15% and 20%
-- - Compares against actual average billing rate from work_full
--
-- Grain: company_id × skill_level (one row per combination)
--
-- Data Sources:
-- - user / userstatus / salary: Active billable consultants and their salaries
-- - finance_details: GL entries for statutory costs (pension, ATP, AM-bidrag)
--   and benefit costs (lunch, phone, health insurance, transport)
-- - fact_opex: Non-payroll operating expenses for overhead allocation
-- - fact_employee_monthly: Billable FTE per company for FTE-weighted allocation
-- - fact_user_utilization: Actual utilization and available hours (TTM)
-- - work_full: Actual billing rates (TTM)
--
-- GL Account Mapping (Statutory & Benefit Costs):
-- Trustworks A/S (d8894494): range 3500-3599
--   3502 = Base salary, 3510 = Pension, 3580 = ATP, 3578 = AM-bidrag
--   3570 = Phone/internet, 3585 = Lunch, 3593 = Health insurance
--   3565 = Transport (DSB brutto)
--
-- Subsidiaries (44592d3b, e4b0a2a4): range 2200-2299
--   2210 = Base salary, 2215 = Pension, 2222 = ATP, 2223 = AM-bidrag
--   2245 = Phone/internet, 2250 = Lunch, 2255 = Health insurance
--   2242 = Transport
--
-- Formula:
--   Fully Loaded Monthly Cost = avg_salary + pension + ATP + AM-bidrag
--     + benefits + staff_allocation + overhead_allocation
--   Break-even Rate = Fully Loaded Cost / Billable Hours Per Month
--   Min Rate 15% = Break-even (target) / 0.85
--   Min Rate 20% = Break-even (target) / 0.80
--
-- Bonuses: Excluded per business decision
-- Non-billable staff: Distributed evenly across all billable consultants
-- Overhead: FTE-weighted allocation of non-payroll OPEX
-- Time grain: Trailing 12 months (rolling)
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_minimum_viable_rate` AS

WITH
    -- 1) Trailing 12-month date boundaries
    ttm_range AS (
        SELECT
            DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 12 MONTH), '%Y%m') AS start_month_key,
            DATE_FORMAT(CURDATE(), '%Y%m') AS end_month_key,
            DATE_SUB(CURDATE(), INTERVAL 12 MONTH) AS start_date,
            CURDATE() AS end_date
    ),

    -- 2) Active billable consultants: current ACTIVE + CONSULTANT type, internal users only
    --    Grouped by company × skill_level
    active_consultants AS (
        SELECT
            us.companyuuid AS company_id,
            u.primary_skill_level AS skill_level,
            COUNT(DISTINCT u.uuid) AS consultant_count
        FROM user u
        INNER JOIN userstatus us ON us.useruuid = u.uuid
        WHERE u.type = 'USER'
          AND us.type = 'CONSULTANT'
          AND us.status = 'ACTIVE'
          AND us.statusdate = (
              SELECT MAX(us2.statusdate)
              FROM userstatus us2
              WHERE us2.useruuid = u.uuid
                AND us2.statusdate <= CURDATE()
          )
          AND u.primary_skill_level IS NOT NULL
          AND u.primary_skill_level BETWEEN 1 AND 5
        GROUP BY us.companyuuid, u.primary_skill_level
    ),

    -- 3) Average monthly salary per company × skill_level (current salary)
    avg_salary AS (
        SELECT
            us.companyuuid AS company_id,
            u.primary_skill_level AS skill_level,
            AVG(s.salary) AS avg_monthly_salary
        FROM user u
        INNER JOIN userstatus us ON us.useruuid = u.uuid
        INNER JOIN salary s ON s.useruuid = u.uuid
        WHERE u.type = 'USER'
          AND us.type = 'CONSULTANT'
          AND us.status = 'ACTIVE'
          AND us.statusdate = (
              SELECT MAX(us2.statusdate)
              FROM userstatus us2
              WHERE us2.useruuid = u.uuid
                AND us2.statusdate <= CURDATE()
          )
          AND s.activefrom = (
              SELECT MAX(s2.activefrom)
              FROM salary s2
              WHERE s2.useruuid = u.uuid
                AND s2.activefrom <= CURDATE()
          )
          AND u.primary_skill_level IS NOT NULL
          AND u.primary_skill_level BETWEEN 1 AND 5
        GROUP BY us.companyuuid, u.primary_skill_level
    ),

    -- 4) GL payroll costs: statutory costs (pension, ATP, AM-bidrag) per company (TTM)
    --    Account ranges differ by company
    gl_statutory_costs AS (
        SELECT
            fd.companyuuid AS company_id,
            -- Base salary total (for computing ratios)
            SUM(CASE
                WHEN fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 3502 THEN ABS(fd.amount)
                WHEN fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 2210 THEN ABS(fd.amount)
                ELSE 0
            END) AS total_salary,
            -- Pension (employer)
            SUM(CASE
                WHEN fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 3510 THEN ABS(fd.amount)
                WHEN fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 2215 THEN ABS(fd.amount)
                ELSE 0
            END) AS total_pension,
            -- ATP
            SUM(CASE
                WHEN fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 3580 THEN ABS(fd.amount)
                WHEN fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 2222 THEN ABS(fd.amount)
                ELSE 0
            END) AS total_atp,
            -- AM-bidrag
            SUM(CASE
                WHEN fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 3578 THEN ABS(fd.amount)
                WHEN fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 2223 THEN ABS(fd.amount)
                ELSE 0
            END) AS total_am_bidrag
        FROM finance_details fd
        CROSS JOIN ttm_range tr
        WHERE fd.expensedate >= tr.start_date
          AND fd.expensedate <= tr.end_date
          AND fd.expensedate IS NOT NULL
          AND fd.amount != 0
          AND (
              (fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                  AND fd.accountnumber IN (3502, 3510, 3580, 3578))
              OR
              (fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                  AND fd.accountnumber IN (2210, 2215, 2222, 2223))
          )
        GROUP BY fd.companyuuid
    ),

    -- 5) GL benefit costs per company (TTM): lunch, phone, health insurance, transport
    gl_benefit_costs AS (
        SELECT
            fd.companyuuid AS company_id,
            SUM(ABS(fd.amount)) AS total_benefit_costs
        FROM finance_details fd
        CROSS JOIN ttm_range tr
        WHERE fd.expensedate >= tr.start_date
          AND fd.expensedate <= tr.end_date
          AND fd.expensedate IS NOT NULL
          AND fd.amount != 0
          AND (
              -- Trustworks A/S benefit accounts
              (fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                  AND fd.accountnumber IN (3570, 3585, 3593, 3565))
              OR
              -- Subsidiary benefit accounts
              (fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                  AND fd.accountnumber IN (2245, 2250, 2255, 2242))
          )
        GROUP BY fd.companyuuid
    ),

    -- 6) Total active headcount per company (all types, for deriving per-person amounts)
    company_headcount AS (
        SELECT
            us.companyuuid AS company_id,
            COUNT(DISTINCT us.useruuid) AS total_active_users,
            SUM(CASE WHEN us.type = 'CONSULTANT' AND u.type = 'USER' THEN 1 ELSE 0 END) AS billable_consultant_count
        FROM userstatus us
        INNER JOIN user u ON u.uuid = us.useruuid
        WHERE us.status = 'ACTIVE'
          AND us.statusdate = (
              SELECT MAX(us2.statusdate)
              FROM userstatus us2
              WHERE us2.useruuid = us.useruuid
                AND us2.statusdate <= CURDATE()
          )
        GROUP BY us.companyuuid
    ),

    -- 7) Statutory ratios per company: pension as % of salary, ATP and AM-bidrag per person per month
    statutory_ratios AS (
        SELECT
            sc.company_id,
            -- Pension as percentage of salary
            CASE WHEN sc.total_salary > 0
                THEN sc.total_pension / sc.total_salary
                ELSE 0
            END AS pension_pct,
            -- ATP per person per month = total_atp / 12 / headcount
            CASE WHEN ch.total_active_users > 0
                THEN sc.total_atp / 12.0 / ch.total_active_users
                ELSE 0
            END AS atp_per_person_monthly,
            -- AM-bidrag per person per month = total_am / 12 / headcount
            CASE WHEN ch.total_active_users > 0
                THEN sc.total_am_bidrag / 12.0 / ch.total_active_users
                ELSE 0
            END AS am_per_person_monthly
        FROM gl_statutory_costs sc
        INNER JOIN company_headcount ch ON ch.company_id = sc.company_id
    ),

    -- 8) Benefit costs per person per month per company
    benefit_per_person AS (
        SELECT
            bc.company_id,
            CASE WHEN ch.total_active_users > 0
                THEN bc.total_benefit_costs / 12.0 / ch.total_active_users
                ELSE 0
            END AS benefit_per_person_monthly
        FROM gl_benefit_costs bc
        INNER JOIN company_headcount ch ON ch.company_id = bc.company_id
    ),

    -- 9) Staff costs: total STAFF (non-billable) salary, distributed across all billable consultants
    --    Staff only exist at Trustworks A/S currently, but formula is generic
    staff_costs AS (
        SELECT
            us.companyuuid AS company_id,
            SUM(s.salary) AS total_staff_monthly_salary
        FROM userstatus us
        INNER JOIN user u ON u.uuid = us.useruuid
        INNER JOIN salary s ON s.useruuid = u.uuid
        WHERE us.status = 'ACTIVE'
          AND us.type = 'STAFF'
          AND u.type = 'USER'
          AND us.statusdate = (
              SELECT MAX(us2.statusdate)
              FROM userstatus us2
              WHERE us2.useruuid = us.useruuid
                AND us2.statusdate <= CURDATE()
          )
          AND s.activefrom = (
              SELECT MAX(s2.activefrom)
              FROM salary s2
              WHERE s2.useruuid = u.uuid
                AND s2.activefrom <= CURDATE()
          )
        GROUP BY us.companyuuid
    ),

    -- 10) Staff allocation per billable consultant per company
    staff_allocation AS (
        SELECT
            ch.company_id,
            CASE WHEN ch.billable_consultant_count > 0
                THEN COALESCE(sc.total_staff_monthly_salary, 0) / ch.billable_consultant_count
                ELSE 0
            END AS staff_per_consultant_monthly
        FROM company_headcount ch
        LEFT JOIN staff_costs sc ON sc.company_id = ch.company_id
    ),

    -- 11) Non-payroll OPEX from fact_opex (TTM), for overhead allocation
    group_opex AS (
        SELECT
            SUM(CASE WHEN fo.is_payroll_flag = 0 THEN fo.opex_amount_dkk ELSE 0 END) AS total_non_payroll_opex
        FROM fact_opex fo
        CROSS JOIN ttm_range tr
        WHERE CAST(fo.month_key AS CHAR CHARACTER SET utf8mb4) >= tr.start_month_key
          AND CAST(fo.month_key AS CHAR CHARACTER SET utf8mb4) <= tr.end_month_key
    ),

    -- 12) FTE weights per company from fact_employee_monthly (TTM avg)
    --     Used for FTE-weighted overhead allocation across companies
    fte_weights AS (
        SELECT
            fem.company_id,
            AVG(fem.fte_billable) AS avg_billable_fte,
            AVG(fem.fte_total) AS avg_total_fte
        FROM fact_employee_monthly fem
        CROSS JOIN ttm_range tr
        WHERE CAST(fem.month_key AS CHAR CHARACTER SET utf8mb4) >= tr.start_month_key
          AND CAST(fem.month_key AS CHAR CHARACTER SET utf8mb4) <= tr.end_month_key
        GROUP BY fem.company_id
    ),

    -- 13) Total billable FTE across all companies (for weight denominator)
    total_fte AS (
        SELECT SUM(avg_billable_fte) AS group_billable_fte
        FROM fte_weights
    ),

    -- 14) Overhead allocation per company, then per consultant per month
    overhead_allocation AS (
        SELECT
            fw.company_id,
            -- Company share of group OPEX = group_opex * (company_fte / total_fte)
            -- Then per consultant per month = company_share / 12 / company_billable_consultants
            CASE WHEN tf.group_billable_fte > 0 AND ch.billable_consultant_count > 0
                THEN (go.total_non_payroll_opex * (fw.avg_billable_fte / tf.group_billable_fte))
                     / 12.0
                     / ch.billable_consultant_count
                ELSE 0
            END AS overhead_per_consultant_monthly
        FROM fte_weights fw
        CROSS JOIN group_opex go
        CROSS JOIN total_fte tf
        INNER JOIN company_headcount ch ON ch.company_id = fw.company_id
    ),

    -- 15) Utilization statistics per company × skill_level from fact_user_utilization (TTM)
    utilization_stats AS (
        SELECT
            fuu.companyuuid AS company_id,
            u.primary_skill_level AS skill_level,
            AVG(fuu.net_available_hours) AS avg_net_available_hours,
            AVG(fuu.billable_hours) AS avg_billable_hours,
            -- Utilization ratio: avg of individual month ratios
            AVG(fuu.utilization_ratio) AS avg_utilization_ratio
        FROM fact_user_utilization fuu
        INNER JOIN user u ON u.uuid = fuu.user_id
        INNER JOIN userstatus us ON us.useruuid = u.uuid
        CROSS JOIN ttm_range tr
        WHERE CAST(fuu.month_key AS CHAR CHARACTER SET utf8mb4) >= tr.start_month_key
          AND CAST(fuu.month_key AS CHAR CHARACTER SET utf8mb4) <= tr.end_month_key
          AND us.type = 'CONSULTANT'
          AND u.type = 'USER'
          AND us.statusdate = (
              SELECT MAX(us2.statusdate)
              FROM userstatus us2
              WHERE us2.useruuid = u.uuid
                AND us2.statusdate <= CURDATE()
          )
          AND u.primary_skill_level IS NOT NULL
          AND u.primary_skill_level BETWEEN 1 AND 5
        GROUP BY fuu.companyuuid, u.primary_skill_level
    ),

    -- 16) Actual billing rates per company × skill_level from work_full (TTM)
    actual_rates AS (
        SELECT
            wf.consultant_company_uuid AS company_id,
            u.primary_skill_level AS skill_level,
            AVG(wf.rate) AS avg_billing_rate
        FROM work_full wf
        INNER JOIN user u ON u.uuid = wf.useruuid
        CROSS JOIN ttm_range tr
        WHERE wf.registered >= tr.start_date
          AND wf.registered <= tr.end_date
          AND wf.rate > 0
          AND u.primary_skill_level IS NOT NULL
          AND u.primary_skill_level BETWEEN 1 AND 5
        GROUP BY wf.consultant_company_uuid, u.primary_skill_level
    )

-- 17) Final SELECT: combine all CTEs into output columns
SELECT
    -- Surrogate key
    CONCAT(ac.company_id, '-', ac.skill_level) AS rate_id,

    -- Dimensions
    ac.company_id,
    CASE ac.company_id
        WHEN 'd8894494-2fb4-4f72-9e05-e6032e6dd691' THEN 'Trustworks A/S'
        WHEN '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3' THEN 'Trustworks Technology ApS'
        WHEN 'e4b0a2a4-0963-4153-b0a2-a409637153a2' THEN 'Trustworks Cyber Security ApS'
        ELSE 'Unknown'
    END AS company_name,
    ac.skill_level,
    ac.consultant_count,

    -- Cost components
    ROUND(asl.avg_monthly_salary, 2) AS avg_monthly_salary_dkk,

    -- Statutory costs
    ROUND(COALESCE(sr.pension_pct, 0), 4) AS employer_pension_pct,
    ROUND(COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary, 2) AS employer_pension_dkk,
    ROUND(COALESCE(sr.atp_per_person_monthly, 0), 2) AS atp_per_person_dkk,
    ROUND(COALESCE(sr.am_per_person_monthly, 0), 2) AS am_bidrag_per_person_dkk,

    -- Benefits
    ROUND(COALESCE(bp.benefit_per_person_monthly, 0), 2) AS benefit_per_person_dkk,

    -- Allocations
    ROUND(COALESCE(sa.staff_per_consultant_monthly, 0), 2) AS staff_allocation_dkk,
    ROUND(COALESCE(oa.overhead_per_consultant_monthly, 0), 2) AS overhead_allocation_dkk,

    -- Total monthly cost
    ROUND(
        asl.avg_monthly_salary
        + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
        + COALESCE(sr.atp_per_person_monthly, 0)
        + COALESCE(sr.am_per_person_monthly, 0)
        + COALESCE(bp.benefit_per_person_monthly, 0)
        + COALESCE(sa.staff_per_consultant_monthly, 0)
        + COALESCE(oa.overhead_per_consultant_monthly, 0)
    , 2) AS total_monthly_cost_dkk,

    -- Utilization metrics
    ROUND(COALESCE(us2.avg_net_available_hours, 0), 2) AS avg_net_available_hours,
    ROUND(COALESCE(us2.avg_utilization_ratio, 0), 4) AS actual_utilization_ratio,
    ROUND(COALESCE(us2.avg_billable_hours, 0), 2) AS actual_billable_hours,
    0.75 AS target_utilization_ratio,
    ROUND(COALESCE(us2.avg_net_available_hours, 0) * 0.75, 2) AS target_billable_hours,

    -- Break-even rates
    -- Actual: total_cost / actual_billable_hours (NULL if 0 billable hours)
    CASE WHEN COALESCE(us2.avg_billable_hours, 0) > 0
        THEN ROUND(
            (asl.avg_monthly_salary
             + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
             + COALESCE(sr.atp_per_person_monthly, 0)
             + COALESCE(sr.am_per_person_monthly, 0)
             + COALESCE(bp.benefit_per_person_monthly, 0)
             + COALESCE(sa.staff_per_consultant_monthly, 0)
             + COALESCE(oa.overhead_per_consultant_monthly, 0))
            / us2.avg_billable_hours
        , 2)
        ELSE NULL
    END AS break_even_rate_actual,

    -- Target: total_cost / (net_available * 0.75)
    CASE WHEN COALESCE(us2.avg_net_available_hours, 0) * 0.75 > 0
        THEN ROUND(
            (asl.avg_monthly_salary
             + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
             + COALESCE(sr.atp_per_person_monthly, 0)
             + COALESCE(sr.am_per_person_monthly, 0)
             + COALESCE(bp.benefit_per_person_monthly, 0)
             + COALESCE(sa.staff_per_consultant_monthly, 0)
             + COALESCE(oa.overhead_per_consultant_monthly, 0))
            / (us2.avg_net_available_hours * 0.75)
        , 2)
        ELSE NULL
    END AS break_even_rate_target,

    -- Margin rates (based on target utilization)
    CASE WHEN COALESCE(us2.avg_net_available_hours, 0) * 0.75 > 0
        THEN ROUND(
            (asl.avg_monthly_salary
             + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
             + COALESCE(sr.atp_per_person_monthly, 0)
             + COALESCE(sr.am_per_person_monthly, 0)
             + COALESCE(bp.benefit_per_person_monthly, 0)
             + COALESCE(sa.staff_per_consultant_monthly, 0)
             + COALESCE(oa.overhead_per_consultant_monthly, 0))
            / (us2.avg_net_available_hours * 0.75)
            / 0.85
        , 2)
        ELSE NULL
    END AS min_rate_15pct_margin,

    CASE WHEN COALESCE(us2.avg_net_available_hours, 0) * 0.75 > 0
        THEN ROUND(
            (asl.avg_monthly_salary
             + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
             + COALESCE(sr.atp_per_person_monthly, 0)
             + COALESCE(sr.am_per_person_monthly, 0)
             + COALESCE(bp.benefit_per_person_monthly, 0)
             + COALESCE(sa.staff_per_consultant_monthly, 0)
             + COALESCE(oa.overhead_per_consultant_monthly, 0))
            / (us2.avg_net_available_hours * 0.75)
            / 0.80
        , 2)
        ELSE NULL
    END AS min_rate_20pct_margin,

    -- Actual billing rate comparison
    ROUND(COALESCE(ar.avg_billing_rate, 0), 2) AS avg_actual_billing_rate,

    -- Rate buffer = actual_billing_rate - break_even_target
    CASE WHEN COALESCE(us2.avg_net_available_hours, 0) * 0.75 > 0 AND COALESCE(ar.avg_billing_rate, 0) > 0
        THEN ROUND(
            ar.avg_billing_rate
            - (asl.avg_monthly_salary
               + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
               + COALESCE(sr.atp_per_person_monthly, 0)
               + COALESCE(sr.am_per_person_monthly, 0)
               + COALESCE(bp.benefit_per_person_monthly, 0)
               + COALESCE(sa.staff_per_consultant_monthly, 0)
               + COALESCE(oa.overhead_per_consultant_monthly, 0))
              / (us2.avg_net_available_hours * 0.75)
        , 2)
        ELSE NULL
    END AS rate_buffer_dkk,

    -- Metadata
    'OPERATIONAL' AS data_source

FROM active_consultants ac
INNER JOIN avg_salary asl ON asl.company_id = ac.company_id AND asl.skill_level = ac.skill_level
LEFT JOIN statutory_ratios sr ON sr.company_id = ac.company_id
LEFT JOIN benefit_per_person bp ON bp.company_id = ac.company_id
LEFT JOIN staff_allocation sa ON sa.company_id = ac.company_id
LEFT JOIN overhead_allocation oa ON oa.company_id = ac.company_id
LEFT JOIN utilization_stats us2 ON us2.company_id = ac.company_id AND us2.skill_level = ac.skill_level
LEFT JOIN actual_rates ar ON ar.company_id = ac.company_id AND ar.skill_level = ac.skill_level
ORDER BY ac.company_id, ac.skill_level;
