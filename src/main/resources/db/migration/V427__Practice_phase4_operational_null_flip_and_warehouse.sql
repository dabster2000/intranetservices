-- =============================================================================
-- V427: Practice Part 2, Phase 4 — warehouse recreation + operational NULL flip.
--
-- The stored 'UD' sentinel stops existing in OPERATIONAL data: "no practice"
-- becomes NULL on `user`, `user_practice_history` (historical periods included)
-- and `sales_lead` — both key twins together. The WAREHOUSE layer keeps a
-- synthetic "No practice" dimension member instead, so no NULL group key ever
-- reaches an aggregate (the Kimball default-row pattern, spec §4.1).
--
-- ── The synthetic-member rule (§4.1 refinement, recorded here and in §1.6.J) ──
-- The "No practice" member is realized as the literal 'UD' string synthesized
-- in the view definitions — COALESCE(prg.code, 'UD') over a registry LEFT JOIN
-- on the row's practice_uuid, labelled COALESCE(prg.name, 'No practice') — NOT
-- as a reference to the UD registry row. Consequences, all intended:
--   * view outputs are byte-identical to the pre-flip state (the old
--     COALESCE(u.practice,'UD') views mapped NULL to 'UD' already, and stored
--     'UD' rows resolved to the UD registry row whose code is 'UD');
--   * the fact tables refreshed from these views keep their existing 'UD'
--     buckets, so no mat-table column or refresh procedure changes are needed
--     for the flip itself;
--   * consumer GROUP BYs never see a NULL practice key;
--   * the design survives Phase 5 dropping the UD registry row (the join then
--     misses and the COALESCE literals take over) and is rename-ready (the
--     emitted codes follow registry renames with no view change).
--
-- Views recreated (7): fact_backlog, fact_employee_monthly, fact_pipeline,
--   fact_project_financials, fact_revenue_budget, fact_salary_monthly,
--   fact_staffing_forecast_week. Each gets the registry join + the member
--   mapping, plus two ADDITIVE columns at the end of the select list:
--   practice_uuid (true NULL for the no-practice member) and practice_label.
--   The two dominant-practice votes (fact_backlog contract_service_line,
--   fact_project_financials service_line_ranking) now vote on practice_uuid
--   with the NULL group participating (pre-flip they voted on the code with
--   NULL excluded — the populations are identical after the flip, verified
--   against the golden master; no environment has pre-flip NULL-practice users
--   with contract/work rows).
--
-- Deliberately NOT touched:
--   * `consultant` — a user-shaped entity view (feeds the Employee entity);
--     its practice column follows operational truth, so 'UD' → NULL there is
--     the phase's one intended wire-visible change, not a warehouse dimension.
--   * fact_opex / fact_opex_budget — neither reads user/sales_lead practice
--     (fact_opex inherits its buckets from fact_salary_monthly and
--     fact_employee_monthly_mat; fact_opex_budget is the V126 stub reading its
--     own budget_source rows). They keep working unchanged.
--   * the 4 mat tables — their practice/service-line code columns are already
--     VARCHAR(50) on every environment (prod/staging/local verified), wide
--     enough for the Phase 5 canonical codes; the refresh procedures insert
--     explicit column lists, so the views' additive columns are invisible to
--     them.
--   * `team` — practice-less teams are already NULL (teams never stored 'UD').
--   * the UD registry row — dropped in Phase 5, still the filter-token alias.
--
-- Also recreated: the two pipeline snapshot procedures (V425) — only their
-- registry join moves from sl.practice (code) to sl.practice_uuid, which the
-- application maintains since Phase 3. Byte-identical today; removes the last
-- routine reference to a legacy practice code column ahead of Phase 5.
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §4.1 (operational-NULL / warehouse-member split), §4.4 wave 3, §1.6.J.
-- Plan:  docs/superpowers/plans/2026-07-19-practice-part2-phased-rollout.md
--        Phase 4.
--
-- Idempotency (mandatory — this file re-runs via repair-at-start and after the
-- nightly staging refresh strip): ALTERs are state-idempotent (MODIFY to the
-- same definition), views/procs are CREATE OR REPLACE, and the flip UPDATEs
-- match zero rows once applied (they key on the stored 'UD' code or a
-- UD-registry-row uuid, neither of which survives the flip).
--
-- ECS canary note: during cutover the old (Phase 3) task runs against this
-- schema and may still write 'UD' + the UD uuid on sync events. The daily
-- reconciliation tick (Phase 4 code) re-derives those users to NULL within a
-- day, and on staging this file re-applies each morning — transient by design,
-- same class as the V426 raw-write tolerance.
--
-- Rollback: forward-only per repo discipline for the data flip (the pre-flip
-- state is reconstructable — every flipped row is exactly the NULL-key rows);
-- views/procs are recreatable from V214/V333/V180/V389/V329/V425.
-- =============================================================================


-- 1) Structural: allow NULL practice periods. ---------------------------------
--    user_practice_history.practice was NOT NULL from V407 (history rows only
--    existed for non-null practices under the trigger regime). NULL periods are
--    first-class history from this phase on (spec §4.3), so the column becomes
--    nullable — same type otherwise. The uuid twin (V424) is already nullable,
--    and the interval CHECK / indexes do not touch the practice columns.
ALTER TABLE user_practice_history
    MODIFY COLUMN practice VARCHAR(50) NULL;

--    user.practice loses its 'UD' default (V418 set it as the safe raw-insert
--    default; the honest default for "no practice" is now NULL). Type unchanged.
ALTER TABLE `user`
    MODIFY COLUMN practice VARCHAR(3) NULL DEFAULT NULL;

--    sales_lead.practice is already nullable — nothing to alter.


-- =============================================================================
-- 2) Warehouse recreation: the seven practice-dimensioned views + the two
--    pipeline snapshot procedures. Definitions are their latest migration
--    sources (V214 / V333 / V180 / V389 / V329 / V425) with only the practice
--    plumbing changed as described in the file header. CREATE OR REPLACE
--    throughout — idempotent.
-- =============================================================================

-- ── 2a) fact_backlog (source: V214 §4) ────────────────────────────────────────

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_backlog` AS

WITH
    -- 1) Aggregate bi_budget_per_day to contract-month level
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

    -- 3) Get dominant service line per contract (from assigned consultants).
    --    The vote resolves each consultant's practice through the registry via
    --    practice_uuid and maps no-practice (NULL since the Phase 4 flip; the
    --    same population that stored 'UD' before it) to the 'UD' warehouse
    --    member INSIDE the vote — so those consultants keep participating as
    --    one group. NEW, PINNED TIE-BREAK: exactly tied votes (equal consultant
    --    count and equal hours — 53 contracts at implementation time) resolve
    --    to the registry's sort_order (lower first, the no-practice member
    --    last). The pre-flip definition left ties to the query plan's arbitrary
    --    group order, which no recreation can reproduce by construction; the
    --    pinned rule makes the dimension deterministic and stable across
    --    Phase 5's code renames. Recorded in spec §1.6.J.
    contract_service_line AS (
        SELECT
            cc.contractuuid AS contract_uuid,
            COALESCE(
                (SELECT COALESCE(vprg.code, 'UD')
                 FROM contract_consultants cc2
                 JOIN user u ON cc2.useruuid = u.uuid
                 LEFT JOIN practice vprg ON vprg.uuid = u.practice_uuid
                 WHERE cc2.contractuuid = cc.contractuuid
                 GROUP BY COALESCE(vprg.code, 'UD')
                 ORDER BY COUNT(*) DESC, SUM(cc2.hours) DESC,
                          MIN(COALESCE(vprg.sort_order, 2147483647)) ASC
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

    -- Data source
    'BI_BUDGET' AS data_source,

    -- Practice dimension, additive (Phase 4): true NULL for the no-practice
    -- member (also NULL for contracts with no assigned consultants). The join
    -- excludes the 'UD' member literal so its registry row (dropped in Phase 5)
    -- never leaks a uuid.
    prg.uuid AS practice_uuid,
    COALESCE(prg.name, 'No practice') AS practice_label

FROM backlog_by_contract_month bcm
    LEFT JOIN contract_metadata cm ON bcm.contract_uuid = cm.contract_uuid
    LEFT JOIN client cl ON bcm.client_uuid = cl.uuid
    LEFT JOIN contract_service_line csl ON bcm.contract_uuid = csl.contract_uuid
    LEFT JOIN practice prg ON prg.code = csl.service_line_id AND csl.service_line_id <> 'UD'
WHERE bcm.backlog_revenue_dkk > 0
ORDER BY bcm.year_val, bcm.month_val, bcm.contract_uuid;


-- ── 2b) fact_employee_monthly (source: V333) ──────────────────────────────────

CREATE OR REPLACE
    ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
VIEW `fact_employee_monthly` AS
WITH
    user_date_bounds AS (
        SELECT
            useruuid,
            MIN(document_date) AS first_date,
            MAX(document_date) AS last_date
        FROM fact_user_day
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
            COALESCE(prg.code, 'UD') AS practice_id,
            prg.uuid AS practice_uuid,
            COALESCE(prg.name, 'No practice') AS practice_label,
            CASE
                WHEN b.consultant_type = 'CONSULTANT'
                    AND b.status_type IN ('ACTIVE')
                    THEN 'BILLABLE'
                ELSE 'NON_BILLABLE'
            END AS role_type,
            COALESCE(b.gross_available_hours, 0) / 7.4 AS daily_fte,
            udb.first_date,
            udb.last_date
        FROM fact_user_day b
        INNER JOIN `user` u ON b.useruuid = u.uuid
        LEFT JOIN practice prg ON prg.uuid = u.practice_uuid
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
                WHEN DAYOFMONTH(document_date) = 1 THEN useruuid
            END) AS headcount_start,
            COUNT(DISTINCT CASE
                WHEN document_date = LAST_DAY(document_date) THEN useruuid
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
            practice_uuid,
            practice_label,
            role_type,
            COUNT(DISTINCT useruuid) AS distinct_employees,
            SUM(daily_fte) / COUNT(DISTINCT document_date) AS avg_monthly_fte,
            COUNT(DISTINCT CASE WHEN role_type = 'BILLABLE' THEN useruuid END) AS billable_headcount,
            COUNT(DISTINCT CASE WHEN role_type = 'NON_BILLABLE' THEN useruuid END) AS non_billable_headcount,
            SUM(CASE WHEN role_type = 'BILLABLE' THEN daily_fte ELSE 0 END) / COUNT(DISTINCT document_date) AS fte_billable,
            SUM(CASE WHEN role_type = 'NON_BILLABLE' THEN daily_fte ELSE 0 END) / COUNT(DISTINCT document_date) AS fte_non_billable
        FROM daily_employee_data
        -- practice_uuid/practice_label are 1:1 with practice_id (registry code,
        -- no-practice member = NULL uuid) — adding them keeps the groups identical.
        GROUP BY companyuuid, year_val, month_val, practice_id, practice_uuid, practice_label, role_type
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
    NULL AS cost_center_id,
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
    'HR_SYSTEM' AS data_source,

    -- Practice dimension, additive (Phase 4): true NULL for the no-practice
    -- member, registry display name as the label.
    mf.practice_uuid,
    mf.practice_label
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
    AND mf.role_type = jl.role_type
ORDER BY mf.year_val DESC, mf.month_val DESC, mf.companyuuid, mf.practice_id, mf.role_type;


-- ── 2c) fact_pipeline (source: V180) ──────────────────────────────────────────

CREATE OR REPLACE ALGORITHM=UNDEFINED
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
            sl.practice_uuid AS practice_uuid,
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
            po.practice_uuid,
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
    -- Registry-resolved service line: the hardcoded identity-mapping CTE is
    -- gone — codes come from the practice registry via the lead's practice_uuid,
    -- with NULL (no practice) mapping to the 'UD' warehouse member.
    COALESCE(prg.code, 'UD') AS service_line_id,
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

    'CRM' AS data_source,

    -- Practice dimension, additive (Phase 4): true NULL for the no-practice
    -- member, registry display name as the label.
    pbm.practice_uuid,
    COALESCE(prg.name, 'No practice') AS practice_label

FROM pipeline_by_month pbm
         LEFT JOIN client c ON pbm.client_uuid = c.uuid
         LEFT JOIN stage_mapping sm ON pbm.status = sm.status
         LEFT JOIN practice prg ON prg.uuid = pbm.practice_uuid
ORDER BY pbm.year_val, pbm.month_val, pbm.opportunity_uuid;


-- ── 2d) fact_project_financials (source: V389) ────────────────────────────────

CREATE OR REPLACE ALGORITHM=UNDEFINED
SQL SECURITY DEFINER
VIEW `fact_project_financials` AS
WITH

-- 1) Project-month base from work entries
project_months AS (
    SELECT DISTINCT
        COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
        YEAR(w.registered) AS year_val,
        MONTH(w.registered) AS month_val
    FROM `work` w
    LEFT JOIN `task` t ON w.taskuuid = t.uuid
    LEFT JOIN `project` p ON COALESCE(w.projectuuid, t.projectuuid) = p.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND p.uuid IS NOT NULL
      AND w.registered IS NOT NULL
      AND w.workduration > 0
),

-- 2) Invoice lines with companyuuid resolved from userstatus on invoicedate
invoice_line_companies AS (
    SELECT
        p.uuid AS project_uuid,
        YEAR(i.invoicedate) AS year_val,
        MONTH(i.invoicedate) AS month_val,
        COALESCE(
            (
                SELECT us.companyuuid
                FROM userstatus us
                WHERE us.useruuid = ii.consultantuuid
                  AND us.statusdate <= i.invoicedate
                ORDER BY us.statusdate DESC
                LIMIT 1
            ),
            'd8894494-2fb4-4f72-9e05-e6032e6dd691'
        ) AS companyuuid,
        CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END AS sign,
        (ii.rate * ii.hours *
            CASE WHEN i.currency = 'DKK' THEN 1 ELSE c.conversion END
        ) AS line_amount_dkk
    FROM `project` p
    JOIN `invoices` i ON p.uuid = i.projectuuid
    JOIN `invoiceitems` ii ON i.uuid = ii.invoiceuuid
    LEFT JOIN `currences` c ON c.currency = i.currency AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m')
    WHERE i.status = 'CREATED'
      AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE')
      AND (i.type <> 'CREDIT_NOTE' OR i.debtor_companyuuid IS NULL)
      AND ii.rate IS NOT NULL
      AND ii.hours IS NOT NULL
      AND (i.currency = 'DKK' OR c.uuid IS NOT NULL)
),

-- 3) Revenue per project-month-company
revenue_by_company AS (
    SELECT
        ilc.project_uuid,
        ilc.year_val,
        ilc.month_val,
        ilc.companyuuid,
        SUM(ilc.sign * ilc.line_amount_dkk) AS total_revenue_dkk
    FROM invoice_line_companies ilc
    GROUP BY ilc.project_uuid, ilc.year_val, ilc.month_val, ilc.companyuuid
),

-- 4) Work cost (employees) per project-month-company
work_cost_aggregation AS (
    SELECT
        wc.project_uuid,
        wc.year_val,
        wc.month_val,
        wc.companyuuid,
        SUM(wc.employee_salary_cost_dkk) AS employee_salary_cost_dkk,
        SUM(wc.workduration) AS total_hours,
        COUNT(DISTINCT wc.useruuid) AS consultant_count
    FROM (
        SELECT
            COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
            YEAR(w.registered) AS year_val,
            MONTH(w.registered) AS month_val,
            COALESCE(
                (
                    SELECT us.companyuuid
                    FROM userstatus us
                    WHERE us.useruuid = w.useruuid
                      AND us.statusdate <= w.registered
                    ORDER BY us.statusdate DESC
                    LIMIT 1
                ),
                'd8894494-2fb4-4f72-9e05-e6032e6dd691'
            ) AS companyuuid,
            w.useruuid,
            w.workduration,
            w.workduration * (
                COALESCE(
                    (
                        SELECT s.salary
                        FROM salary s
                        WHERE s.useruuid = w.useruuid
                          AND s.activefrom <= w.registered
                        ORDER BY s.activefrom DESC
                        LIMIT 1
                    ),
                    0
                ) / 160.33
            ) AS employee_salary_cost_dkk
        FROM `work` w
        LEFT JOIN `task` t ON w.taskuuid = t.uuid
        JOIN `user` u ON w.useruuid = u.uuid
        WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
          AND u.type = 'USER'
          AND w.workduration > 0
          AND w.registered IS NOT NULL
    ) wc
    GROUP BY wc.project_uuid, wc.year_val, wc.month_val, wc.companyuuid
),

-- 5) External consultant cost per project-month-company
external_consultant_cost_aggregation AS (
    SELECT
        ec.project_uuid,
        ec.year_val,
        ec.month_val,
        ec.companyuuid,
        SUM(ec.external_cost_dkk) AS external_cost_dkk
    FROM (
        SELECT
            COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
            YEAR(w.registered) AS year_val,
            MONTH(w.registered) AS month_val,
            COALESCE(
                (
                    SELECT us.companyuuid
                    FROM userstatus us
                    WHERE us.useruuid = w.useruuid
                      AND us.statusdate <= w.registered
                    ORDER BY us.statusdate DESC
                    LIMIT 1
                ),
                'd8894494-2fb4-4f72-9e05-e6032e6dd691'
            ) AS companyuuid,
            w.workduration * COALESCE(
                (
                    SELECT s.salary
                    FROM salary s
                    WHERE s.useruuid = w.useruuid
                      AND s.activefrom <= w.registered
                      AND s.type = 'HOURLY'
                    ORDER BY s.activefrom DESC
                    LIMIT 1
                ),
                0
            ) AS external_cost_dkk
        FROM `work` w
        LEFT JOIN `task` t ON w.taskuuid = t.uuid
        JOIN `user` u ON w.useruuid = u.uuid
        WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
          AND u.type IN ('EXTERNAL', 'CONSULTANT')
          AND w.workduration > 0
          AND w.registered IS NOT NULL
    ) ec
    GROUP BY ec.project_uuid, ec.year_val, ec.month_val, ec.companyuuid
),

-- 6) Project expenses aggregated per project-month (no company split yet)
expenses_by_project_month AS (
    SELECT
        e.projectuuid AS project_uuid,
        YEAR(e.expensedate) AS year_val,
        MONTH(e.expensedate) AS month_val,
        SUM(e.amount) AS expense_cost_dkk
    FROM `expenses` e
    WHERE e.projectuuid IS NOT NULL
      AND e.status IN ('VERIFIED_BOOKED','VERIFIED_UNBOOKED')
      AND e.expensedate IS NOT NULL
    GROUP BY e.projectuuid, YEAR(e.expensedate), MONTH(e.expensedate)
),

-- 7) Build company weights per project-month
revenue_weights AS (
    SELECT
        rbc.project_uuid,
        rbc.year_val,
        rbc.month_val,
        rbc.companyuuid,
        rbc.total_revenue_dkk,
        SUM(rbc.total_revenue_dkk) OVER (PARTITION BY rbc.project_uuid, rbc.year_val, rbc.month_val) AS sum_rev,
        CASE
            WHEN SUM(rbc.total_revenue_dkk) OVER (PARTITION BY rbc.project_uuid, rbc.year_val, rbc.month_val) > 0
                THEN rbc.total_revenue_dkk / SUM(rbc.total_revenue_dkk) OVER (PARTITION BY rbc.project_uuid, rbc.year_val, rbc.month_val)
            ELSE NULL
        END AS rev_weight
    FROM revenue_by_company rbc
),

hour_weights AS (
    SELECT
        wca.project_uuid,
        wca.year_val,
        wca.month_val,
        wca.companyuuid,
        wca.total_hours,
        SUM(wca.total_hours) OVER (PARTITION BY wca.project_uuid, wca.year_val, wca.month_val) AS sum_hours,
        CASE
            WHEN SUM(wca.total_hours) OVER (PARTITION BY wca.project_uuid, wca.year_val, wca.month_val) > 0
                THEN wca.total_hours / SUM(wca.total_hours) OVER (PARTITION BY wca.project_uuid, wca.year_val, wca.month_val)
            ELSE NULL
        END AS hour_weight
    FROM work_cost_aggregation wca
),

-- 8) Distribute expenses by chosen weights
project_expense_by_company AS (
    SELECT
        e.project_uuid,
        e.year_val,
        e.month_val,
        w.companyuuid,
        e.expense_cost_dkk * w.weight AS expense_cost_dkk
    FROM expenses_by_project_month e
    JOIN (
        SELECT project_uuid, year_val, month_val, companyuuid, rev_weight AS weight
        FROM revenue_weights
        WHERE sum_rev > 0
        UNION ALL
        SELECT project_uuid, year_val, month_val, companyuuid, hour_weight AS weight
        FROM hour_weights
        WHERE sum_hours > 0
        UNION ALL
        SELECT e2.project_uuid, e2.year_val, e2.month_val, 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AS companyuuid, 1.0 AS weight
        FROM expenses_by_project_month e2
        WHERE NOT EXISTS (
            SELECT 1 FROM revenue_weights r
            WHERE r.project_uuid = e2.project_uuid AND r.year_val = e2.year_val AND r.month_val = e2.month_val AND r.sum_rev > 0
        )
        AND NOT EXISTS (
            SELECT 1 FROM hour_weights h
            WHERE h.project_uuid = e2.project_uuid AND h.year_val = e2.year_val AND h.month_val = e2.month_val AND h.sum_hours > 0
        )
    ) w ON w.project_uuid = e.project_uuid AND w.year_val = e.year_val AND w.month_val = e.month_val
),

-- 9) Total cost per project-month-company
total_cost_aggregation AS (
    SELECT
        COALESCE(wca.project_uuid, eca.project_uuid, pebc.project_uuid) AS project_uuid,
        COALESCE(wca.year_val, eca.year_val, pebc.year_val) AS year_val,
        COALESCE(wca.month_val, eca.month_val, pebc.month_val) AS month_val,
        COALESCE(wca.companyuuid, eca.companyuuid, pebc.companyuuid) AS companyuuid,
        COALESCE(wca.employee_salary_cost_dkk, 0) AS employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) AS external_cost_dkk,
        COALESCE(pebc.expense_cost_dkk, 0) AS expense_cost_dkk,
        COALESCE(wca.employee_salary_cost_dkk, 0) + COALESCE(eca.external_cost_dkk, 0) + COALESCE(pebc.expense_cost_dkk, 0) AS total_cost_dkk,
        COALESCE(wca.total_hours, 0) AS total_hours,
        COALESCE(wca.consultant_count, 0) AS consultant_count
    FROM work_cost_aggregation wca
    LEFT JOIN external_consultant_cost_aggregation eca
        ON wca.project_uuid = eca.project_uuid AND wca.year_val = eca.year_val AND wca.month_val = eca.month_val AND wca.companyuuid = eca.companyuuid
    LEFT JOIN project_expense_by_company pebc
        ON wca.project_uuid = pebc.project_uuid AND wca.year_val = pebc.year_val AND wca.month_val = pebc.month_val AND wca.companyuuid = pebc.companyuuid

    UNION

    SELECT
        COALESCE(eca.project_uuid, pebc.project_uuid) AS project_uuid,
        COALESCE(eca.year_val, pebc.year_val) AS year_val,
        COALESCE(eca.month_val, pebc.month_val) AS month_val,
        COALESCE(eca.companyuuid, pebc.companyuuid) AS companyuuid,
        0 AS employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) AS external_cost_dkk,
        COALESCE(pebc.expense_cost_dkk, 0) AS expense_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) + COALESCE(pebc.expense_cost_dkk, 0) AS total_cost_dkk,
        0 AS total_hours,
        0 AS consultant_count
    FROM external_consultant_cost_aggregation eca
    LEFT JOIN project_expense_by_company pebc
        ON eca.project_uuid = pebc.project_uuid AND eca.year_val = pebc.year_val AND eca.month_val = pebc.month_val AND eca.companyuuid = pebc.companyuuid
    WHERE NOT EXISTS (
        SELECT 1 FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = eca.project_uuid AND wca2.year_val = eca.year_val AND wca2.month_val = eca.month_val AND wca2.companyuuid = eca.companyuuid
        LIMIT 1
    )

    UNION

    SELECT
        pebc.project_uuid,
        pebc.year_val,
        pebc.month_val,
        pebc.companyuuid,
        0 AS employee_salary_cost_dkk,
        0 AS external_cost_dkk,
        pebc.expense_cost_dkk AS expense_cost_dkk,
        pebc.expense_cost_dkk AS total_cost_dkk,
        0 AS total_hours,
        0 AS consultant_count
    FROM project_expense_by_company pebc
    WHERE NOT EXISTS (
        SELECT 1 FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = pebc.project_uuid AND wca2.year_val = pebc.year_val AND wca2.month_val = pebc.month_val AND wca2.companyuuid = pebc.companyuuid
        LIMIT 1
    )
    AND NOT EXISTS (
        SELECT 1 FROM external_consultant_cost_aggregation eca2
        WHERE eca2.project_uuid = pebc.project_uuid AND eca2.year_val = pebc.year_val AND eca2.month_val = pebc.month_val AND eca2.companyuuid = pebc.companyuuid
        LIMIT 1
    )
),

-- 10) Service line: dominant practice per project-month (no company split).
--     The vote resolves each consultant's practice through the registry via
--     practice_uuid and maps no-practice (NULL since the Phase 4 flip; the same
--     population that stored 'UD' before it) to the 'UD' warehouse member
--     INSIDE the vote — so those consultants keep participating as one group.
--     NEW, PINNED TIE-BREAK: exactly tied votes (equal hours and equal
--     consultant count) resolve to the registry's sort_order (lower first, the
--     no-practice member last). The pre-flip definition left ties to the query
--     plan's arbitrary group order, which no recreation can reproduce by
--     construction; the pinned rule makes the dimension deterministic and
--     stable across Phase 5's code renames (spec §1.6.J). Project-months with
--     no work rows still fall through to 'UNKNOWN' in the outer SELECT.
service_line_ranking AS (
    SELECT
        COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
        YEAR(w.registered) AS year_val,
        MONTH(w.registered) AS month_val,
        COALESCE(vprg.code, 'UD') AS practice,
        SUM(w.workduration) AS hours_by_practice,
        ROW_NUMBER() OVER (
            PARTITION BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered)
            ORDER BY SUM(w.workduration) DESC, COUNT(DISTINCT w.useruuid) DESC,
                     MIN(COALESCE(vprg.sort_order, 2147483647)) ASC
        ) AS skill_rank
    FROM `work` w
    LEFT JOIN `task` t ON w.taskuuid = t.uuid
    JOIN `user` u ON w.useruuid = u.uuid
    LEFT JOIN practice vprg ON vprg.uuid = u.practice_uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND u.type = 'USER'
      AND w.workduration > 0
    GROUP BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered), COALESCE(vprg.code, 'UD')
),

dominant_service_line AS (
    SELECT slr.project_uuid, slr.year_val, slr.month_val,
           slr.practice AS dominant_skilltype,
           -- Additive uuid/label for the winner; the 'UD' member literal is
           -- excluded from the join so its registry row (dropped in Phase 5)
           -- never leaks a uuid.
           prg.uuid AS practice_uuid,
           COALESCE(prg.name, 'No practice') AS practice_label
    FROM service_line_ranking slr
    LEFT JOIN practice prg ON prg.code = slr.practice AND slr.practice <> 'UD'
    WHERE slr.skill_rank = 1
),

-- 11) Contract type per project
project_contract_types AS (
    SELECT
        p.uuid AS project_uuid,
        c.contracttype AS contracttype,
        COUNT(0) AS contract_count,
        ROW_NUMBER() OVER (
            PARTITION BY p.uuid
            ORDER BY COUNT(0) DESC
        ) AS rank_num
    FROM `project` p
    JOIN `contract_project` cp ON p.uuid = cp.projectuuid
    JOIN `contracts` c ON cp.contractuuid = c.uuid
    WHERE c.contracttype IS NOT NULL
      AND c.status IN ('SIGNED','TIME','BUDGET')
    GROUP BY p.uuid, c.contracttype
),

primary_contract_type AS (
    SELECT pct.project_uuid, pct.contracttype
    FROM project_contract_types pct
    WHERE pct.rank_num = 1
),

-- 12) Final project-month-company key set
project_month_companies AS (
    SELECT DISTINCT pm.project_uuid, pm.year_val, pm.month_val, rbc.companyuuid
    FROM project_months pm
    JOIN revenue_by_company rbc ON pm.project_uuid = rbc.project_uuid AND pm.year_val = rbc.year_val AND pm.month_val = rbc.month_val

    UNION

    SELECT DISTINCT pm.project_uuid, pm.year_val, pm.month_val, tca.companyuuid
    FROM project_months pm
    JOIN total_cost_aggregation tca ON pm.project_uuid = tca.project_uuid AND pm.year_val = tca.year_val AND pm.month_val = tca.month_val

    UNION

    SELECT pm.project_uuid, pm.year_val, pm.month_val, 'd8894494-2fb4-4f72-9e05-e6032e6dd691' AS companyuuid
    FROM project_months pm
    WHERE NOT EXISTS (
        SELECT 1 FROM revenue_by_company rbc
        WHERE rbc.project_uuid = pm.project_uuid AND rbc.year_val = pm.year_val AND rbc.month_val = pm.month_val
    )
    AND NOT EXISTS (
        SELECT 1 FROM total_cost_aggregation tca
        WHERE tca.project_uuid = pm.project_uuid AND tca.year_val = pm.year_val AND tca.month_val = pm.month_val
    )
)

SELECT
    -- Composite key includes companyuuid to prevent INSERT IGNORE duplicate-row drops
    CONCAT(pmc.project_uuid, '-', pmc.companyuuid, '-', CONCAT(LPAD(pmc.year_val, 4, '0'), LPAD(pmc.month_val, 2, '0'))) AS project_financial_id,
    pmc.project_uuid AS project_id,
    p.clientuuid AS client_id,
    pmc.companyuuid AS companyuuid,
    COALESCE(c.segment, 'OTHER') AS sector_id,
    COALESCE(dsl.dominant_skilltype, 'UNKNOWN') AS service_line_id,
    COALESCE(pct.contracttype, 'PERIOD') AS contract_type_id,
    CONCAT(LPAD(pmc.year_val, 4, '0'), LPAD(pmc.month_val, 2, '0')) AS month_key,
    pmc.year_val AS year,
    pmc.month_val AS month_number,
    COALESCE(rbc.total_revenue_dkk, 0) AS recognized_revenue_dkk,
    COALESCE(tca.employee_salary_cost_dkk, 0) AS employee_salary_cost_dkk,
    COALESCE(tca.external_cost_dkk, 0) AS external_consultant_cost_dkk,
    COALESCE(tca.expense_cost_dkk, 0) AS project_expense_cost_dkk,
    COALESCE(tca.total_cost_dkk, 0) AS direct_delivery_cost_dkk,
    COALESCE(tca.total_hours, 0) AS total_hours,
    COALESCE(tca.consultant_count, 0) AS consultant_count,
    'OPERATIONAL' AS data_source,

    -- Practice dimension, additive (Phase 4): true NULL for the no-practice
    -- member. Both stay NULL on 'UNKNOWN' rows (no work-based vote exists).
    dsl.practice_uuid,
    dsl.practice_label
FROM project_month_companies pmc
JOIN `project` p ON pmc.project_uuid = p.uuid
LEFT JOIN `client` c ON p.clientuuid = c.uuid
LEFT JOIN revenue_by_company rbc
    ON pmc.project_uuid = rbc.project_uuid AND pmc.year_val = rbc.year_val AND pmc.month_val = rbc.month_val AND pmc.companyuuid = rbc.companyuuid
LEFT JOIN total_cost_aggregation tca
    ON pmc.project_uuid = tca.project_uuid AND pmc.year_val = tca.year_val AND pmc.month_val = tca.month_val AND pmc.companyuuid = tca.companyuuid
LEFT JOIN dominant_service_line dsl
    ON pmc.project_uuid = dsl.project_uuid AND pmc.year_val = dsl.year_val AND pmc.month_val = dsl.month_val
LEFT JOIN primary_contract_type pct ON pmc.project_uuid = pct.project_uuid
ORDER BY pmc.year_val DESC, pmc.month_val DESC, pmc.project_uuid, pmc.companyuuid;


-- ── 2e) fact_revenue_budget (source: V214 §3) ─────────────────────────────────

CREATE OR REPLACE ALGORITHM=UNDEFINED
SQL SECURITY DEFINER
VIEW `fact_revenue_budget` AS
WITH budget_with_dimensions AS (
    SELECT
        b.companyuuid,
        b.clientuuid,
        b.contractuuid,
        b.useruuid,
        b.year AS year_val,
        b.month AS month_val,
        b.budgetHours,
        b.rate,
        COALESCE(prg.code, 'UD') AS service_line_id,
        COALESCE(c.segment, 'OTHER') AS sector_id,
        COALESCE(ct.contracttype, 'PERIOD') AS contract_type_id
    FROM bi_budget_per_day b
    LEFT JOIN user u ON b.useruuid = u.uuid
    LEFT JOIN practice prg ON prg.uuid = u.practice_uuid
    LEFT JOIN client c ON b.clientuuid = c.uuid
    LEFT JOIN contracts ct ON b.contractuuid = ct.uuid
    WHERE b.budgetHours > 0
      AND b.document_date IS NOT NULL
      AND b.companyuuid IS NOT NULL
),
budget_aggregated AS (
    SELECT
        bd.companyuuid,
        bd.service_line_id,
        bd.sector_id,
        bd.contract_type_id,
        bd.year_val,
        bd.month_val,
        SUM(bd.budgetHours * bd.rate) AS budget_revenue_dkk,
        SUM(bd.budgetHours) AS budget_hours,
        COUNT(DISTINCT bd.contractuuid) AS contract_count,
        COUNT(DISTINCT bd.useruuid) AS consultant_count
    FROM budget_with_dimensions bd
    GROUP BY bd.companyuuid, bd.service_line_id, bd.sector_id, bd.contract_type_id, bd.year_val, bd.month_val
)
SELECT
    -- Primary Key
    CONCAT(
        ba.companyuuid, '-',
        ba.service_line_id, '-',
        ba.sector_id, '-',
        ba.contract_type_id, '-',
        LPAD(ba.year_val, 4, '0'),
        LPAD(ba.month_val, 2, '0')
    ) AS revenue_budget_id,

    -- Dimension Columns
    ba.companyuuid AS company_id,
    ba.service_line_id,
    ba.sector_id,
    ba.contract_type_id,

    -- Calendar Time Dimensions (existing)
    CONCAT(LPAD(ba.year_val, 4, '0'), LPAD(ba.month_val, 2, '0')) AS month_key,
    ba.year_val AS year,
    ba.month_val AS month_number,

    -- Fiscal Year Dimensions
    CASE
        WHEN ba.month_val >= 7 THEN ba.year_val     -- Jul-Dec: same year
        ELSE ba.year_val - 1                        -- Jan-Jun: previous year
    END AS fiscal_year,

    CASE
        WHEN ba.month_val >= 7 THEN ba.month_val - 6  -- Jul=1, Aug=2, ..., Dec=6
        ELSE ba.month_val + 6                         -- Jan=7, Feb=8, ..., Jun=12
    END AS fiscal_month_number,

    CONCAT(
        'FY',
        CASE WHEN ba.month_val >= 7 THEN ba.year_val ELSE ba.year_val - 1 END,
        '-',
        LPAD(
            CASE WHEN ba.month_val >= 7 THEN ba.month_val - 6 ELSE ba.month_val + 6 END,
            2, '0'
        )
    ) AS fiscal_month_key,

    -- Budget Scenario
    'ORIGINAL' AS budget_scenario,

    -- Metrics
    ba.budget_revenue_dkk,
    ba.budget_hours,
    ba.contract_count,
    ba.consultant_count,

    -- Practice dimension, additive (Phase 4): true NULL for the no-practice
    -- member, registry display name as the label. Derived from the aggregated
    -- code so the aggregation itself is untouched; the join excludes the 'UD'
    -- member literal so its registry row (dropped in Phase 5) never leaks a uuid.
    prgo.uuid AS practice_uuid,
    COALESCE(prgo.name, 'No practice') AS practice_label
FROM budget_aggregated ba
LEFT JOIN practice prgo ON prgo.code = ba.service_line_id AND ba.service_line_id <> 'UD'
ORDER BY ba.year_val DESC, ba.month_val DESC, ba.companyuuid, ba.service_line_id;


-- ── 2f) fact_salary_monthly (source: V214 §6) ─────────────────────────────────

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_salary_monthly` AS

WITH

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
            DATE_ADD(CURDATE(), INTERVAL 1 MONTH)
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
       AND DATE_ADD(sp.period_start, INTERVAL n.n MONTH) >= DATE_SUB(CURDATE(), INTERVAL 120 MONTH)
),

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
      AND ls.status_companyuuid = ls.companyuuid
),

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
        CASE
            WHEN s.useruuid IN (
                '8fa7f75a-57bf-4c6f-8db7-7e16067c1bcd',
                '7948c5e8-162c-4053-b905-0f59a21d7746',
                'ca0e1027-061f-49e7-b66a-a487c815f5a0'
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

SELECT
    CONCAT(em.useruuid, '-', em.companyuuid, '-',
           LPAD(em.year_num, 4, '0'), LPAD(em.month_num, 2, '0'))
                                                        AS salary_monthly_id,

    em.useruuid,
    em.companyuuid,
    COALESCE(prg.code, 'UD')                            AS practice_id,

    lsal.effective_salary,
    lsal.db_salary,
    lsal.salary_type,

    lpen.pension_own_pct,
    lpen.pension_company_pct,
    ROUND(
        lsal.effective_salary
        * (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0,
        2
    )                                                   AS pension_deduction,

    ROUND(lsal.effective_salary * 0.0045, 2)            AS bededag,

    ROUND(asup.supplements, 2)                          AS supplements,
    ROUND(mlump.lump_sums, 2)                           AS lump_sums,
    ROUND(hh.hourly_hours, 4)                           AS hourly_hours,

    CASE
        WHEN lsal.salary_type = 'HOURLY'
            THEN ROUND(hh.hourly_hours * lsal.effective_salary, 2)
        ELSE 0.0
    END                                                 AS base_pay,

    CASE
        WHEN lsal.salary_type = 'NORMAL' THEN
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
            ROUND(
                (hh.hourly_hours * lsal.effective_salary) * 1.0045
                - (hh.hourly_hours * lsal.effective_salary
                   * (lpen.pension_own_pct + lpen.pension_company_pct) / 100.0),
                2
            )
        ELSE 0.0
    END                                                 AS salary_sum,

    em.employee_status,
    em.employee_type,
    em.is_leave_month,

    CONCAT(LPAD(em.year_num, 4, '0'), LPAD(em.month_num, 2, '0'))
                                                        AS month_key,
    CAST(em.year_num AS SIGNED)                         AS year,
    CAST(em.month_num AS SIGNED)                        AS month_number,

    CASE
        WHEN em.month_num >= 7 THEN CAST(em.year_num AS SIGNED)
        ELSE CAST(em.year_num - 1 AS SIGNED)
    END                                                 AS fiscal_year,

    CASE
        WHEN em.month_num >= 7 THEN em.month_num - 6
        ELSE em.month_num + 6
    END                                                 AS fiscal_month_number,

    'SALARY_DB_CALC'                                    AS data_source,

    -- Practice dimension, additive (Phase 4): true NULL for the no-practice
    -- member, registry display name as the label.
    prg.uuid                                            AS practice_uuid,
    COALESCE(prg.name, 'No practice')                   AS practice_label

FROM eligible_months em

JOIN user u
    ON u.uuid = em.useruuid

LEFT JOIN practice prg
    ON prg.uuid = u.practice_uuid

JOIN latest_salary lsal
    ON  lsal.useruuid   = em.useruuid
    AND lsal.month_end  = em.month_end

LEFT JOIN latest_pension lpen
    ON  lpen.useruuid  = em.useruuid
    AND lpen.month_end = em.month_end

LEFT JOIN active_supplements asup
    ON  asup.useruuid  = em.useruuid
    AND asup.month_end = em.month_end

LEFT JOIN monthly_lump_sums mlump
    ON  mlump.useruuid  = em.useruuid
    AND mlump.month_end = em.month_end

LEFT JOIN hourly_hours hh
    ON  hh.useruuid  = em.useruuid
    AND hh.month_end = em.month_end

WHERE NOT (em.employee_type = 'EXTERNAL' AND lsal.db_salary = 0)

AND NOT (lsal.salary_type = 'HOURLY'
         AND hh.hourly_hours = 0
         AND asup.supplements = 0
         AND mlump.lump_sums  = 0);


-- ── 2g) fact_staffing_forecast_week (source: V329) ────────────────────────────

CREATE OR REPLACE VIEW fact_staffing_forecast_week AS
WITH
week_calendar AS (
    SELECT
        CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK         AS week_start_date,
        CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n * 7 + 6 DAY  AS week_end_date,
        YEARWEEK(CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK, 3) AS week_key,
        YEAR(CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK)        AS year_val,
        WEEK(CURDATE() - INTERVAL WEEKDAY(CURDATE()) DAY + INTERVAL weeks.n WEEK, 3)     AS iso_week_number
    FROM (
        SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
        UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
        UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
    ) weeks
),
user_company_mapping AS (
    SELECT DISTINCT
        b.useruuid AS user_id,
        b.document_date,
        COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id
    FROM bi_budget_per_day b
    WHERE b.budgetHours > 0
      AND b.document_date >= CURDATE()
      AND b.document_date <  CURDATE() + INTERVAL 12 WEEK
),
forecast_billable_aggregated AS (
    SELECT
        b.useruuid AS user_id,
        COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
        YEARWEEK(b.document_date, 3) AS week_key,
        SUM(b.budgetHours)                                  AS forecast_billable_hours,
        SUM(b.budgetHoursWithNoAvailabilityAdjustment)      AS raw_billable_hours,
        SUM(b.budgetHours * b.rate)                         AS forecast_revenue_dkk,
        COUNT(DISTINCT b.contractuuid)                      AS contract_count
    FROM bi_budget_per_day b
    WHERE b.budgetHours > 0
      AND b.document_date >= CURDATE()
      AND b.document_date <  CURDATE() + INTERVAL 12 WEEK
    GROUP BY b.useruuid, COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691'), YEARWEEK(b.document_date, 3)
),
capacity_by_user_company_week AS (
    SELECT
        a.useruuid                          AS user_id,
        ucm.company_id                      AS company_id,
        YEARWEEK(a.document_date, 3)        AS week_key,
        SUM(a.gross_available_hours)        AS gross_capacity_hours,
        SUM(a.vacation_hours)               AS planned_vacation_hours,
        SUM(a.sick_hours)                   AS planned_sick_hours,
        SUM(a.maternity_leave_hours)        AS planned_maternity_hours,
        SUM(a.non_payd_leave_hours)         AS planned_unpaid_leave_hours,
        SUM(a.paid_leave_hours)             AS planned_paid_leave_hours,
        SUM(a.unavailable_hours)            AS total_unavailable_hours,
        SUM(a.gross_available_hours) - SUM(a.unavailable_hours) AS capacity_hours,
        MAX(a.consultant_type)              AS consultant_type,
        MAX(a.status_type)                  AS status_type
    FROM fact_user_day a
    JOIN user_company_mapping ucm
        ON a.useruuid = ucm.user_id
       AND a.document_date = ucm.document_date
    WHERE a.status_type NOT IN ('TERMINATED', 'PREBOARDING')
      AND a.consultant_type IN ('CONSULTANT', 'STUDENT', 'EXTERNAL')
      AND a.document_date >= CURDATE()
      AND a.document_date <  CURDATE() + INTERVAL 12 WEEK
    GROUP BY a.useruuid, ucm.company_id, YEARWEEK(a.document_date, 3)
),
aggregated_user_company_week AS (
    SELECT
        f.user_id, f.company_id, f.week_key,
        f.forecast_billable_hours, f.raw_billable_hours, f.forecast_revenue_dkk, f.contract_count,
        COALESCE(c.capacity_hours, 0)         AS capacity_hours,
        COALESCE(c.gross_capacity_hours, 0)   AS gross_capacity_hours,
        COALESCE(c.total_unavailable_hours, 0) AS total_unavailable_hours,
        COALESCE(c.planned_vacation_hours, 0) AS planned_vacation_hours,
        c.consultant_type, c.status_type,
        'BACKLOG' AS source_type
    FROM forecast_billable_aggregated f
    LEFT JOIN capacity_by_user_company_week c
        ON f.user_id = c.user_id
       AND f.company_id = c.company_id
       AND f.week_key = c.week_key
    UNION ALL
    SELECT
        c.user_id, c.company_id, c.week_key,
        0, 0, 0, 0,
        c.capacity_hours, c.gross_capacity_hours, c.total_unavailable_hours, c.planned_vacation_hours,
        c.consultant_type, c.status_type,
        'CAPACITY_ONLY' AS source_type
    FROM capacity_by_user_company_week c
    LEFT JOIN forecast_billable_aggregated f
        ON c.user_id = f.user_id
       AND c.company_id = f.company_id
       AND c.week_key = f.week_key
    WHERE f.user_id IS NULL
),
project_detail AS (
    SELECT
        b.useruuid AS user_id,
        COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691') AS company_id,
        YEARWEEK(b.document_date, 3) AS week_key,
        b.contractuuid AS project_id,
        b.clientuuid   AS client_id,
        SUM(b.budgetHours) AS project_billable_hours
    FROM bi_budget_per_day b
    WHERE b.budgetHours > 0
      AND b.document_date >= CURDATE()
      AND b.document_date <  CURDATE() + INTERVAL 12 WEEK
    GROUP BY b.useruuid, COALESCE(b.companyuuid, 'd8894494-2fb4-4f72-9e05-e6032e6dd691'),
             YEARWEEK(b.document_date, 3), b.contractuuid, b.clientuuid
),
final_enriched AS (
    SELECT
        agg.user_id, agg.company_id, agg.week_key,
        agg.forecast_billable_hours, agg.raw_billable_hours, agg.forecast_revenue_dkk, agg.contract_count,
        agg.capacity_hours, agg.gross_capacity_hours, agg.total_unavailable_hours, agg.planned_vacation_hours,
        agg.consultant_type, agg.status_type, agg.source_type,
        pd.project_id, pd.client_id, pd.project_billable_hours,
        COALESCE(prg.code, 'UD') AS practice_id,
        prg.uuid AS practice_uuid,
        COALESCE(prg.name, 'No practice') AS practice_label,
        wc.week_start_date, wc.week_end_date, wc.year_val, wc.iso_week_number
    FROM aggregated_user_company_week agg
    LEFT JOIN project_detail pd
        ON agg.user_id = pd.user_id
       AND agg.company_id = pd.company_id
       AND agg.week_key = pd.week_key
       AND agg.source_type = 'BACKLOG'
    LEFT JOIN user u ON agg.user_id = u.uuid
    LEFT JOIN practice prg ON prg.uuid = u.practice_uuid
    LEFT JOIN week_calendar wc ON agg.week_key = wc.week_key
)
SELECT
    CONCAT(fe.user_id, '-', fe.company_id, '-', fe.week_key,
           COALESCE(CONCAT('-', fe.project_id), '')) AS forecast_staffing_id,
    fe.user_id, fe.company_id, fe.practice_id, fe.project_id, fe.client_id,
    fe.week_key, fe.week_start_date, fe.week_end_date,
    fe.year_val AS year, fe.iso_week_number,
    COALESCE(fe.project_billable_hours, fe.forecast_billable_hours) AS forecast_billable_hours,
    GREATEST(0, fe.capacity_hours - fe.forecast_billable_hours)     AS forecast_nonbillable_hours,
    COALESCE(fe.project_billable_hours, fe.forecast_billable_hours) AS forecast_total_hours,
    fe.capacity_hours, fe.gross_capacity_hours,
    fe.total_unavailable_hours AS planned_absence_hours,
    fe.planned_vacation_hours,
    fe.source_type,
    CASE WHEN fe.source_type = 'BACKLOG' THEN 100.0 ELSE NULL END AS probability_pct,
    'BI_BUDGET' AS data_source,
    CASE WHEN fe.capacity_hours > 0
         THEN ROUND(fe.forecast_billable_hours / fe.capacity_hours * 100, 2)
         ELSE NULL END AS forecast_utilization_pct,

    -- Practice dimension, additive (Phase 4): true NULL for the no-practice
    -- member, registry display name as the label.
    fe.practice_uuid,
    fe.practice_label
FROM final_enriched fe
WHERE fe.week_start_date IS NOT NULL
ORDER BY fe.week_start_date, fe.user_id, fe.company_id, fe.project_id;


-- ── 2h) pipeline snapshot procedures (source: V425 §3c) ────────────────────

DELIMITER $$

CREATE OR REPLACE PROCEDURE sp_snapshot_pipeline(IN p_snapshot_month CHAR(6))
BEGIN
    -- Delete existing snapshot for idempotency
    DELETE FROM fact_pipeline_snapshot WHERE snapshot_month = p_snapshot_month;

    INSERT INTO fact_pipeline_snapshot (
        snapshot_id, snapshot_month, opportunity_uuid, client_uuid, company_uuid,
        stage_id, practice_uuid, rate, period_months, allocation, consultant_count,
        is_extension, expected_revenue_dkk, probability_pct, weighted_pipeline_dkk
    )
    SELECT
        CONCAT(p_snapshot_month, '-', sl.uuid) AS snapshot_id,
        p_snapshot_month,
        sl.uuid,
        sl.clientuuid,
        -- Company from lead manager's userstatus (same pattern as fact_pipeline V180)
        COALESCE(
            (SELECT us.companyuuid
             FROM userstatus us
             WHERE us.useruuid = sl.leadmanager
               AND us.statusdate <= COALESCE(sl.closedate, CURDATE())
             ORDER BY us.statusdate DESC
             LIMIT 1),
            'd8894494-2fb4-4f72-9e05-e6032e6dd691'
        ) AS company_uuid,
        sl.status AS stage_id,
        preg.uuid AS practice_uuid,
        sl.rate,
        sl.period AS period_months,
        sl.allocation,
        GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid), 1
        )) AS consultant_count,
        sl.extension AS is_extension,
        -- Expected monthly revenue = rate * standard_hours * allocation% * consultants
        (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc2 WHERE slc2.leaduuid = sl.uuid), 1
        ))) AS expected_revenue_dkk,
        CASE sl.status
            WHEN 'DETECTED'    THEN 10
            WHEN 'QUALIFIED'   THEN 25
            WHEN 'SHORTLISTED' THEN 40
            WHEN 'PROPOSAL'    THEN 50
            WHEN 'NEGOTIATION' THEN 75
            ELSE 10
        END AS probability_pct,
        -- Weighted = expected * probability / 100
        (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc3 WHERE slc3.leaduuid = sl.uuid), 1
        ))) * (CASE sl.status
            WHEN 'DETECTED'    THEN 10
            WHEN 'QUALIFIED'   THEN 25
            WHEN 'SHORTLISTED' THEN 40
            WHEN 'PROPOSAL'    THEN 50
            WHEN 'NEGOTIATION' THEN 75
            ELSE 10
        END / 100.0) AS weighted_pipeline_dkk
    FROM sales_lead sl
    -- Phase 4: resolve via the maintained uuid twin (sl.practice_uuid, kept by
    -- SalesService since Phase 3) — sl.practice is dropped in Phase 5.
    LEFT JOIN practice preg ON preg.uuid = sl.practice_uuid
    WHERE sl.status NOT IN ('WON', 'LOST')
      AND sl.rate > 0
      AND sl.period > 0;
END$$


CREATE OR REPLACE PROCEDURE sp_backfill_pipeline_snapshots()
BEGIN
    DECLARE v_counter INT DEFAULT 0;
    DECLARE v_snapshot_month CHAR(6);
    DECLARE v_month_start DATE;
    DECLARE v_month_end DATE;
    DECLARE v_existing INT;

    -- Iterate from 12 months ago up to (and including) the current month
    WHILE v_counter <= 12 DO
        SET v_month_start = DATE_ADD(
            DATE(CONCAT(YEAR(CURDATE()), '-', LPAD(MONTH(CURDATE()), 2, '0'), '-01')),
            INTERVAL (v_counter - 12) MONTH
        );
        SET v_month_end   = LAST_DAY(v_month_start);
        SET v_snapshot_month = DATE_FORMAT(v_month_start, '%Y%m');

        -- Only backfill if no rows exist for this month
        SELECT COUNT(*) INTO v_existing
        FROM fact_pipeline_snapshot
        WHERE snapshot_month = v_snapshot_month;

        IF v_existing = 0 THEN
            INSERT INTO fact_pipeline_snapshot (
                snapshot_id, snapshot_month, opportunity_uuid, client_uuid,
                company_uuid, stage_id, practice_uuid, rate, period_months,
                allocation, consultant_count, is_extension,
                expected_revenue_dkk, probability_pct, weighted_pipeline_dkk
            )
            SELECT
                CONCAT(v_snapshot_month, '-', sl.uuid) AS snapshot_id,
                v_snapshot_month,
                sl.uuid,
                sl.clientuuid,
                COALESCE(
                    (SELECT us.companyuuid
                     FROM userstatus us
                     WHERE us.useruuid = sl.leadmanager
                       AND us.statusdate <= v_month_end
                     ORDER BY us.statusdate DESC
                     LIMIT 1),
                    'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                ) AS company_uuid,
                -- Determine the stage the lead was in at that point in time
                CASE
                    -- WON leads that were won AFTER this month: they were likely in NEGOTIATION
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 'NEGOTIATION'
                    -- LOST leads that were lost AFTER this month: use lost_at_stage
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN COALESCE(sl.lost_at_stage, 'DETECTED')
                    -- Still-active leads: use current status
                    ELSE sl.status
                END AS stage_id,
                preg.uuid AS practice_uuid,
                sl.rate,
                sl.period AS period_months,
                sl.allocation,
                GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid), 1
                )) AS consultant_count,
                sl.extension AS is_extension,
                -- Expected revenue
                (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc2 WHERE slc2.leaduuid = sl.uuid), 1
                ))) AS expected_revenue_dkk,
                -- Probability based on reconstructed stage
                CASE
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 75  -- NEGOTIATION probability
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN CASE COALESCE(sl.lost_at_stage, 'DETECTED')
                            WHEN 'DETECTED'    THEN 10
                            WHEN 'QUALIFIED'   THEN 25
                            WHEN 'SHORTLISTED' THEN 40
                            WHEN 'PROPOSAL'    THEN 50
                            WHEN 'NEGOTIATION' THEN 75
                            ELSE 10
                        END
                    ELSE CASE sl.status
                        WHEN 'DETECTED'    THEN 10
                        WHEN 'QUALIFIED'   THEN 25
                        WHEN 'SHORTLISTED' THEN 40
                        WHEN 'PROPOSAL'    THEN 50
                        WHEN 'NEGOTIATION' THEN 75
                        ELSE 10
                    END
                END AS probability_pct,
                -- Weighted pipeline
                (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc3 WHERE slc3.leaduuid = sl.uuid), 1
                ))) * (CASE
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 75
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN CASE COALESCE(sl.lost_at_stage, 'DETECTED')
                            WHEN 'DETECTED'    THEN 10
                            WHEN 'QUALIFIED'   THEN 25
                            WHEN 'SHORTLISTED' THEN 40
                            WHEN 'PROPOSAL'    THEN 50
                            WHEN 'NEGOTIATION' THEN 75
                            ELSE 10
                        END
                    ELSE CASE sl.status
                        WHEN 'DETECTED'    THEN 10
                        WHEN 'QUALIFIED'   THEN 25
                        WHEN 'SHORTLISTED' THEN 40
                        WHEN 'PROPOSAL'    THEN 50
                        WHEN 'NEGOTIATION' THEN 75
                        ELSE 10
                    END
                END / 100.0) AS weighted_pipeline_dkk
            FROM sales_lead sl
            -- Phase 4: resolve via the maintained uuid twin (sl.practice_uuid,
            -- kept by SalesService since Phase 3) — sl.practice is dropped in Phase 5.
            LEFT JOIN practice preg ON preg.uuid = sl.practice_uuid
            WHERE sl.created <= v_month_end
              AND sl.rate > 0
              AND sl.period > 0
              AND (
                  -- Currently active leads (not WON/LOST)
                  sl.status NOT IN ('WON', 'LOST')
                  -- WON leads that were won AFTER this month (so they were active during it)
                  OR (sl.status = 'WON' AND sl.won_date > v_month_end)
                  -- LOST leads that were lost AFTER this month
                  OR (sl.status = 'LOST' AND sl.last_updated > v_month_end)
              );
        END IF;

        SET v_counter = v_counter + 1;
    END WHILE;
END$$

DELIMITER ;


-- =============================================================================
-- 3) The operational NULL flip. ------------------------------------------------
--    In-place semantic conversion: stored 'UD' always meant "no practice", so
--    rows flip to NULL on both twins with no interval/period changes (history
--    stays contiguous — only the value changes). Each UPDATE also catches rows
--    whose uuid twin drifted to the UD registry row without the code (defensive,
--    normally 0 rows). Idempotent: after the flip neither predicate matches.

UPDATE `user` u
    LEFT JOIN practice ud ON ud.code = 'UD' AND ud.uuid = u.practice_uuid
SET u.practice = NULL,
    u.practice_uuid = NULL
WHERE u.practice = 'UD'
   OR ud.uuid IS NOT NULL;

UPDATE user_practice_history h
    LEFT JOIN practice ud ON ud.code = 'UD' AND ud.uuid = h.practice_uuid
SET h.practice = NULL,
    h.practice_uuid = NULL
WHERE h.practice = 'UD'
   OR ud.uuid IS NOT NULL;

-- last_updated is ON UPDATE CURRENT_TIMESTAMP — pin it so the semantic
-- conversion does not masquerade as a business edit on the flipped leads.
UPDATE sales_lead sl
    LEFT JOIN practice ud ON ud.code = 'UD' AND ud.uuid = sl.practice_uuid
SET sl.practice = NULL,
    sl.practice_uuid = NULL,
    sl.last_updated = sl.last_updated
WHERE sl.practice = 'UD'
   OR ud.uuid IS NOT NULL;

-- fact_pipeline_snapshot is NOT flipped: append-only history (its 'UD'-uuid
-- rows freeze exactly like the V425 JK precedent froze to NULL); it has zero
-- readers and new snapshots resolve NULL practice_uuid from the recreated
-- procedures above.
