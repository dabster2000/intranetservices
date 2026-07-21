-- =============================================================================
-- V420: Drop dead legacy BI objects; remove unread practice_id from
--       fact_salary_monthly_teamroles.
--
-- Part 2 "Wave 3 trim" of the practice data-model design
-- (docs/superpowers/specs/2026-07-19-practice-data-model-design.md §4.4),
-- executed early because the objects are provably dead today.
--
-- Evidence (verified against prod twservices4 + repo, 2026-07-19):
--
--   1. fact_employee_monthly_mv / fact_project_financials_mv
--      - Legacy copies of the *_mat tables, populated only by a manually
--        created event (ev_refresh_facts_daily) that V257 disabled with the
--        note "no longer referenced by any code".
--      - Prod data confirms nothing writes them: fact_project_financials_mv
--        is EMPTY (0 rows) and fact_employee_monthly_mv is frozen (forward
--        horizon 2028-02 vs the live _mat's 2028-06, different row shape).
--      - Zero Java references, zero dependent views.
--      - If the disabled legacy event still exists and were ever re-enabled,
--        it would now fail loudly on the dropped procedure — intended.
--      - The active rebuild path (sp_refresh_fact_tables, V336) writes the
--        *_mat tables exclusively; V336's own comments state consumers read
--        _mat exclusively.
--      - refresh_fact_employee_monthly_mv (last recreated in V330 as a
--        mechanical rename) is CALLed by nothing: no migration, no procedure,
--        no Java. No refresh_fact_project_financials_mv exists.
--
--   2. fact_contract_chain
--      - View created in V230; zero Java references ever since; zero
--        dependent views or procedure references in any migration.
--
--   3. fact_salary_monthly_teamroles.practice_id
--      - Its only consumers (ProfitabilityProvider, TeamDashboardService)
--        select explicit columns (salary_sum, month_key, ...) and never read
--        practice_id; no SELECT * usage. The column is a per-user practice
--        code duplicated from fact_salary_monthly — removing it takes this
--        view off the practice re-key surface entirely.
--
-- Rollback strategy: all objects are derived (no source data is lost).
--   - fact_contract_chain: re-run the CREATE from V230.
--   - fact_salary_monthly_teamroles: re-run section 7 of V214.
--   - The _mv tables and their refresh procedure were already dead; restoring
--     them would mean re-running V330's blocks, but there is no consumer to
--     restore them for.
--
-- All statements are idempotent (IF EXISTS / CREATE OR REPLACE) — safe to
-- re-run.
-- =============================================================================

-- 1. Dead double-buffer tables + their orphaned refresh procedure
DROP PROCEDURE IF EXISTS refresh_fact_employee_monthly_mv;
DROP TABLE IF EXISTS fact_employee_monthly_mv;
DROP TABLE IF EXISTS fact_project_financials_mv;

-- 2. Never-consumed view
DROP VIEW IF EXISTS fact_contract_chain;

-- 3. fact_salary_monthly_teamroles without practice_id
--    (identical to V214 section 7 minus the fsm.practice_id column)
CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_salary_monthly_teamroles` AS

SELECT
    CONCAT(fsm.useruuid, '-', fsm.companyuuid, '-', fsm.month_key, '-', tr.teamuuid)
                                                        AS salary_team_id,

    fsm.salary_monthly_id,
    fsm.useruuid,
    fsm.companyuuid,
    tr.teamuuid,
    fsm.effective_salary,
    fsm.db_salary,
    fsm.salary_type,
    fsm.pension_own_pct,
    fsm.pension_company_pct,
    fsm.pension_deduction,
    fsm.bededag,
    fsm.supplements,
    fsm.lump_sums,
    fsm.hourly_hours,
    fsm.base_pay,
    fsm.salary_sum,
    fsm.employee_status,
    fsm.employee_type,
    fsm.is_leave_month,
    fsm.month_key,
    fsm.year,
    fsm.month_number,
    fsm.fiscal_year,
    fsm.fiscal_month_number,
    fsm.data_source

FROM fact_salary_monthly fsm

JOIN teamroles tr
    ON  tr.useruuid    = fsm.useruuid
    AND tr.membertype  = 'MEMBER'
    AND tr.startdate  <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.year, '-', fsm.month_number, '-01'), '%Y-%c-%d'))
    AND (tr.enddate IS NULL
         OR tr.enddate > STR_TO_DATE(CONCAT(fsm.year, '-', fsm.month_number, '-01'), '%Y-%c-%d'));
