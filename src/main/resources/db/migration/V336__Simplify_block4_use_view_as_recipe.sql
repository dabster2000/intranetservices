-- ============================================================================
-- V336: Simplify sp_refresh_fact_tables block 4 (use the view as the recipe)
-- ============================================================================
-- Purpose
--   Eliminate the duplicated CTE between the `fact_employee_monthly` VIEW
--   and `sp_refresh_fact_tables` block 4. After V333, both carried an
--   identical 5-CTE definition (user_date_bounds -> daily_employee_data ->
--   month_boundaries / monthly_fte / joiners_leavers -> final SELECT) and
--   any future change had to be made in two places. V333 itself had to do
--   exactly that for the FTE divisor and the PROBATION removal.
--
--   Block 4 now reads directly from the view:
--       INSERT INTO fact_employee_monthly_mat (...)
--       SELECT (...) FROM fact_employee_monthly;
--   The view becomes the single source of truth; the procedure is just
--   the refresher.
--
-- Background
--   `fact_employee_monthly` returns the same shape as the materialized
--   table (minus the unused `cost_center_id` column, which the existing
--   INSERT column list already drops). All four Java services that consume
--   this data (CxoForecastService, CostAnalyticsResource, CxoFinanceService,
--   ProfitabilityProvider) read `fact_employee_monthly_mat` exclusively, so
--   only the populator path is affected by this change. The result of
--   `INSERT ... SELECT FROM fact_employee_monthly` is byte-identical to the
--   prior inline-CTE result (verified post-V333: view = mat after refresh,
--   ratios 1.0753-1.0830 across all 13 (company, practice, role) cells).
--
-- Changes
--   - Procedure `sp_refresh_fact_tables` recreated; only block 4 body
--     changes from a 5-CTE definition to `INSERT IGNORE ... SELECT FROM
--     fact_employee_monthly`. Blocks 1-3 and 5-13 are unchanged from V333.
--
-- Idempotency
--   `DROP PROCEDURE IF EXISTS` followed by `CREATE PROCEDURE`. Re-running
--   this migration is safe.
--
-- Rollback
--   Re-apply V333 on top of V336 (V333 contains the inline-CTE form of
--   block 4 and recreates the same procedure).
--
-- Verification (run after deploy)
--   1. View output equals materialized output post-refresh (the next
--      change-log driven incremental refresh, or a manual call):
--        CALL sp_refresh_fact_tables();
--        SELECT COUNT(*) FROM fact_employee_monthly_mat;
--        -- expected: same row count as before (~597 in production)
--
--        SELECT v.employee_month_id, v.fte_total v_fte, m.fte_total m_fte
--        FROM fact_employee_monthly v
--        JOIN fact_employee_monthly_mat m USING (employee_month_id)
--        WHERE ROUND(v.fte_total, 2) <> ROUND(m.fte_total, 2);
--        -- expected: 0 rows
--
--   2. Procedure body no longer carries the 5-CTE block:
--        SELECT routine_definition LIKE '%user_date_bounds AS%' AS has_cte
--        FROM information_schema.routines
--        WHERE routine_name = 'sp_refresh_fact_tables';
--        -- expected: 0
-- ============================================================================

DROP PROCEDURE IF EXISTS sp_refresh_fact_tables;

DELIMITER //

CREATE PROCEDURE sp_refresh_fact_tables()
BEGIN
    -- 1. fact_revenue_budget_mat  (was Block 2)
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

    -- 2. fact_project_financials_mat  (was Block 3)
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

    -- 3. fact_opex_mat  (was Block 4, FIXED in V224: cost_type restored)
    TRUNCATE TABLE fact_opex_mat;
    INSERT IGNORE INTO fact_opex_mat
        (opex_id, company_id, cost_center_id, expense_category_id,
         expense_subcategory_id, practice_id, sector_id,
         month_key, year, month_number,
         fiscal_year, fiscal_month_number, fiscal_month_key,
         opex_amount_dkk, invoice_count, is_payroll_flag,
         cost_type,
         data_source)
    SELECT
        opex_id, company_id, cost_center_id, expense_category_id,
        expense_subcategory_id, practice_id, sector_id,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number, fiscal_month_key,
        opex_amount_dkk, invoice_count, is_payroll_flag,
        cost_type,
        data_source
    FROM fact_opex;

    -- =========================================================================
    -- 4. fact_employee_monthly_mat
    --
    -- V336: simplified from a duplicated 5-CTE definition to a thin
    -- INSERT ... SELECT FROM the `fact_employee_monthly` view. The view is
    -- the single source of truth for FTE, headcount, and joiners/leavers
    -- math; this block only refreshes the materialized snapshot.
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
    SELECT
        employee_month_id, company_id, practice_id, role_type,
        month_key, year, month_number,
        fiscal_year, fiscal_month_number,
        headcount_start, headcount_end, average_headcount,
        billable_headcount, non_billable_headcount,
        fte_total, fte_billable, fte_non_billable,
        joiners_count, leavers_count,
        voluntary_leavers_count, data_source
    FROM fact_employee_monthly;

    -- 5. fact_tw_bonus_monthly_mat  (was Block 6)
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

    -- 6. fact_tw_bonus_annual_mat  (was Block 7)
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

    -- 7. fact_client_revenue_mat  (was Block 8)
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

    -- 8. fact_operating_cost_distribution_mat  (was Block 9)
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

    -- 9. fact_intercompany_settlement_mat  (was Block 10)
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
    -- 10. fact_minimum_viable_rate_mat  (was Block 11, OPTIMIZED -- uses _mat tables)
    --
    -- FIXED in V237: Added min_monthly_salary_dkk, max_monthly_salary_dkk
    -- FIXED in V272: utilization_stats CTE now reads from fact_user_day +
    --   work_full directly (replacing dropped fact_user_utilization_mat).
    -- =========================================================================
    TRUNCATE TABLE fact_minimum_viable_rate_mat;
    INSERT IGNORE INTO fact_minimum_viable_rate_mat
        (rate_id, company_id, company_name, career_level,
         consultant_count, avg_monthly_salary_dkk,
         min_monthly_salary_dkk, max_monthly_salary_dkk,
         employer_pension_pct, employer_pension_dkk,
         atp_per_person_dkk, am_bidrag_per_person_dkk,
         benefit_per_person_dkk, staff_allocation_dkk,
         overhead_allocation_dkk, total_monthly_cost_dkk,
         avg_net_available_hours, actual_utilization_ratio,
         actual_billable_hours, utilization_post_first_billing,
         target_utilization_ratio,
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
                AVG(s.salary) AS avg_monthly_salary,
                MIN(s.salary) AS min_monthly_salary,
                MAX(s.salary) AS max_monthly_salary
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

        -- V275: Simplified utilization from fact_user_day (aligns with V272 convention).
        -- fact_user_day already has pre-computed registered_billable_hours and
        -- net_available_hours per user per day, refreshed every 5 minutes.
        -- Replaces the previous 3-CTE approach (work_full + join).

        first_billing_date AS (
            SELECT useruuid, MIN(document_date) AS first_date
            FROM fact_user_day
            WHERE registered_billable_hours > 0
              AND consultant_type = 'CONSULTANT'
            GROUP BY useruuid
        ),

        utilization_stats AS (
            SELECT
                fud.companyuuid AS company_id,
                ccl.career_level,
                AVG(monthly_available) AS avg_net_available_hours,
                AVG(monthly_billable) AS avg_billable_hours,
                CASE WHEN SUM(monthly_available) > 0
                     THEN SUM(monthly_billable) / SUM(monthly_available)
                     ELSE 0
                END AS avg_utilization_ratio,
                CASE WHEN SUM(monthly_available_post_billing) > 0
                     THEN SUM(monthly_billable_post_billing) / SUM(monthly_available_post_billing)
                     ELSE 0
                END AS utilization_post_first_billing
            FROM (
                SELECT
                    fud.companyuuid, fud.useruuid, fud.year, fud.month,
                    SUM(fud.net_available_hours) AS monthly_available,
                    SUM(fud.registered_billable_hours) AS monthly_billable,
                    SUM(CASE WHEN fb.first_date IS NOT NULL AND fud.document_date >= fb.first_date
                             THEN fud.net_available_hours ELSE 0 END) AS monthly_available_post_billing,
                    SUM(CASE WHEN fb.first_date IS NOT NULL AND fud.document_date >= fb.first_date
                             THEN fud.registered_billable_hours ELSE 0 END) AS monthly_billable_post_billing
                FROM fact_user_day fud
                LEFT JOIN first_billing_date fb ON fb.useruuid = fud.useruuid
                CROSS JOIN ttm_range tr
                WHERE fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                  AND fud.document_date >= tr.start_date
                  AND fud.document_date <= tr.end_date
                GROUP BY fud.companyuuid, fud.useruuid, fud.year, fud.month
            ) fud
            INNER JOIN current_career_level ccl ON ccl.useruuid = fud.useruuid
            GROUP BY fud.companyuuid, ccl.career_level
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
        ROUND(asl.min_monthly_salary, 0) AS min_monthly_salary_dkk,
        ROUND(asl.max_monthly_salary, 0) AS max_monthly_salary_dkk,
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
        ROUND(COALESCE(us2.utilization_post_first_billing, 0), 4) AS utilization_post_first_billing,
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

    -- =========================================================================
    -- 11. fact_company_revenue_mat  (was Block 12, RESTORED in V263)
    -- =========================================================================
    TRUNCATE TABLE fact_company_revenue_mat;
    INSERT IGNORE INTO fact_company_revenue_mat
        (revenue_id, company_id, month_key, year, month_number,
         fiscal_year, fiscal_month_number,
         invoice_phantom_dkk, internal_dkk, credit_note_dkk,
         net_revenue_dkk)
    SELECT
        revenue_id, company_id, month_key, year, month_number,
        fiscal_year, fiscal_month_number,
        invoice_phantom_dkk, internal_dkk, credit_note_dkk,
        net_revenue_dkk
    FROM fact_company_revenue;

    -- =========================================================================
    -- 12. fact_internal_invoice_cost_mat  (was Block 13, RESTORED in V263)
    -- =========================================================================
    TRUNCATE TABLE fact_internal_invoice_cost_mat;
    INSERT IGNORE INTO fact_internal_invoice_cost_mat
        (internal_invoice_cost_id, company_id, month_key, year, month_number,
         fiscal_year, fiscal_month_number,
         internal_invoice_cost_dkk, queued_cost_dkk, created_cost_dkk,
         entry_count, data_sources)
    SELECT
        internal_invoice_cost_id, company_id, month_key, year, month_number,
        fiscal_year, fiscal_month_number,
        internal_invoice_cost_dkk, queued_cost_dkk, created_cost_dkk,
        entry_count, data_sources
    FROM fact_internal_invoice_cost;

    -- =========================================================================
    -- 13. fact_sick_day_rolling_mat  (was Block 14, NEW in V264)
    --
    -- Unlike blocks 1-12 which truncate+insert from views, this block
    -- delegates to sp_refresh_sick_day_rolling() which handles the full
    -- computation pipeline (base values -> bridging -> rolling window).
    -- The procedure internally truncates the table.
    -- =========================================================================
    CALL sp_refresh_sick_day_rolling();

END //

DELIMITER ;
