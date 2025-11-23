CREATE OR REPLACE VIEW fact_project_financials AS

WITH project_months AS (
    -- Step 1: Get all distinct project-month combinations from work entries
    -- CRITICAL: Handle both old path (work.projectuuid) and new path (work→task→project)
    -- Pre-2024: work.projectuuid populated directly
    -- Post-2024: work.projectuuid NULL, must use task.projectuuid
    SELECT DISTINCT
        COALESCE(w.projectuuid, t.projectuuid) as project_uuid,
        YEAR(w.registered) as year_val,
        MONTH(w.registered) as month_val
    FROM work w
             LEFT JOIN task t ON w.taskuuid = t.uuid
             LEFT JOIN project p ON COALESCE(w.projectuuid, t.projectuuid) = p.uuid
    WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
      AND p.uuid IS NOT NULL
      AND w.registered IS NOT NULL
      AND w.workduration > 0
),

     customer_revenue AS (
         -- External customer revenue (gross)
         SELECT
             p.uuid as project_uuid,
             YEAR(i.invoicedate) as year_val,
             MONTH(i.invoicedate) as month_val,
             SUM(
                     ii.rate * ii.hours *
                     CASE
                         WHEN i.currency = 'DKK' THEN 1
                         ELSE c.conversion
                         END
             )  as total_revenue_dkk,
             COUNT(DISTINCT i.uuid) as invoice_count,
             'CUSTOMER' as revenue_type
         FROM project p
                  INNER JOIN invoices i ON p.uuid = i.projectuuid
                  INNER JOIN invoiceitems ii ON i.uuid = ii.invoiceuuid
                  LEFT JOIN currences c ON c.currency = i.currency AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m')
         WHERE i.status = 'CREATED'
           AND i.type IN ('INVOICE', 'PHANTOM')
           AND ii.rate IS NOT NULL
           AND ii.hours IS NOT NULL
           AND (i.currency = 'DKK' OR c.uuid IS NOT NULL)
         GROUP BY p.uuid, YEAR(i.invoicedate), MONTH(i.invoicedate)
     ),

     credit_notes AS (
         -- Customer refunds/corrections (negative)
         SELECT
             p.uuid as project_uuid,
             YEAR(i.invoicedate) as year_val,
             MONTH(i.invoicedate) as month_val,
             -SUM(
                     ii.rate * ii.hours *
                     CASE
                         WHEN i.currency = 'DKK' THEN 1
                         ELSE c.conversion
                         END
              )  as total_revenue_dkk,
             COUNT(DISTINCT i.uuid) as invoice_count,
             'CREDIT_NOTE' as revenue_type
         FROM project p
                  INNER JOIN invoices i ON p.uuid = i.projectuuid
                  INNER JOIN invoiceitems ii ON i.uuid = ii.invoiceuuid
                  LEFT JOIN currences c ON c.currency = i.currency AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m')
         WHERE i.status = 'CREATED'
           AND i.type = 'CREDIT_NOTE'
           AND ii.rate IS NOT NULL
           AND ii.hours IS NOT NULL
           AND (i.currency = 'DKK' OR c.uuid IS NOT NULL)
         GROUP BY p.uuid, YEAR(i.invoicedate), MONTH(i.invoicedate)
     ),

     revenue_aggregation AS (
         -- Combine for net customer revenue
         SELECT
             project_uuid,
             year_val,
             month_val,
             SUM(total_revenue_dkk) as total_revenue_dkk,
             SUM(invoice_count) as invoice_count
         FROM (
                  SELECT * FROM customer_revenue
                  UNION ALL
                  SELECT * FROM credit_notes
              ) combined
         GROUP BY project_uuid, year_val, month_val
     ),

     work_cost_aggregation AS (
         SELECT
             COALESCE(w.projectuuid, t.projectuuid) as project_uuid,
             YEAR(w.registered) as year_val,
             MONTH(w.registered) as month_val,
             SUM(w.workduration * (COALESCE(sal.salary, 0) / 160.0)) as total_cost_dkk,
             SUM(w.workduration) as total_hours,
             COUNT(DISTINCT w.useruuid) as consultant_count
         FROM work w
                  LEFT JOIN task t ON w.taskuuid = t.uuid
                  INNER JOIN user u ON w.useruuid = u.uuid
                  LEFT JOIN salary sal ON w.useruuid = sal.useruuid
         WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
           AND u.type = 'USER'
           AND w.workduration > 0
           AND w.registered IS NOT NULL
         GROUP BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered)
     ),

     service_line_ranking AS (
         -- Step 4: Rank service lines by total work hours per project-month
         -- Determines which skill type (PM, BA, SA, DEV, CYB) had most hours
         -- CRITICAL: Handle both old path (work.projectuuid) and new path (work→task→project)
         SELECT
             COALESCE(w.projectuuid, t.projectuuid) as project_uuid,
             YEAR(w.registered) as year_val,
             MONTH(w.registered) as month_val,
             u.primaryskilltype,
             SUM(w.workduration) as hours_by_skilltype,
             ROW_NUMBER() OVER (
                 PARTITION BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered)
                 ORDER BY SUM(w.workduration) DESC, COUNT(DISTINCT w.useruuid) DESC
                 ) as skill_rank
         FROM work w
                  LEFT JOIN task t ON w.taskuuid = t.uuid
                  INNER JOIN user u ON w.useruuid = u.uuid
         WHERE COALESCE(w.projectuuid, t.projectuuid) IS NOT NULL
           AND u.type = 'USER'
           AND u.primaryskilltype IS NOT NULL
           AND w.workduration > 0
         GROUP BY COALESCE(w.projectuuid, t.projectuuid), YEAR(w.registered), MONTH(w.registered), u.primaryskilltype
     ),

     dominant_service_line AS (
         -- Step 5: Extract only the dominant (rank #1) service line per project-month
         SELECT
             project_uuid,
             year_val,
             month_val,
             primaryskilltype as dominant_skilltype
         FROM service_line_ranking
         WHERE skill_rank = 1
     ),

     project_contract_types AS (
         -- Step 6: Get contract types from contracts table via contract_project junction
         -- CRITICAL FIX: Use authoritative contracts table, not invoices.contract_type
         -- Ranks by frequency (count of contracts) per project
         SELECT
             p.uuid as project_uuid,
             c.contracttype,
             COUNT(*) as contract_count,
             ROW_NUMBER() OVER (
                 PARTITION BY p.uuid
                 ORDER BY COUNT(*) DESC
                 ) as rank_num
         FROM project p
                  INNER JOIN contract_project cp ON p.uuid = cp.projectuuid
                  INNER JOIN contracts c ON cp.contractuuid = c.uuid
         WHERE c.contracttype IS NOT NULL
           AND c.status IN ('SIGNED', 'TIME', 'BUDGET')
         GROUP BY p.uuid, c.contracttype
     ),

     primary_contract_type AS (
         -- Step 7: Keep only the most common contract type per project
         SELECT project_uuid, contracttype
         FROM project_contract_types
         WHERE rank_num = 1
     )

-- Step 8: Final aggregation - combine all dimensions and metrics
SELECT
    CONCAT(pm.project_uuid, '-', CONCAT(LPAD(pm.year_val, 4, '0'), LPAD(pm.month_val, 2, '0'))) as project_financial_id,
    pm.project_uuid as project_id,
    p.clientuuid as client_id,
    COALESCE(c.segment, 'OTHER') as sector_id,
    COALESCE(dsl.dominant_skilltype, 'UNKNOWN') as service_line_id,
    COALESCE(pct.contracttype, 'PERIOD') as contract_type_id,
    CONCAT(LPAD(pm.year_val, 4, '0'), LPAD(pm.month_val, 2, '0')) as month_key,
    pm.year_val as year,
    pm.month_val as month_number,
    COALESCE(ra.total_revenue_dkk, 0) as recognized_revenue_dkk,
    COALESCE(wca.total_cost_dkk, 0) as direct_delivery_cost_dkk,
    COALESCE(wca.total_hours, 0) as total_hours,
    COALESCE(wca.consultant_count, 0) as consultant_count,
    'OPERATIONAL' as data_source
FROM project_months pm
         INNER JOIN project p ON pm.project_uuid = p.uuid
         LEFT JOIN client c ON p.clientuuid = c.uuid
         LEFT JOIN revenue_aggregation ra
                   ON pm.project_uuid = ra.project_uuid
                       AND pm.year_val = ra.year_val
                       AND pm.month_val = ra.month_val
         LEFT JOIN work_cost_aggregation wca
                   ON pm.project_uuid = wca.project_uuid
                       AND pm.year_val = wca.year_val
                       AND pm.month_val = wca.month_val
         LEFT JOIN dominant_service_line dsl
                   ON pm.project_uuid = dsl.project_uuid
                       AND pm.year_val = dsl.year_val
                       AND pm.month_val = dsl.month_val
         LEFT JOIN primary_contract_type pct
                   ON pm.project_uuid = pct.project_uuid
ORDER BY pm.year_val DESC, pm.month_val DESC, pm.project_uuid;