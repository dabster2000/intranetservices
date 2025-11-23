CREATE OR REPLACE ALGORITHM=UNDEFINED
SQL SECURITY DEFINER
VIEW `fact_project_financials` AS
WITH

-- 1) Same project-month base as before (from work)
project_months AS (
    SELECT DISTINCT
        COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
        YEAR(w.registered) AS year_val,
        MONTH(w.registered) AS month_val
    FROM `work` w
             LEFT JOIN `task` t
                       ON w.taskuuid = t.uuid
             LEFT JOIN `project` p
                       ON COALESCE(w.projectuuid, t.projectuuid) = p.uuid
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
        CASE
            WHEN i.type = 'CREDIT_NOTE' THEN -1
            ELSE 1
            END AS sign,
        (ii.rate * ii.hours *
         CASE
             WHEN i.currency = 'DKK' THEN 1
             ELSE c.conversion
             END
            ) AS line_amount_dkk
    FROM `project` p
             JOIN `invoices` i
                  ON p.uuid = i.projectuuid
             JOIN `invoiceitems` ii
                  ON i.uuid = ii.invoiceuuid
             LEFT JOIN `currences` c
                       ON c.currency = i.currency
                           AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m')
    WHERE i.status = 'CREATED'
      AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE') -- no INTERNAL / INTERNAL_SERVICE
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
    GROUP BY
        ilc.project_uuid,
        ilc.year_val,
        ilc.month_val,
        ilc.companyuuid
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
                      LEFT JOIN `task` t
                                ON w.taskuuid = t.uuid
                      JOIN `user` u
                           ON w.useruuid = u.uuid
             WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
               AND u.type = 'USER'
               AND w.workduration > 0
               AND w.registered IS NOT NULL
         ) wc
    GROUP BY
        wc.project_uuid,
        wc.year_val,
        wc.month_val,
        wc.companyuuid
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
                      LEFT JOIN `task` t
                                ON w.taskuuid = t.uuid
                      JOIN `user` u
                           ON w.useruuid = u.uuid
             WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
               AND u.type IN ('EXTERNAL', 'CONSULTANT')
               AND w.workduration > 0
               AND w.registered IS NOT NULL
         ) ec
    GROUP BY
        ec.project_uuid,
        ec.year_val,
        ec.month_val,
        ec.companyuuid
),

-- 6) Project expenses assigned to main company per project-month
project_expense_aggregation AS (
    SELECT
        e.projectuuid AS project_uuid,
        YEAR(e.expensedate) AS year_val,
        MONTH(e.expensedate) AS month_val,
        'd8894494-2fb4-4f72-9e05-e6032e6dd691' AS companyuuid,
        SUM(e.amount) AS expense_cost_dkk
    FROM `expenses` e
    WHERE e.projectuuid IS NOT NULL
      AND e.status IN ('VERIFIED_BOOKED','VERIFIED_UNBOOKED')
      AND e.expensedate IS NOT NULL
    GROUP BY
        e.projectuuid,
        YEAR(e.expensedate),
        MONTH(e.expensedate)
),

-- 7) Total cost per project-month-company (same union pattern as old view, but with companyuuid)
total_cost_aggregation AS (
    -- a) rows where we have work cost (possibly with external & expenses)
    SELECT
        COALESCE(wca.project_uuid, eca.project_uuid, pea.project_uuid) AS project_uuid,
        COALESCE(wca.year_val, eca.year_val, pea.year_val) AS year_val,
        COALESCE(wca.month_val, eca.month_val, pea.month_val) AS month_val,
        COALESCE(wca.companyuuid, eca.companyuuid, pea.companyuuid) AS companyuuid,
        COALESCE(wca.employee_salary_cost_dkk, 0) AS employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) AS external_cost_dkk,
        COALESCE(pea.expense_cost_dkk, 0) AS expense_cost_dkk,
        COALESCE(wca.employee_salary_cost_dkk, 0)
            + COALESCE(eca.external_cost_dkk, 0)
            + COALESCE(pea.expense_cost_dkk, 0) AS total_cost_dkk,
        COALESCE(wca.total_hours, 0) AS total_hours,
        COALESCE(wca.consultant_count, 0) AS consultant_count
    FROM work_cost_aggregation wca
             LEFT JOIN external_consultant_cost_aggregation eca
                       ON wca.project_uuid = eca.project_uuid
                           AND wca.year_val = eca.year_val
                           AND wca.month_val = eca.month_val
                           AND wca.companyuuid = eca.companyuuid
             LEFT JOIN project_expense_aggregation pea
                       ON wca.project_uuid = pea.project_uuid
                           AND wca.year_val = pea.year_val
                           AND wca.month_val = pea.month_val
                           AND wca.companyuuid = pea.companyuuid

    UNION

    -- b) rows where we have external cost (and maybe expenses) but no work cost
    SELECT
        COALESCE(eca.project_uuid, pea.project_uuid) AS project_uuid,
        COALESCE(eca.year_val, pea.year_val) AS year_val,
        COALESCE(eca.month_val, pea.month_val) AS month_val,
        COALESCE(eca.companyuuid, pea.companyuuid) AS companyuuid,
        0 AS employee_salary_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0) AS external_cost_dkk,
        COALESCE(pea.expense_cost_dkk, 0) AS expense_cost_dkk,
        COALESCE(eca.external_cost_dkk, 0)
            + COALESCE(pea.expense_cost_dkk, 0) AS total_cost_dkk,
        0 AS total_hours,
        0 AS consultant_count
    FROM external_consultant_cost_aggregation eca
             LEFT JOIN project_expense_aggregation pea
                       ON eca.project_uuid = pea.project_uuid
                           AND eca.year_val = pea.year_val
                           AND eca.month_val = pea.month_val
                           AND eca.companyuuid = pea.companyuuid
    WHERE NOT EXISTS (
        SELECT 1
        FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = eca.project_uuid
          AND wca2.year_val = eca.year_val
          AND wca2.month_val = eca.month_val
          AND wca2.companyuuid = eca.companyuuid
        LIMIT 1
    )

    UNION

    -- c) rows where we only have expenses
    SELECT
        pea.project_uuid,
        pea.year_val,
        pea.month_val,
        pea.companyuuid,
        0 AS employee_salary_cost_dkk,
        0 AS external_cost_dkk,
        pea.expense_cost_dkk AS expense_cost_dkk,
        pea.expense_cost_dkk AS total_cost_dkk,
        0 AS total_hours,
        0 AS consultant_count
    FROM project_expense_aggregation pea
    WHERE NOT EXISTS (
        SELECT 1
        FROM work_cost_aggregation wca2
        WHERE wca2.project_uuid = pea.project_uuid
          AND wca2.year_val = pea.year_val
          AND wca2.month_val = pea.month_val
          AND wca2.companyuuid = pea.companyuuid
        LIMIT 1
    )
      AND NOT EXISTS (
        SELECT 1
        FROM external_consultant_cost_aggregation eca2
        WHERE eca2.project_uuid = pea.project_uuid
          AND eca2.year_val = pea.year_val
          AND eca2.month_val = pea.month_val
          AND eca2.companyuuid = pea.companyuuid
        LIMIT 1
    )
),

-- 8) Same service line logic as before (per project-month, no company split)
service_line_ranking AS (
    SELECT
        COALESCE(w.projectuuid, t.projectuuid) AS project_uuid,
        YEAR(w.registered) AS year_val,
        MONTH(w.registered) AS month_val,
        u.primaryskilltype AS primaryskilltype,
        SUM(w.workduration) AS hours_by_skilltype,
        ROW_NUMBER() OVER (
            PARTITION BY COALESCE(w.projectuuid, t.projectuuid),
                YEAR(w.registered),
                MONTH(w.registered)
            ORDER BY SUM(w.workduration) DESC,
                COUNT(DISTINCT w.useruuid) DESC
            ) AS skill_rank
    FROM `work` w
             LEFT JOIN `task` t
                       ON w.taskuuid = t.uuid
             JOIN `user` u
                  ON w.useruuid = u.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND u.type = 'USER'
      AND u.primaryskilltype IS NOT NULL
      AND w.workduration > 0
    GROUP BY
        COALESCE(w.projectuuid, t.projectuuid),
        YEAR(w.registered),
        MONTH(w.registered),
        u.primaryskilltype
),

dominant_service_line AS (
    SELECT
        slr.project_uuid,
        slr.year_val,
        slr.month_val,
        slr.primaryskilltype AS dominant_skilltype
    FROM service_line_ranking slr
    WHERE slr.skill_rank = 1
),

-- 9) Same contract type logic as before
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
             JOIN `contract_project` cp
                  ON p.uuid = cp.projectuuid
             JOIN `contracts` c
                  ON cp.contractuuid = c.uuid
    WHERE c.contracttype IS NOT NULL
      AND c.status IN ('SIGNED','TIME','BUDGET')
    GROUP BY
        p.uuid,
        c.contracttype
),

primary_contract_type AS (
    SELECT
        pct.project_uuid,
        pct.contracttype
    FROM project_contract_types pct
    WHERE pct.rank_num = 1
),

-- 10) Final project-month-company key set
project_month_companies AS (
    -- All project-month-company combinations with revenue
    SELECT DISTINCT
        pm.project_uuid,
        pm.year_val,
        pm.month_val,
        rbc.companyuuid
    FROM project_months pm
             JOIN revenue_by_company rbc
                  ON pm.project_uuid = rbc.project_uuid
                      AND pm.year_val = rbc.year_val
                      AND pm.month_val = rbc.month_val

    UNION

    -- All project-month-company combinations with cost
    SELECT DISTINCT
        pm.project_uuid,
        pm.year_val,
        pm.month_val,
        tca.companyuuid
    FROM project_months pm
             JOIN total_cost_aggregation tca
                  ON pm.project_uuid = tca.project_uuid
                      AND pm.year_val = tca.year_val
                      AND pm.month_val = tca.month_val

    UNION

    -- Project-months with work but no revenue or cost: assign to main company
    SELECT
        pm.project_uuid,
        pm.year_val,
        pm.month_val,
        'd8894494-2fb4-4f72-9e05-e6032e6dd691' AS companyuuid
    FROM project_months pm
    WHERE NOT EXISTS (
        SELECT 1
        FROM revenue_by_company rbc
        WHERE rbc.project_uuid = pm.project_uuid
          AND rbc.year_val = pm.year_val
          AND rbc.month_val = pm.month_val
    )
      AND NOT EXISTS (
        SELECT 1
        FROM total_cost_aggregation tca
        WHERE tca.project_uuid = pm.project_uuid
          AND tca.year_val = pm.year_val
          AND tca.month_val = pm.month_val
    )
)

SELECT
    CONCAT(
            pmc.project_uuid,
            '-',
            CONCAT(LPAD(pmc.year_val, 4, '0'), LPAD(pmc.month_val, 2, '0'))
    ) AS project_financial_id,
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
    'OPERATIONAL' AS data_source
FROM project_month_companies pmc
         JOIN `project` p
              ON pmc.project_uuid = p.uuid
         LEFT JOIN `client` c
                   ON p.clientuuid = c.uuid
         LEFT JOIN revenue_by_company rbc
                   ON pmc.project_uuid = rbc.project_uuid
                       AND pmc.year_val = rbc.year_val
                       AND pmc.month_val = rbc.month_val
                       AND pmc.companyuuid = rbc.companyuuid
         LEFT JOIN total_cost_aggregation tca
                   ON pmc.project_uuid = tca.project_uuid
                       AND pmc.year_val = tca.year_val
                       AND pmc.month_val = tca.month_val
                       AND pmc.companyuuid = tca.companyuuid
         LEFT JOIN dominant_service_line dsl
                   ON pmc.project_uuid = dsl.project_uuid
                       AND pmc.year_val = dsl.year_val
                       AND pmc.month_val = dsl.month_val
         LEFT JOIN primary_contract_type pct
                   ON pmc.project_uuid = pct.project_uuid
ORDER BY
    pmc.year_val DESC,
    pmc.month_val DESC,
    pmc.project_uuid,
    pmc.companyuuid;
