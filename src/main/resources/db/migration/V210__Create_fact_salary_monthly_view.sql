-- =============================================================================
-- Migration V210: Create fact_salary_monthly view (+ companion teamroles view)
--
-- Purpose:
--   Monthly per-employee salary calculation view, exposing the DB-calculable
--   components of the Danish AM-grundlag (Arbejdsmarkedsbidragsgrundlag) for
--   payroll reconciliation, cost reporting, and CXO dashboards.
--
-- Primary view: fact_salary_monthly
-- Companion view: fact_salary_monthly_teamroles
--
-- ─── Grain ───────────────────────────────────────────────────────────────────
--   Primary:   useruuid × companyuuid × month_key  (YYYYMM)
--   Companion: useruuid × companyuuid × month_key × teamuuid
--
--   The companion view is a simple join of the primary view to teamroles
--   (membertype = 'MEMBER' only, active during the month). This allows
--   team-filtered queries without fan-out salary duplication in the primary view.
--
-- ─── Salary eligibility (inclusion rules) ─────────────────────────────────
--   Included:  ACTIVE, MATERNITY_LEAVE, PAID_LEAVE, NON_PAY_LEAVE
--   Excluded:  TERMINATED (full month), PREBOARDING (full month)
--   NON_PAY_LEAVE: included with FULL (unprorated) salary; flagged via
--                  is_leave_month = 1. Proration requires working-day counts
--                  not available in the DB — see docs §3.
--
-- ─── Salary components (DB-calculable) ────────────────────────────────────
--   NORMAL employees:
--     salary_sum = effective_salary × (1 − pension_rate + 0.0045)
--                + supplements + lump_sums
--
--   HOURLY employees:
--     salary_sum = (hourly_hours × effective_salary) × 1.0045
--                − pension (if any)
--
--   Where pension_rate = (pension_own + pension_company) / 100
--
--   Bededagstillaeg (0.45%) is always applied to all employees (docs §1.4).
--   The prayer_day column in salary is historical and NOT used here.
--
-- ─── Owner salary overrides ───────────────────────────────────────────────
--   Three owners have actual salaries of 150,000 DKK/month. The DB stores
--   100,000. The override is applied via CASE in the latest_salary CTE.
--   UUIDs (hardcoded per docs/finalized/salaries/business-rules-and-math.md §1.1):
--     8fa7f75a-57bf-4c6f-8db7-7e16067c1bcd
--     7948c5e8-162c-4053-b905-0f59a21d7746
--     ca0e1027-061f-49e7-b66a-a487c815f5a0
--
-- ─── Hourly work ──────────────────────────────────────────────────────────
--   Hourly hours come from work.workduration WHERE:
--     taskuuid = 'a7314f77-5e03-4f56-8b1c-0562e601f22f'  (Hourly wages task)
--     paid_out >= first_of_month AND paid_out < first_of_next_month
--   Critical: use paid_out (payroll date), NOT registered date.
--
-- ─── Payroll-only components (EXCLUDED from view) ─────────────────────────
--   ATP, free phone, free internet, free lunch taxable value, feriepenge,
--   fritvalg, SH-dag, and retroactive payroll corrections are NOT stored in
--   the DB and are intentionally excluded. They create a residual of ~1.9%
--   between this view and the ERP Lon AM-grundlag total.
--
-- ─── Supplement convention ────────────────────────────────────────────────
--   salary_supplement.with_pension is not applied to supplement amounts in
--   this view (docs state supplements are added directly to AM-grundlag;
--   pension-qualifying supplements affect the pension calculation upstream
--   in the payroll system, not in the DB-based formula).
--
-- ─── Fiscal year convention ───────────────────────────────────────────────
--   Trustworks financial year: July 1 → June 30.
--   fiscal_year:  month >= 7 → calendar year; month < 7 → calendar year − 1.
--   fiscal_month_number: July=1, August=2, ..., December=6, January=7, ..., June=12.
--   Example: January 2026 → fiscal_year=2025, fiscal_month_number=7.
--
-- ─── CTE chain ────────────────────────────────────────────────────────────
--   1. month_spine          — all (useruuid, companyuuid, year, month) from
--                             userstatus, excluding full-month TERMINATED /
--                             PREBOARDING.
--   2. latest_status        — temporally resolved status per (useruuid, month_end).
--   3. eligible_months      — filters out TERMINATED and PREBOARDING; flags
--                             NON_PAY_LEAVE.
--   4. latest_salary        — temporally resolved salary with owner overrides.
--   5. latest_pension       — temporally resolved pension rates (COALESCE 0).
--   6. active_supplements   — SUM of supplements active during the month.
--   7. monthly_lump_sums    — SUM of lump sums paid in the month.
--   8. hourly_hours         — SUM of work hours paid out during the month
--                             (HOURLY employees only).
--
-- ─── Data Sources ─────────────────────────────────────────────────────────
--   userstatus, salary, user_pension, salary_supplement,
--   salary_lump_sum, work, user
--
-- ─── Affected Java entities / repositories ────────────────────────────────
--   No existing Java entity maps to this view (new view).
--   Future consumers should use native queries or a new @Entity mapping.
--
-- ─── Validation queries ───────────────────────────────────────────────────
--   -- Row count for Jan 2026 (expect ~83):
--   SELECT COUNT(*) FROM fact_salary_monthly WHERE month_key = '202601';
--
--   -- Salary sum total for Jan 2026 (expect ~4,858,195 ±5%):
--   SELECT SUM(salary_sum) FROM fact_salary_monthly WHERE month_key = '202601';
--
--   -- Mette Borch: salary_sum ≈ 54,406.50
--   SELECT effective_salary, salary_sum
--   FROM fact_salary_monthly
--   WHERE month_key = '202601'
--     AND useruuid = (SELECT uuid FROM user WHERE lastname = 'Borch' AND firstname = 'Mette');
--
--   -- Owner override example: effective_salary = 150000, salary_sum ≈ 132,675
--   SELECT db_salary, effective_salary, salary_sum
--   FROM fact_salary_monthly
--   WHERE month_key = '202601'
--     AND useruuid = '8fa7f75a-57bf-4c6f-8db7-7e16067c1bcd';
--
--   -- Manmeet Singh: lump_sums = 60000
--   SELECT lump_sums, salary_sum
--   FROM fact_salary_monthly
--   WHERE month_key = '202601'
--     AND useruuid = (SELECT uuid FROM user WHERE lastname = 'Singh' AND firstname = 'Manmeet');
--
--   -- TERMINATED/PREBOARDING exclusion:
--   SELECT COUNT(*) FROM fact_salary_monthly f
--   WHERE f.month_key = '202601'
--     AND f.employee_status IN ('TERMINATED','PREBOARDING');
--   -- Expected: 0
--
--   -- Fiscal year check for Jan 2026:
--   SELECT fiscal_year, fiscal_month_number
--   FROM fact_salary_monthly WHERE month_key = '202601' LIMIT 1;
--   -- Expected: fiscal_year=2025, fiscal_month_number=7
--
-- ─── Rollback strategy ────────────────────────────────────────────────────
--   DROP VIEW IF EXISTS fact_salary_monthly_teamroles;
--   DROP VIEW IF EXISTS fact_salary_monthly;
--   These views are read-only and carry no foreign key dependencies.
--
-- ─── Index recommendations ────────────────────────────────────────────────
--   No indexes needed — these are views. The following existing indexes are
--   leveraged: salary(useruuid, activefrom), userstatus(useruuid, statusdate),
--   user_pension(useruuid, active_date), work(taskuuid, paid_out, useruuid),
--   salary_lump_sum(useruuid, month), salary_supplement(useruuid).
--
-- Idempotent: CREATE OR REPLACE VIEW — safe to re-run.
-- =============================================================================

-- =============================================================================
-- PRIMARY VIEW: fact_salary_monthly
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_salary_monthly` AS

WITH

-- ---------------------------------------------------------------------------
-- 1) month_spine: one row per (useruuid, companyuuid, year, month_num)
--    derived from all userstatus rows. Each status row represents a period
--    from its statusdate until the next status row for the same user.
--
--    We collect every distinct (useruuid, companyuuid, year, month) where at
--    least one userstatus row was effective during that calendar month. This
--    is the basis for the temporal resolution in the following CTEs.
--
--    We use a generated months table approach: find min and max dates from
--    userstatus, then cross-join with a sequence to enumerate months. Since
--    MariaDB lacks generate_series, we build a bounded inline number sequence
--    using a UNION ALL of small value sets. The date range covered (2015-2035)
--    requires at most 240 months, so a 256-row sequence is sufficient.
--
--    Alternative (used here for simplicity and MariaDB compatibility):
--    Pull distinct (useruuid, YEAR(statusdate), MONTH(statusdate)) from
--    userstatus directly — this gives us all months where a status change
--    occurred, but misses stable months with no change. To cover stable months,
--    we instead pull the set of all distinct (useruuid, year, month) from
--    fact_user_day (bi_data_per_day) which covers every day for every user.
--    However, fact_user_day is a base table we should not assume exists here.
--
--    Correct approach for this view: build from userstatus directly.
--    For each userstatus record, the status is valid from statusdate until
--    the next statusdate for the same user. We enumerate each month within
--    that range. To avoid a full cross-join explosion, we enumerate months
--    by finding (useruuid, companyuuid, year, month) combinations present in
--    salary records + userstatus, then resolve the status as of the last day
--    of each month.
--
--    Practical simplification: collect all (useruuid, companyuuid) pairs from
--    userstatus, then for each pair, enumerate months from the user's first
--    non-PREBOARDING status to today. We bound the range to the last 5 years
--    + 1 future year for performance.
--
--    IMPLEMENTATION: We join userstatus to a number sequence to enumerate
--    months from each status row's statusdate forward until the next status
--    row. This is clean and does not over-generate rows.
-- ---------------------------------------------------------------------------
nums AS (
    -- 256-row sequence 0..255 for month offset generation
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
    UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
    UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
    UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
    UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23
    UNION ALL SELECT 24 UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27
    UNION ALL SELECT 28 UNION ALL SELECT 29 UNION ALL SELECT 30 UNION ALL SELECT 31
    UNION ALL SELECT 32 UNION ALL SELECT 33 UNION ALL SELECT 34 UNION ALL SELECT 35
    UNION ALL SELECT 36 UNION ALL SELECT 37 UNION ALL SELECT 38 UNION ALL SELECT 39
    UNION ALL SELECT 40 UNION ALL SELECT 41 UNION ALL SELECT 42 UNION ALL SELECT 43
    UNION ALL SELECT 44 UNION ALL SELECT 45 UNION ALL SELECT 46 UNION ALL SELECT 47
    UNION ALL SELECT 48 UNION ALL SELECT 49 UNION ALL SELECT 50 UNION ALL SELECT 51
    UNION ALL SELECT 52 UNION ALL SELECT 53 UNION ALL SELECT 54 UNION ALL SELECT 55
    UNION ALL SELECT 56 UNION ALL SELECT 57 UNION ALL SELECT 58 UNION ALL SELECT 59
    UNION ALL SELECT 60 UNION ALL SELECT 61 UNION ALL SELECT 62 UNION ALL SELECT 63
    UNION ALL SELECT 64 UNION ALL SELECT 65 UNION ALL SELECT 66 UNION ALL SELECT 67
    UNION ALL SELECT 68 UNION ALL SELECT 69 UNION ALL SELECT 70 UNION ALL SELECT 71
    UNION ALL SELECT 72 UNION ALL SELECT 73 UNION ALL SELECT 74 UNION ALL SELECT 75
    UNION ALL SELECT 76 UNION ALL SELECT 77 UNION ALL SELECT 78 UNION ALL SELECT 79
    UNION ALL SELECT 80 UNION ALL SELECT 81 UNION ALL SELECT 82 UNION ALL SELECT 83
    UNION ALL SELECT 84 UNION ALL SELECT 85 UNION ALL SELECT 86 UNION ALL SELECT 87
    UNION ALL SELECT 88 UNION ALL SELECT 89 UNION ALL SELECT 90 UNION ALL SELECT 91
    UNION ALL SELECT 92 UNION ALL SELECT 93 UNION ALL SELECT 94 UNION ALL SELECT 95
    UNION ALL SELECT 96 UNION ALL SELECT 97 UNION ALL SELECT 98 UNION ALL SELECT 99
    UNION ALL SELECT 100 UNION ALL SELECT 101 UNION ALL SELECT 102 UNION ALL SELECT 103
    UNION ALL SELECT 104 UNION ALL SELECT 105 UNION ALL SELECT 106 UNION ALL SELECT 107
    UNION ALL SELECT 108 UNION ALL SELECT 109 UNION ALL SELECT 110 UNION ALL SELECT 111
    UNION ALL SELECT 112 UNION ALL SELECT 113 UNION ALL SELECT 114 UNION ALL SELECT 115
    UNION ALL SELECT 116 UNION ALL SELECT 117 UNION ALL SELECT 118 UNION ALL SELECT 119
    UNION ALL SELECT 120 UNION ALL SELECT 121 UNION ALL SELECT 122 UNION ALL SELECT 123
    UNION ALL SELECT 124 UNION ALL SELECT 125 UNION ALL SELECT 126 UNION ALL SELECT 127
    UNION ALL SELECT 128 UNION ALL SELECT 129 UNION ALL SELECT 130 UNION ALL SELECT 131
    UNION ALL SELECT 132 UNION ALL SELECT 133 UNION ALL SELECT 134 UNION ALL SELECT 135
    UNION ALL SELECT 136 UNION ALL SELECT 137 UNION ALL SELECT 138 UNION ALL SELECT 139
    UNION ALL SELECT 140 UNION ALL SELECT 141 UNION ALL SELECT 142 UNION ALL SELECT 143
    UNION ALL SELECT 144 UNION ALL SELECT 145 UNION ALL SELECT 146 UNION ALL SELECT 147
    UNION ALL SELECT 148 UNION ALL SELECT 149 UNION ALL SELECT 150 UNION ALL SELECT 151
    UNION ALL SELECT 152 UNION ALL SELECT 153 UNION ALL SELECT 154 UNION ALL SELECT 155
    UNION ALL SELECT 156 UNION ALL SELECT 157 UNION ALL SELECT 158 UNION ALL SELECT 159
    UNION ALL SELECT 160 UNION ALL SELECT 161 UNION ALL SELECT 162 UNION ALL SELECT 163
    UNION ALL SELECT 164 UNION ALL SELECT 165 UNION ALL SELECT 166 UNION ALL SELECT 167
    UNION ALL SELECT 168 UNION ALL SELECT 169 UNION ALL SELECT 170 UNION ALL SELECT 171
    UNION ALL SELECT 172 UNION ALL SELECT 173 UNION ALL SELECT 174 UNION ALL SELECT 175
    UNION ALL SELECT 176 UNION ALL SELECT 177 UNION ALL SELECT 178 UNION ALL SELECT 179
    UNION ALL SELECT 180 UNION ALL SELECT 181 UNION ALL SELECT 182 UNION ALL SELECT 183
    UNION ALL SELECT 184 UNION ALL SELECT 185 UNION ALL SELECT 186 UNION ALL SELECT 187
    UNION ALL SELECT 188 UNION ALL SELECT 189 UNION ALL SELECT 190 UNION ALL SELECT 191
    UNION ALL SELECT 192 UNION ALL SELECT 193 UNION ALL SELECT 194 UNION ALL SELECT 195
    UNION ALL SELECT 196 UNION ALL SELECT 197 UNION ALL SELECT 198 UNION ALL SELECT 199
    UNION ALL SELECT 200 UNION ALL SELECT 201 UNION ALL SELECT 202 UNION ALL SELECT 203
    UNION ALL SELECT 204 UNION ALL SELECT 205 UNION ALL SELECT 206 UNION ALL SELECT 207
    UNION ALL SELECT 208 UNION ALL SELECT 209 UNION ALL SELECT 210 UNION ALL SELECT 211
    UNION ALL SELECT 212 UNION ALL SELECT 213 UNION ALL SELECT 214 UNION ALL SELECT 215
    UNION ALL SELECT 216 UNION ALL SELECT 217 UNION ALL SELECT 218 UNION ALL SELECT 219
    UNION ALL SELECT 220 UNION ALL SELECT 221 UNION ALL SELECT 222 UNION ALL SELECT 223
    UNION ALL SELECT 224 UNION ALL SELECT 225 UNION ALL SELECT 226 UNION ALL SELECT 227
    UNION ALL SELECT 228 UNION ALL SELECT 229 UNION ALL SELECT 230 UNION ALL SELECT 231
    UNION ALL SELECT 232 UNION ALL SELECT 233 UNION ALL SELECT 234 UNION ALL SELECT 235
    UNION ALL SELECT 236 UNION ALL SELECT 237 UNION ALL SELECT 238 UNION ALL SELECT 239
    UNION ALL SELECT 240 UNION ALL SELECT 241 UNION ALL SELECT 242 UNION ALL SELECT 243
    UNION ALL SELECT 244 UNION ALL SELECT 245 UNION ALL SELECT 246 UNION ALL SELECT 247
    UNION ALL SELECT 248 UNION ALL SELECT 249 UNION ALL SELECT 250 UNION ALL SELECT 251
    UNION ALL SELECT 252 UNION ALL SELECT 253 UNION ALL SELECT 254 UNION ALL SELECT 255
),

-- ---------------------------------------------------------------------------
-- Build a spine of (useruuid, companyuuid, year, month_num) by finding the
-- start of each status period and the start of the next status period for
-- the same user. We then enumerate every month in [status_start, next_status).
-- The spine is bounded to months <= current month (no future payroll).
-- ---------------------------------------------------------------------------
status_periods AS (
    SELECT
        us.useruuid,
        us.companyuuid,
        us.statusdate                                       AS period_start,
        COALESCE(
            (SELECT MIN(us2.statusdate)
             FROM userstatus us2
             WHERE us2.useruuid = us.useruuid
               AND us2.statusdate > us.statusdate),
            DATE_ADD(CURDATE(), INTERVAL 1 MONTH)          -- open-ended: current+1
        )                                                   AS period_end
    FROM userstatus us
),

month_spine AS (
    SELECT DISTINCT
        sp.useruuid,
        sp.companyuuid,
        YEAR(DATE_ADD(sp.period_start, INTERVAL n.n MONTH))  AS year_num,
        MONTH(DATE_ADD(sp.period_start, INTERVAL n.n MONTH)) AS month_num
    FROM status_periods sp
    JOIN nums n
        ON DATE_ADD(sp.period_start, INTERVAL n.n MONTH) < sp.period_end
       AND DATE_ADD(sp.period_start, INTERVAL n.n MONTH) <= LAST_DAY(CURDATE())
       -- Bound to last ~10 years for performance
       AND DATE_ADD(sp.period_start, INTERVAL n.n MONTH) >= DATE_SUB(CURDATE(), INTERVAL 120 MONTH)
),

-- ---------------------------------------------------------------------------
-- 2) latest_status: resolve the most recent userstatus record for each
--    (useruuid, month_end). Uses MAX + self-join pattern (no correlated
--    subqueries) consistent with V201/V209 style.
-- ---------------------------------------------------------------------------
status_max_date AS (
    SELECT
        ms.useruuid,
        ms.companyuuid,
        ms.year_num,
        ms.month_num,
        LAST_DAY(STR_TO_DATE(CONCAT(ms.year_num, '-', ms.month_num, '-01'), '%Y-%c-%d'))
            AS month_end,
        STR_TO_DATE(CONCAT(ms.year_num, '-', ms.month_num, '-01'), '%Y-%c-%d')
            AS month_start,
        MAX(us.statusdate) AS max_statusdate
    FROM month_spine ms
    JOIN userstatus us
        ON  us.useruuid  = ms.useruuid
        AND us.statusdate <= LAST_DAY(STR_TO_DATE(CONCAT(ms.year_num, '-', ms.month_num, '-01'), '%Y-%c-%d'))
    GROUP BY ms.useruuid, ms.companyuuid, ms.year_num, ms.month_num
),

latest_status AS (
    SELECT
        smd.useruuid,
        smd.companyuuid,
        smd.year_num,
        smd.month_num,
        smd.month_end,
        smd.month_start,
        us.status           AS employee_status,
        us.type             AS employee_type,
        us.companyuuid      AS status_companyuuid
    FROM status_max_date smd
    JOIN userstatus us
        ON  us.useruuid   = smd.useruuid
        AND us.statusdate = smd.max_statusdate
),

-- ---------------------------------------------------------------------------
-- 3) eligible_months: filter to only payroll-eligible months.
--    Rules (docs §4.2):
--      - TERMINATED (full month)  → EXCLUDED
--      - PREBOARDING (full month) → EXCLUDED
--      - EXTERNAL with no salary  → excluded later (after salary join)
--    NON_PAY_LEAVE is INCLUDED with is_leave_month=1 per user decision.
--
--    Company filter: the spine uses the companyuuid from userstatus to build
--    periods. We keep the spine's companyuuid as the primary dimension; the
--    status_companyuuid (the company at month_end) must match for the row to
--    be valid (an employee who transferred companies should only appear under
--    the company they belonged to at month end).
-- ---------------------------------------------------------------------------
eligible_months AS (
    SELECT
        ls.useruuid,
        ls.companyuuid,
        ls.year_num,
        ls.month_num,
        ls.month_end,
        ls.month_start,
        ls.employee_status,
        ls.employee_type,
        CASE WHEN ls.employee_status = 'NON_PAY_LEAVE' THEN 1 ELSE 0 END
            AS is_leave_month
    FROM latest_status ls
    WHERE ls.employee_status NOT IN ('TERMINATED', 'PREBOARDING')
      -- Ensure the company at month-end matches the spine's company dimension
      -- (guards against company transfers where old period has old companyuuid)
      AND ls.status_companyuuid = ls.companyuuid
),

-- ---------------------------------------------------------------------------
-- 4) latest_salary: temporally resolve salary per (useruuid, month_end).
--    Apply owner salary overrides (150,000 for 3 owners; DB shows 100,000).
--    Owner UUIDs are hardcoded — see §1.1 of business-rules-and-math.md.
-- ---------------------------------------------------------------------------
salary_max_date AS (
    SELECT
        em.useruuid,
        em.month_end,
        MAX(s.activefrom) AS max_activefrom
    FROM eligible_months em
    JOIN salary s
        ON  s.useruuid   = em.useruuid
        AND s.activefrom <= em.month_end
    GROUP BY em.useruuid, em.month_end
),

latest_salary AS (
    SELECT
        smd.useruuid,
        smd.month_end,
        -- Owner salary override: DB stores 100,000 but actual is 150,000
        CASE
            WHEN s.useruuid IN (
                '8fa7f75a-57bf-4c6f-8db7-7e16067c1bcd',   -- Owner override 1
                '7948c5e8-162c-4053-b905-0f59a21d7746',   -- Owner override 2
                'ca0e1027-061f-49e7-b66a-a487c815f5a0'    -- Owner override 3
            ) THEN 150000.0
            ELSE CAST(s.salary AS DOUBLE)
        END                             AS effective_salary,
        CAST(s.salary AS DOUBLE)        AS db_salary,
        s.type                          AS salary_type
    FROM salary_max_date smd
    JOIN salary s
        ON  s.useruuid   = smd.useruuid
        AND s.activefrom = smd.max_activefrom
),

-- ---------------------------------------------------------------------------
-- 5) latest_pension: resolve pension rates per (useruuid, month_end).
--    COALESCE to 0 when no pension record exists (e.g., new hires or
--    HOURLY/STUDENT employees with no pension).
-- ---------------------------------------------------------------------------
pension_max_date AS (
    SELECT
        em.useruuid,
        em.month_end,
        MAX(up.active_date) AS max_active_date
    FROM eligible_months em
    LEFT JOIN user_pension up
        ON  up.useruuid    = em.useruuid
        AND up.active_date <= em.month_end
    GROUP BY em.useruuid, em.month_end
),

latest_pension AS (
    SELECT
        pmd.useruuid,
        pmd.month_end,
        COALESCE(up.pension_own, 0)     AS pension_own_pct,
        COALESCE(up.pension_company, 0) AS pension_company_pct
    FROM pension_max_date pmd
    LEFT JOIN user_pension up
        ON  up.useruuid    = pmd.useruuid
        AND up.active_date = pmd.max_active_date
),

-- ---------------------------------------------------------------------------
-- 6) active_supplements: SUM of salary_supplement.value for supplements
--    active during the calendar month.
--    Active = from_month <= month_end AND (to_month >= month_start OR to_month IS NULL)
-- ---------------------------------------------------------------------------
active_supplements AS (
    SELECT
        em.useruuid,
        em.month_end,
        COALESCE(SUM(ss.value), 0.0) AS supplements
    FROM eligible_months em
    LEFT JOIN salary_supplement ss
        ON  ss.useruuid    = em.useruuid
        AND ss.from_month <= em.month_end
        AND (ss.to_month >= em.month_start OR ss.to_month IS NULL)
    GROUP BY em.useruuid, em.month_end
),

-- ---------------------------------------------------------------------------
-- 7) monthly_lump_sums: SUM of salary_lump_sum.lump_sum for the target month.
--    The month column uses last-day-of-month storage convention; we query
--    with a range covering the full month to handle any storage convention.
-- ---------------------------------------------------------------------------
monthly_lump_sums AS (
    SELECT
        em.useruuid,
        em.month_end,
        COALESCE(SUM(ls.lump_sum), 0.0) AS lump_sums
    FROM eligible_months em
    LEFT JOIN salary_lump_sum ls
        ON  ls.useruuid = em.useruuid
        AND ls.month   >= em.month_start
        AND ls.month   <= em.month_end
    GROUP BY em.useruuid, em.month_end
),

-- ---------------------------------------------------------------------------
-- 8) hourly_hours: SUM of work.workduration for the hourly wages task,
--    paid out during the target month. Only relevant for HOURLY employees,
--    but computed for all to simplify the final join.
--    Task UUID: a7314f77-5e03-4f56-8b1c-0562e601f22f (Hourly wages task)
--    Critical: filter on paid_out (payroll cutoff date), NOT registered date.
-- ---------------------------------------------------------------------------
hourly_hours AS (
    SELECT
        em.useruuid,
        em.month_end,
        COALESCE(SUM(w.workduration), 0.0) AS hourly_hours
    FROM eligible_months em
    LEFT JOIN work w
        ON  w.useruuid = em.useruuid
        AND w.taskuuid = 'a7314f77-5e03-4f56-8b1c-0562e601f22f'
        AND w.paid_out >= em.month_start
        AND w.paid_out <  DATE_ADD(em.month_start, INTERVAL 1 MONTH)
    GROUP BY em.useruuid, em.month_end
)

-- ---------------------------------------------------------------------------
-- FINAL SELECT: join all CTEs and compute salary_sum
-- ---------------------------------------------------------------------------
SELECT
    -- Surrogate key
    CONCAT(em.useruuid, '-', em.companyuuid, '-',
           LPAD(em.year_num, 4, '0'), LPAD(em.month_num, 2, '0'))
                                                        AS salary_monthly_id,

    -- Employee dimensions
    em.useruuid,
    em.companyuuid,
    u.primaryskilltype                                  AS practice_id,

    -- Salary components
    lsal.effective_salary,
    lsal.db_salary,
    lsal.salary_type,

    -- Pension
    lpen.pension_own_pct,
    lpen.pension_company_pct,
    ROUND(
        lsal.effective_salary
        * (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0,
        2
    )                                                   AS pension_deduction,

    -- Bededagstillaeg (0.45% — always applied, docs §1.4)
    ROUND(lsal.effective_salary * 0.0045, 2)            AS bededag,

    -- Additional components
    ROUND(asup.supplements, 2)                          AS supplements,
    ROUND(mlump.lump_sums, 2)                           AS lump_sums,
    ROUND(hh.hourly_hours, 4)                           AS hourly_hours,

    -- Base pay (relevant for HOURLY; 0 for NORMAL)
    CASE
        WHEN lsal.salary_type = 'HOURLY'
            THEN ROUND(hh.hourly_hours * lsal.effective_salary, 2)
        ELSE 0.0
    END                                                 AS base_pay,

    -- AM-grundlag-like salary sum (the main metric)
    CASE
        WHEN lsal.salary_type = 'NORMAL' THEN
            -- NORMAL: effective_salary × (1 − pension_rate + 0.0045) + supplements + lump_sums
            ROUND(
                lsal.effective_salary
                * (1.0
                   - (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0
                   + 0.0045)
                + asup.supplements
                + mlump.lump_sums,
                2
            )
        WHEN lsal.salary_type = 'HOURLY' THEN
            -- HOURLY: (hours × rate) × 1.0045 − pension
            ROUND(
                (hh.hourly_hours * lsal.effective_salary) * 1.0045
                - (hh.hourly_hours * lsal.effective_salary
                   * (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0),
                2
            )
        ELSE 0.0
    END                                                 AS salary_sum,

    -- Status flags
    em.employee_status,
    em.employee_type,
    em.is_leave_month,

    -- Time dimensions
    CONCAT(LPAD(em.year_num, 4, '0'), LPAD(em.month_num, 2, '0'))
                                                        AS month_key,
    CAST(em.year_num AS SIGNED)                         AS year,
    CAST(em.month_num AS SIGNED)                        AS month_number,

    -- Fiscal year dimensions (July 1 → June 30)
    CASE
        WHEN em.month_num >= 7 THEN CAST(em.year_num AS SIGNED)
        ELSE CAST(em.year_num - 1 AS SIGNED)
    END                                                 AS fiscal_year,

    CASE
        WHEN em.month_num >= 7 THEN em.month_num - 6   -- Jul=1, Aug=2, ..., Dec=6
        ELSE em.month_num + 6                          -- Jan=7, Feb=8, ..., Jun=12
    END                                                 AS fiscal_month_number,

    -- Data source marker
    'SALARY_DB_CALC'                                    AS data_source

FROM eligible_months em

-- Resolve user for practice_id
JOIN user u
    ON u.uuid = em.useruuid

-- Inner join to salary: employees with no salary record are excluded
-- (EXTERNAL employees with salary = 0 are excluded via WHERE below)
JOIN latest_salary lsal
    ON  lsal.useruuid   = em.useruuid
    AND lsal.month_end  = em.month_end

-- Pension: LEFT join (0% if no record)
LEFT JOIN latest_pension lpen
    ON  lpen.useruuid  = em.useruuid
    AND lpen.month_end = em.month_end

-- Supplements: always present (COALESCE 0 in CTE)
LEFT JOIN active_supplements asup
    ON  asup.useruuid  = em.useruuid
    AND asup.month_end = em.month_end

-- Lump sums: always present (COALESCE 0 in CTE)
LEFT JOIN monthly_lump_sums mlump
    ON  mlump.useruuid  = em.useruuid
    AND mlump.month_end = em.month_end

-- Hourly hours: always present (COALESCE 0 in CTE)
LEFT JOIN hourly_hours hh
    ON  hh.useruuid  = em.useruuid
    AND hh.month_end = em.month_end

-- Exclude EXTERNAL employees with zero salary (no payslip generated, docs §4.2)
WHERE NOT (em.employee_type = 'EXTERNAL' AND lsal.db_salary = 0)

-- Exclude HOURLY employees with zero hours and zero salary (not paid this month)
-- NORMAL employees with salary=0 are still included (edge case: may have supplements)
AND NOT (lsal.salary_type = 'HOURLY'
         AND hh.hourly_hours = 0
         AND asup.supplements = 0
         AND mlump.lump_sums  = 0);


-- =============================================================================
-- COMPANION VIEW: fact_salary_monthly_teamroles
--
-- Purpose:
--   Extends fact_salary_monthly with a teamuuid dimension for team-filtered
--   queries. An employee in N teams appears N times (once per team), but
--   salary_sum is NOT multiplied — it is read-only from the primary view.
--
-- Grain: useruuid × companyuuid × month_key × teamuuid
--
-- teamroles filter: membertype = 'MEMBER' only (per user decision §2.2).
--   GUEST, SPONSOR, and LEADER roles are excluded.
--
-- Temporal join: a team membership is active if:
--   tr.startdate <= month_end AND (tr.enddate IS NULL OR tr.enddate > month_start)
--   This correctly handles memberships that start or end mid-month.
--
-- Usage note: when computing company-wide or user-wide salary totals, always
--   query fact_salary_monthly (not this view) to avoid double-counting.
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_salary_monthly_teamroles` AS

SELECT
    -- Compound surrogate key including team
    CONCAT(fsm.useruuid, '-', fsm.companyuuid, '-', fsm.month_key, '-', tr.teamuuid)
                                                        AS salary_team_id,

    -- All columns from the primary view
    fsm.salary_monthly_id,
    fsm.useruuid,
    fsm.companyuuid,
    tr.teamuuid,
    fsm.practice_id,
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

-- Join teamroles: MEMBER only, active during the month
JOIN teamroles tr
    ON  tr.useruuid    = fsm.useruuid
    AND tr.membertype  = 'MEMBER'
    -- Membership started on or before the last day of the month
    AND tr.startdate  <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.year, '-', fsm.month_number, '-01'), '%Y-%c-%d'))
    -- Membership had not ended before the first day of the month
    AND (tr.enddate IS NULL
         OR tr.enddate > STR_TO_DATE(CONCAT(fsm.year, '-', fsm.month_number, '-01'), '%Y-%c-%d'));
