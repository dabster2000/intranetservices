-- =============================================================================
-- Migration V221: Optimize sp_refresh_fact_tables for slow blocks 5 and 11
--
-- Purpose:
--   The nightly sp_refresh_fact_tables() reads from slow VIEWS for blocks 5
--   (fact_employee_monthly) and 11 (fact_minimum_viable_rate). This causes
--   30+ minute nightly refresh times. This migration replaces those two blocks
--   with optimized inline SQL:
--
--   Block 5: fact_employee_monthly_mat
--     Problem: Correlated subqueries in the joiners_leavers CTE scan the
--              957K-row bi_data_per_day table per row (O(n^2)).
--     Fix: Pre-compute MIN/MAX(document_date) per user in a single pass
--          (user_date_bounds CTE) and join instead of correlating.
--
--   Block 11: fact_minimum_viable_rate_mat
--     Problem: References fact_opex, fact_employee_monthly, and
--              fact_user_utilization as VIEWS, causing MariaDB to expand the
--              entire slow view chain (nested views of nested views).
--     Fix: Substitute the already-refreshed _mat tables (blocks 1, 4, 5 run
--          before block 11, so the _mat data is fresh).
--
-- All other blocks (1-4, 6-10) remain unchanged.
-- =============================================================================

DROP PROCEDURE IF EXISTS sp_refresh_fact_tables;

DELIMITER //

CREATE PROCEDURE sp_refresh_fact_tables()
BEGIN
    -- 1. fact_user_utilization_mat
    TRUNCATE TABLE fact_user_utilization_mat;
    INSERT IGNORE INTO fact_user_utilization_mat
        (utilization_id, user_id, companyuuid, practice_id, client_id,
         sector_id, contract_type_id, month_key, year, month_number,
         gross_available_hours, unavailable_hours, vacation_hours,
         sick_hours, maternity_leave_hours, non_payd_leave_hours,
         paid_leave_hours, net_available_hours, billable_hours,
         utilization_ratio)
    SELECT
        utilization_id, user_id, companyuuid, practice_id, client_id,
        sector_id, contract_type_id, month_key, year, month_number,
        gross_available_hours, unavailable_hours, vacation_hours,
        sick_hours, maternity_leave_hours, non_payd_leave_hours,
        paid_leave_hours, net_available_hours, billable_hours,
        utilization_ratio
    FROM fact_user_utilization;

    -- 2. fact_revenue_budget_mat
    TRUNCATE TABLE fact_revenue_budget_mat;
    INSERT IGNORE INTO fact_revenue_budget_mat
        (revenue_budget_id, company_id, service_line_id, sector_id,
         contract_type_id, month_key, year, month_number,
         fiscal_year, fiscal_month_number, fiscal_month_key,
         budget_scenario, budget_revenue_dkk, budget_hours,
         contract_count, consultant_count)
    SELECT
        revenue_budget_id, company_id, service_line_id, sector_id,
        contract_type_id, month_key, year, month_number,
        fiscal_year, fiscal_month_number, fiscal_month_key,
        budget_scenario, budget_revenue_dkk, budget_hours,
        contract_count, consultant_count
    FROM fact_revenue_budget;

    -- 3. fact_project_financials_mat
    TRUNCATE TABLE fact_project_financials_mat;
    INSERT IGNORE INTO fact_project_financials_mat
        (project_financial_id, project_id, client_id, companyuuid,
         sector_id, service_line_id, contract_type_id,
         month_key, year, month_number,
         recognized_revenue_dkk, employee_salary_cost_dkk,
         external_consultant_cost_dkk, project_expense_cost_dkk,
         direct_delivery_cost_dkk, total_hours, consultant_count,
         data_source)
    SELECT
        project_financial_id, project_id, client_id, companyuuid,
        sector_id, service_line_id, contract_type_id,
        month_key, year, month_number,
        recognized_revenue_dkk, employee_salary_cost_dkk,
        external_consultant_cost_dkk, project_expense_cost_dkk,
        direct_delivery_cost_dkk, total_hours, consultant_count,
        data_source
    FROM fact_project_financials;

    -- 4. fact_opex_mat
    TRUNCATE TABLE fact_opex_mat;
    INSERT IGNORE INTO fact_opex_mat
        (opex_id, company_id, cost_center_id, expense_category_id,
         expense_subcategory_id, practice_id, sector_id,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number, fiscal_month_key,
         opex_amount_dkk, invoice_count, is_payroll_flag,
         data_source)
    SELECT
        opex_id, company_id, cost_center_id, expense_category_id,
        expense_subcategory_id, practice_id, sector_id,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number, fiscal_month_key,
        opex_amount_dkk, invoice_count, is_payroll_flag,
        data_source
    FROM fact_opex;

    -- =========================================================================
    -- 5. fact_employee_monthly_mat  (OPTIMIZED — no correlated subqueries)
    --
    -- The view (fact_employee_monthly, V124) has O(n^2) correlated subqueries
    -- in the joiners_leavers CTE that scan bi_data_per_day for MIN/MAX dates
    -- per user per row. We pre-compute these bounds once and join.
    -- =========================================================================
    TRUNCATE TABLE fact_employee_monthly_mat;
    INSERT IGNORE INTO fact_employee_monthly_mat
        (employee_month_id, company_id, practice_id, role_type,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number,
         headcount_start, headcount_end, average_headcount,
         billable_headcount, non_billable_headcount,
         fte_total, fte_billable, fte_non_billable,
         joiners_count, leavers_count,
         voluntary_leavers_count, data_source)
    WITH
        user_date_bounds AS (
            SELECT
                useruuid,
                MIN(document_date) AS first_date,
                MAX(document_date) AS last_date
            FROM bi_data_per_day
            WHERE gross_available_hours > 0
              AND status_type != 'TERMINATED'
              AND consultant_type IN ('CONSULTANT', 'STAFF')
            GROUP BY useruuid
        ),

        daily_employee_data AS (
            SELECT
                b.companyuuid,
                b.document_date,
                b.year AS year_val,
                b.month AS month_val,
                b.useruuid,
                b.gross_available_hours,
                b.consultant_type,
                b.status_type,
                COALESCE(u.practice, 'UD') AS practice_id,
                CASE
                    WHEN b.consultant_type = 'CONSULTANT'
                        AND b.status_type IN ('ACTIVE', 'PROBATION')
                        THEN 'BILLABLE'
                    ELSE 'NON_BILLABLE'
                END AS role_type,
                COALESCE(b.gross_available_hours, 0) / 8.0 AS daily_fte,
                udb.first_date,
                udb.last_date
            FROM bi_data_per_day b
            INNER JOIN user u ON b.useruuid = u.uuid
            INNER JOIN user_date_bounds udb ON udb.useruuid = b.useruuid
            WHERE b.gross_available_hours > 0
              AND b.status_type != 'TERMINATED'
              AND b.consultant_type IN ('CONSULTANT', 'STAFF')
        ),

        month_boundaries AS (
            SELECT
                companyuuid,
                year_val,
                month_val,
                practice_id,
                role_type,
                COUNT(DISTINCT CASE
                    WHEN DAY(document_date) = 1
                        THEN useruuid
                END) AS headcount_start,
                COUNT(DISTINCT CASE
                    WHEN document_date = LAST_DAY(document_date)
                        THEN useruuid
                END) AS headcount_end
            FROM daily_employee_data
            GROUP BY companyuuid, year_val, month_val, practice_id, role_type
        ),

        monthly_fte AS (
            SELECT
                companyuuid,
                year_val,
                month_val,
                practice_id,
                role_type,
                COUNT(DISTINCT useruuid) AS distinct_employees,
                SUM(daily_fte) / COUNT(DISTINCT document_date) AS avg_monthly_fte,
                COUNT(DISTINCT CASE WHEN role_type = 'BILLABLE' THEN useruuid END) AS billable_headcount,
                COUNT(DISTINCT CASE WHEN role_type = 'NON_BILLABLE' THEN useruuid END) AS non_billable_headcount,
                SUM(CASE WHEN role_type = 'BILLABLE' THEN daily_fte ELSE 0 END) / COUNT(DISTINCT document_date) AS fte_billable,
                SUM(CASE WHEN role_type = 'NON_BILLABLE' THEN daily_fte ELSE 0 END) / COUNT(DISTINCT document_date) AS fte_non_billable
            FROM daily_employee_data
            GROUP BY companyuuid, year_val, month_val, practice_id, role_type
        ),

        joiners_leavers AS (
            SELECT
                companyuuid,
                year_val,
                month_val,
                practice_id,
                role_type,
                COUNT(DISTINCT CASE
                    WHEN document_date = first_date THEN useruuid
                END) AS joiners_count,
                COUNT(DISTINCT CASE
                    WHEN document_date = last_date AND document_date < CURDATE()
                        THEN useruuid
                END) AS leavers_count
            FROM daily_employee_data
            GROUP BY companyuuid, year_val, month_val, practice_id, role_type
        )

    SELECT
        CONCAT(mf.companyuuid, '-', mf.practice_id, '-', mf.role_type, '-',
               CONCAT(LPAD(mf.year_val, 4, '0'), LPAD(mf.month_val, 2, '0'))
        ) AS employee_month_id,
        mf.companyuuid AS company_id,
        mf.practice_id,
        mf.role_type,
        CONCAT(LPAD(mf.year_val, 4, '0'), LPAD(mf.month_val, 2, '0')) AS month_key,
        mf.year_val AS year,
        mf.month_val AS month_number,
        CASE WHEN mf.month_val >= 7 THEN mf.year_val ELSE mf.year_val - 1 END AS fiscal_year,
        CASE WHEN mf.month_val >= 7 THEN mf.month_val - 6 ELSE mf.month_val + 6 END AS fiscal_month_number,
        COALESCE(mb.headcount_start, 0) AS headcount_start,
        COALESCE(mb.headcount_end, 0) AS headcount_end,
        ROUND((COALESCE(mb.headcount_start, 0) + COALESCE(mb.headcount_end, 0)) / 2.0, 2) AS average_headcount,
        COALESCE(mf.billable_headcount, 0) AS billable_headcount,
        COALESCE(mf.non_billable_headcount, 0) AS non_billable_headcount,
        ROUND(COALESCE(mf.avg_monthly_fte, 0), 2) AS fte_total,
        ROUND(COALESCE(mf.fte_billable, 0), 2) AS fte_billable,
        ROUND(COALESCE(mf.fte_non_billable, 0), 2) AS fte_non_billable,
        COALESCE(jl.joiners_count, 0) AS joiners_count,
        COALESCE(jl.leavers_count, 0) AS leavers_count,
        0 AS voluntary_leavers_count,
        'HR_SYSTEM' AS data_source
    FROM monthly_fte mf
    LEFT JOIN month_boundaries mb
        ON mf.companyuuid = mb.companyuuid
        AND mf.year_val = mb.year_val
        AND mf.month_val = mb.month_val
        AND mf.practice_id = mb.practice_id
        AND mf.role_type = mb.role_type
    LEFT JOIN joiners_leavers jl
        ON mf.companyuuid = jl.companyuuid
        AND mf.year_val = jl.year_val
        AND mf.month_val = jl.month_val
        AND mf.practice_id = jl.practice_id
        AND mf.role_type = jl.role_type;

    -- 6. fact_tw_bonus_monthly_mat
    TRUNCATE TABLE fact_tw_bonus_monthly_mat;
    INSERT IGNORE INTO fact_tw_bonus_monthly_mat
        (useruuid, companyuuid, year, month,
         days_in_month, total_days, eligible_days,
         eligible_share, avg_salary, weighted_avg_salary,
         fiscal_year)
    SELECT
        useruuid, companyuuid, year, month,
        days_in_month, total_days, eligible_days,
        eligible_share, avg_salary, weighted_avg_salary,
        fiscal_year
    FROM fact_tw_bonus_monthly;

    -- 7. fact_tw_bonus_annual_mat
    TRUNCATE TABLE fact_tw_bonus_annual_mat;
    INSERT IGNORE INTO fact_tw_bonus_annual_mat
        (useruuid, companyuuid, fiscal_year,
         weight_sum, eligible_months, total_eligible_days,
         avg_monthly_salary)
    SELECT
        useruuid, companyuuid, fiscal_year,
        weight_sum, eligible_months, total_eligible_days,
        avg_monthly_salary
    FROM fact_tw_bonus_annual;

    -- 8. fact_client_revenue_mat
    TRUNCATE TABLE fact_client_revenue_mat;
    INSERT IGNORE INTO fact_client_revenue_mat
        (client_revenue_id, client_id, company_id,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number,
         invoice_phantom_dkk, internal_dkk,
         credit_note_dkk, net_revenue_dkk)
    SELECT
        client_revenue_id, client_id, company_id,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number,
        invoice_phantom_dkk, internal_dkk,
        credit_note_dkk, net_revenue_dkk
    FROM fact_client_revenue;

    -- 9. fact_operating_cost_distribution_mat
    --    Refreshed before fact_intercompany_settlement_mat (dependency order)
    TRUNCATE TABLE fact_operating_cost_distribution_mat;
    INSERT IGNORE INTO fact_operating_cost_distribution_mat
        (distribution_id, origin_company, payer_company,
         account_code, category_uuid, shared, salary,
         year_val, month_val, month_key,
         fiscal_year, fiscal_month_number,
         origin_gl_amount, payer_ratio, payer_consultants,
         allocated_amount, intercompany_owe)
    SELECT
        distribution_id, origin_company, payer_company,
        account_code, category_uuid, shared, salary,
        year_val, month_val, month_key,
        fiscal_year, fiscal_month_number,
        origin_gl_amount, payer_ratio, payer_consultants,
        allocated_amount, intercompany_owe
    FROM fact_operating_cost_distribution;

    -- 10. fact_intercompany_settlement_mat
    TRUNCATE TABLE fact_intercompany_settlement_mat;
    INSERT IGNORE INTO fact_intercompany_settlement_mat
        (settlement_id, payer_company, receiver_company,
         year_val, month_val, month_key,
         fiscal_year, fiscal_month_number,
         expected_amount, actual_amount, settlement_gap,
         invoice_count, settlement_status)
    SELECT
        CONCAT(payer_company, '-', receiver_company, '-', month_key),
        payer_company, receiver_company,
        year_val, month_val, month_key,
        fiscal_year, fiscal_month_number,
        expected_amount, actual_amount, settlement_gap,
        invoice_count, settlement_status
    FROM fact_intercompany_settlement;

    -- =========================================================================
    -- 11. fact_minimum_viable_rate_mat  (OPTIMIZED — uses _mat tables)
    --
    -- The view (fact_minimum_viable_rate, V207) references fact_opex,
    -- fact_employee_monthly, and fact_user_utilization as VIEWS. MariaDB
    -- expands the entire nested view chain, creating a massive execution plan.
    --
    -- Since blocks 1 (utilization), 4 (opex), and 5 (employee_monthly) are
    -- already refreshed above, we substitute the fast _mat tables:
    --   fact_opex            -> fact_opex_mat
    --   fact_employee_monthly -> fact_employee_monthly_mat
    --   fact_user_utilization -> fact_user_utilization_mat
    -- =========================================================================
    TRUNCATE TABLE fact_minimum_viable_rate_mat;
    INSERT IGNORE INTO fact_minimum_viable_rate_mat
        (rate_id, company_id, company_name, career_level,
         consultant_count, avg_monthly_salary_dkk,
         employer_pension_pct, employer_pension_dkk,
         atp_per_person_dkk, am_bidrag_per_person_dkk,
         benefit_per_person_dkk, staff_allocation_dkk,
         overhead_allocation_dkk, total_monthly_cost_dkk,
         avg_net_available_hours, actual_utilization_ratio,
         actual_billable_hours, target_utilization_ratio,
         target_billable_hours, break_even_rate_actual,
         break_even_rate_target, min_rate_15pct_margin,
         min_rate_20pct_margin, avg_actual_billing_rate,
         rate_buffer_dkk, data_source,
         break_even_utilization_pct, break_even_utilization_15pct,
         break_even_utilization_20pct)
    WITH
        ttm_range AS (
            SELECT
                DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 12 MONTH), '%Y%m') AS start_month_key,
                DATE_FORMAT(CURDATE(), '%Y%m') AS end_month_key,
                DATE_SUB(CURDATE(), INTERVAL 12 MONTH) AS start_date,
                CURDATE() AS end_date
        ),

        current_career_level AS (
            SELECT
                ucl.useruuid,
                ucl.career_track,
                ucl.career_level
            FROM user_career_level ucl
            INNER JOIN (
                SELECT useruuid, MAX(active_from) AS max_active_from
                FROM user_career_level
                WHERE active_from <= CURDATE()
                GROUP BY useruuid
            ) latest ON ucl.useruuid = latest.useruuid AND ucl.active_from = latest.max_active_from
        ),

        active_consultants AS (
            SELECT
                us.companyuuid AS company_id,
                ccl.career_level,
                COUNT(DISTINCT u.uuid) AS consultant_count
            FROM user u
            INNER JOIN userstatus us ON us.useruuid = u.uuid
            INNER JOIN current_career_level ccl ON ccl.useruuid = u.uuid
            WHERE u.type = 'USER'
              AND us.type = 'CONSULTANT'
              AND us.status = 'ACTIVE'
              AND us.statusdate = (
                  SELECT MAX(us2.statusdate)
                  FROM userstatus us2
                  WHERE us2.useruuid = u.uuid
                    AND us2.statusdate <= CURDATE()
              )
            GROUP BY us.companyuuid, ccl.career_level
        ),

        avg_salary AS (
            SELECT
                us.companyuuid AS company_id,
                ccl.career_level,
                AVG(s.salary) AS avg_monthly_salary
            FROM user u
            INNER JOIN userstatus us ON us.useruuid = u.uuid
            INNER JOIN salary s ON s.useruuid = u.uuid
            INNER JOIN current_career_level ccl ON ccl.useruuid = u.uuid
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
            GROUP BY us.companyuuid, ccl.career_level
        ),

        gl_statutory_costs AS (
            SELECT
                fd.companyuuid AS company_id,
                SUM(CASE
                    WHEN fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 3502 THEN ABS(fd.amount)
                    WHEN fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 2210 THEN ABS(fd.amount)
                    ELSE 0
                END) AS total_salary,
                SUM(CASE
                    WHEN fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 3510 THEN ABS(fd.amount)
                    WHEN fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 2215 THEN ABS(fd.amount)
                    ELSE 0
                END) AS total_pension,
                SUM(CASE
                    WHEN fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 3580 THEN ABS(fd.amount)
                    WHEN fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AND fd.accountnumber = 2222 THEN ABS(fd.amount)
                    ELSE 0
                END) AS total_atp,
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
                  (fd.companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                      AND fd.accountnumber IN (3570, 3585, 3593, 3565))
                  OR
                  (fd.companyuuid != 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                      AND fd.accountnumber IN (2245, 2250, 2255, 2242))
              )
            GROUP BY fd.companyuuid
        ),

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

        statutory_ratios AS (
            SELECT
                sc.company_id,
                CASE WHEN sc.total_salary > 0
                    THEN sc.total_pension / sc.total_salary
                    ELSE 0
                END AS pension_pct,
                CASE WHEN ch.total_active_users > 0
                    THEN sc.total_atp / 12.0 / ch.total_active_users
                    ELSE 0
                END AS atp_per_person_monthly,
                CASE WHEN ch.total_active_users > 0
                    THEN sc.total_am_bidrag / 12.0 / ch.total_active_users
                    ELSE 0
                END AS am_per_person_monthly
            FROM gl_statutory_costs sc
            INNER JOIN company_headcount ch ON ch.company_id = sc.company_id
        ),

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

        -- OPTIMIZED: reads from fact_opex_mat instead of fact_opex view
        group_opex AS (
            SELECT
                SUM(CASE WHEN fo.is_payroll_flag = 0 THEN fo.opex_amount_dkk ELSE 0 END) AS total_non_payroll_opex
            FROM fact_opex_mat fo
            CROSS JOIN ttm_range tr
            WHERE CAST(fo.month_key AS CHAR CHARACTER SET utf8mb4) >= tr.start_month_key
              AND CAST(fo.month_key AS CHAR CHARACTER SET utf8mb4) <= tr.end_month_key
        ),

        -- OPTIMIZED: reads from fact_employee_monthly_mat instead of fact_employee_monthly view
        fte_weights AS (
            SELECT
                fem.company_id,
                AVG(fem.fte_billable) AS avg_billable_fte,
                AVG(fem.fte_total) AS avg_total_fte
            FROM fact_employee_monthly_mat fem
            CROSS JOIN ttm_range tr
            WHERE CAST(fem.month_key AS CHAR CHARACTER SET utf8mb4) >= tr.start_month_key
              AND CAST(fem.month_key AS CHAR CHARACTER SET utf8mb4) <= tr.end_month_key
            GROUP BY fem.company_id
        ),

        total_fte AS (
            SELECT SUM(avg_billable_fte) AS group_billable_fte
            FROM fte_weights
        ),

        overhead_allocation AS (
            SELECT
                fw.company_id,
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

        -- OPTIMIZED: reads from fact_user_utilization_mat instead of fact_user_utilization view
        utilization_stats AS (
            SELECT
                fuu.companyuuid AS company_id,
                ccl.career_level,
                AVG(fuu.net_available_hours) AS avg_net_available_hours,
                AVG(fuu.billable_hours) AS avg_billable_hours,
                AVG(fuu.utilization_ratio) AS avg_utilization_ratio
            FROM fact_user_utilization_mat fuu
            INNER JOIN user u ON u.uuid = fuu.user_id
            INNER JOIN userstatus us ON us.useruuid = u.uuid
            INNER JOIN current_career_level ccl ON ccl.useruuid = u.uuid
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
            GROUP BY fuu.companyuuid, ccl.career_level
        ),

        actual_rates AS (
            SELECT
                wf.consultant_company_uuid AS company_id,
                ccl.career_level,
                AVG(wf.rate) AS avg_billing_rate
            FROM work_full wf
            INNER JOIN user u ON u.uuid = wf.useruuid
            INNER JOIN current_career_level ccl ON ccl.useruuid = u.uuid
            CROSS JOIN ttm_range tr
            WHERE wf.registered >= tr.start_date
              AND wf.registered <= tr.end_date
              AND wf.rate > 0
            GROUP BY wf.consultant_company_uuid, ccl.career_level
        )

    SELECT
        CONCAT(ac.company_id, '-', ac.career_level) AS rate_id,
        ac.company_id,
        CASE ac.company_id
            WHEN 'd8894494-2fb4-4f72-9e05-e6032e6dd691' THEN 'Trustworks A/S'
            WHEN '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3' THEN 'Trustworks Technology ApS'
            WHEN 'e4b0a2a4-0963-4153-b0a2-a409637153a2' THEN 'Trustworks Cyber Security ApS'
            ELSE 'Unknown'
        END AS company_name,
        ac.career_level,
        ac.consultant_count,
        ROUND(asl.avg_monthly_salary, 2) AS avg_monthly_salary_dkk,
        ROUND(COALESCE(sr.pension_pct, 0), 4) AS employer_pension_pct,
        ROUND(COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary, 2) AS employer_pension_dkk,
        ROUND(COALESCE(sr.atp_per_person_monthly, 0), 2) AS atp_per_person_dkk,
        ROUND(COALESCE(sr.am_per_person_monthly, 0), 2) AS am_bidrag_per_person_dkk,
        ROUND(COALESCE(bp.benefit_per_person_monthly, 0), 2) AS benefit_per_person_dkk,
        ROUND(COALESCE(sa.staff_per_consultant_monthly, 0), 2) AS staff_allocation_dkk,
        ROUND(COALESCE(oa.overhead_per_consultant_monthly, 0), 2) AS overhead_allocation_dkk,
        ROUND(
            asl.avg_monthly_salary
            + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
            + COALESCE(sr.atp_per_person_monthly, 0)
            + COALESCE(sr.am_per_person_monthly, 0)
            + COALESCE(bp.benefit_per_person_monthly, 0)
            + COALESCE(sa.staff_per_consultant_monthly, 0)
            + COALESCE(oa.overhead_per_consultant_monthly, 0)
        , 2) AS total_monthly_cost_dkk,
        ROUND(COALESCE(us2.avg_net_available_hours, 0), 2) AS avg_net_available_hours,
        ROUND(COALESCE(us2.avg_utilization_ratio, 0), 4) AS actual_utilization_ratio,
        ROUND(COALESCE(us2.avg_billable_hours, 0), 2) AS actual_billable_hours,
        0.75 AS target_utilization_ratio,
        ROUND(COALESCE(us2.avg_net_available_hours, 0) * 0.75, 2) AS target_billable_hours,
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
        ROUND(COALESCE(ar.avg_billing_rate, 0), 2) AS avg_actual_billing_rate,
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
        'OPERATIONAL' AS data_source,
        CASE WHEN COALESCE(us2.avg_net_available_hours, 0) > 0 AND COALESCE(ar.avg_billing_rate, 0) > 0
            THEN ROUND(
                (asl.avg_monthly_salary
                 + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
                 + COALESCE(sr.atp_per_person_monthly, 0)
                 + COALESCE(sr.am_per_person_monthly, 0)
                 + COALESCE(bp.benefit_per_person_monthly, 0)
                 + COALESCE(sa.staff_per_consultant_monthly, 0)
                 + COALESCE(oa.overhead_per_consultant_monthly, 0))
                / (us2.avg_net_available_hours * ar.avg_billing_rate)
            , 4)
            ELSE NULL
        END AS break_even_utilization_pct,
        CASE WHEN COALESCE(us2.avg_net_available_hours, 0) > 0 AND COALESCE(ar.avg_billing_rate, 0) > 0
            THEN ROUND(
                (asl.avg_monthly_salary
                 + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
                 + COALESCE(sr.atp_per_person_monthly, 0)
                 + COALESCE(sr.am_per_person_monthly, 0)
                 + COALESCE(bp.benefit_per_person_monthly, 0)
                 + COALESCE(sa.staff_per_consultant_monthly, 0)
                 + COALESCE(oa.overhead_per_consultant_monthly, 0))
                / (0.85 * us2.avg_net_available_hours * ar.avg_billing_rate)
            , 4)
            ELSE NULL
        END AS break_even_utilization_15pct,
        CASE WHEN COALESCE(us2.avg_net_available_hours, 0) > 0 AND COALESCE(ar.avg_billing_rate, 0) > 0
            THEN ROUND(
                (asl.avg_monthly_salary
                 + COALESCE(sr.pension_pct, 0) * asl.avg_monthly_salary
                 + COALESCE(sr.atp_per_person_monthly, 0)
                 + COALESCE(sr.am_per_person_monthly, 0)
                 + COALESCE(bp.benefit_per_person_monthly, 0)
                 + COALESCE(sa.staff_per_consultant_monthly, 0)
                 + COALESCE(oa.overhead_per_consultant_monthly, 0))
                / (0.80 * us2.avg_net_available_hours * ar.avg_billing_rate)
            , 4)
            ELSE NULL
        END AS break_even_utilization_20pct

    FROM active_consultants ac
    INNER JOIN avg_salary asl ON asl.company_id = ac.company_id AND asl.career_level = ac.career_level
    LEFT JOIN statutory_ratios sr ON sr.company_id = ac.company_id
    LEFT JOIN benefit_per_person bp ON bp.company_id = ac.company_id
    LEFT JOIN staff_allocation sa ON sa.company_id = ac.company_id
    LEFT JOIN overhead_allocation oa ON oa.company_id = ac.company_id
    LEFT JOIN utilization_stats us2 ON us2.company_id = ac.company_id AND us2.career_level = ac.career_level
    LEFT JOIN actual_rates ar ON ar.company_id = ac.company_id AND ar.career_level = ac.career_level
    ORDER BY ac.company_id, ac.career_level;

END //

DELIMITER ;
