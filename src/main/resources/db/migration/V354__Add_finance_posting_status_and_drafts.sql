-- Add booked/draft source grain to finance imports and OPEX facts.

ALTER TABLE finance_details
    ADD COLUMN IF NOT EXISTS postingstatus VARCHAR(20) NOT NULL DEFAULT 'BOOKED',
    ADD COLUMN IF NOT EXISTS journalnumber INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS vouchernumber INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS objectversion VARCHAR(80) NULL,
    ADD COLUMN IF NOT EXISTS entrytypenumber INT NULL,
    ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NULL,
    ADD COLUMN IF NOT EXISTS exchangerate DOUBLE NULL;

ALTER TABLE finances
    ADD COLUMN IF NOT EXISTS postingstatus VARCHAR(20) NOT NULL DEFAULT 'BOOKED';

CREATE INDEX IF NOT EXISTS idx_finances_period_type_status
    ON finances (period, expensetype, postingstatus);

DROP INDEX IF EXISTS uq_fd_logical_key ON finance_details;

DELETE fd
FROM finance_details fd
INNER JOIN (
    SELECT companyuuid,
           postingstatus,
           journalnumber,
           vouchernumber,
           entrynumber,
           accountnumber,
           amount,
           expensedate,
           MIN(id) AS keep_id
    FROM finance_details
    GROUP BY companyuuid, postingstatus, journalnumber, vouchernumber,
             entrynumber, accountnumber, amount, expensedate
    HAVING COUNT(*) > 1
) keepers
    ON  fd.companyuuid    = keepers.companyuuid
    AND fd.postingstatus  = keepers.postingstatus
    AND fd.journalnumber  = keepers.journalnumber
    AND fd.vouchernumber  = keepers.vouchernumber
    AND fd.entrynumber    = keepers.entrynumber
    AND fd.accountnumber  = keepers.accountnumber
    AND fd.amount         = keepers.amount
    AND fd.expensedate    = keepers.expensedate
    AND fd.id            != keepers.keep_id;

ALTER TABLE finance_details
    ADD UNIQUE INDEX IF NOT EXISTS uq_fd_logical_key
        (companyuuid, postingstatus, journalnumber, vouchernumber,
         entrynumber, accountnumber, amount, expensedate);

CREATE INDEX IF NOT EXISTS idx_fd_status_expensedate
    ON finance_details (postingstatus, expensedate);

CREATE INDEX IF NOT EXISTS idx_fd_company_status_account_date
    ON finance_details (companyuuid, postingstatus, accountnumber, expensedate);

ALTER TABLE fact_opex_mat
    ADD COLUMN IF NOT EXISTS posting_status VARCHAR(20)
        GENERATED ALWAYS AS (
            CASE
                WHEN SUBSTRING_INDEX(opex_id, '-', 1) = 'DRAFT' THEN 'DRAFT'
                ELSE 'BOOKED'
            END
        ) STORED;

CREATE INDEX IF NOT EXISTS idx_fom_posting_status_month
    ON fact_opex_mat (posting_status, month_key);

ALTER TABLE fact_opex_distribution_mat
    ADD COLUMN IF NOT EXISTS posting_status VARCHAR(20)
        GENERATED ALWAYS AS (
            CASE
                WHEN SUBSTRING_INDEX(opex_distribution_id, '-', 1) = 'DRAFT' THEN 'DRAFT'
                ELSE 'BOOKED'
            END
        ) STORED;

CREATE INDEX IF NOT EXISTS idx_fodm_posting_status_month
    ON fact_opex_distribution_mat (posting_status, month_key);

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_opex` AS
WITH
category_mapping AS (
    SELECT
        ac.uuid AS category_uuid,
        ac.groupname,
        CASE
            WHEN ac.groupname = 'Delte services' THEN 'PEOPLE_NON_BILLABLE'
            WHEN ac.groupname = 'Salgsfremmende omkostninger' THEN 'SALES_MARKETING'
            WHEN ac.groupname = 'Lokaleomkostninger' THEN 'OFFICE_FACILITIES'
            WHEN ac.groupname = 'Variable omkostninger' THEN 'TOOLS_SOFTWARE'
            WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'OTHER_OPEX'
            ELSE 'OTHER_OPEX'
        END AS expense_category_id,
        CASE
            WHEN ac.groupname = 'Delte services' THEN 'HR_ADMIN'
            WHEN ac.groupname = 'Salgsfremmende omkostninger' THEN 'SALES'
            WHEN ac.groupname = 'Lokaleomkostninger' THEN 'FACILITIES'
            WHEN ac.groupname = 'Variable omkostninger' THEN 'INTERNAL_IT'
            WHEN ac.groupname = 'Øvrige administrationsomk. i alt' THEN 'ADMIN'
            ELSE 'GENERAL'
        END AS cost_center_id
    FROM accounting_categories ac
    WHERE ac.groupname IS NOT NULL
),
gl_opex_transactions AS (
    SELECT
        fd.companyuuid AS company_uuid,
        COALESCE(fd.postingstatus, 'BOOKED') AS posting_status,
        fd.accountnumber,
        fd.amount,
        fd.expensedate,
        aa.categoryuuid,
        aa.salary AS is_salary_account,
        aa.cost_type,
        YEAR(fd.expensedate) AS year_val,
        MONTH(fd.expensedate) AS month_val
    FROM finance_details fd
    INNER JOIN accounting_accounts aa
        ON  fd.accountnumber = aa.account_code
        AND fd.companyuuid   = aa.companyuuid
    WHERE fd.expensedate IS NOT NULL
      AND fd.amount != 0
      AND aa.cost_type IN ('OPEX', 'SALARIES')
),
opex_aggregated AS (
    SELECT
        gl.company_uuid,
        gl.posting_status,
        COALESCE(cm.cost_center_id, 'GENERAL') AS cost_center_id,
        COALESCE(cm.expense_category_id, 'OTHER_OPEX') AS expense_category_id,
        gl.cost_type,
        gl.year_val,
        gl.month_val,
        SUM(ABS(gl.amount)) AS opex_amount_dkk,
        COUNT(*) AS invoice_count,
        MAX(CAST(gl.is_salary_account AS UNSIGNED)) AS is_payroll_flag
    FROM gl_opex_transactions gl
    LEFT JOIN category_mapping cm ON gl.categoryuuid = cm.category_uuid
    GROUP BY
        gl.company_uuid,
        gl.posting_status,
        cm.cost_center_id,
        cm.expense_category_id,
        gl.cost_type,
        gl.year_val,
        gl.month_val
),
fte_practice_weights AS (
    SELECT
        company_id,
        practice_id,
        month_key,
        fte_billable,
        SUM(fte_billable) OVER (PARTITION BY company_id, month_key) AS company_total_fte
    FROM fact_employee_monthly_mat
    WHERE role_type = 'BILLABLE'
      AND fte_billable > 0
),
salary_practice_weights AS (
    SELECT
        companyuuid AS company_id,
        practice_id,
        month_key,
        SUM(salary_sum) AS practice_salary_sum,
        SUM(SUM(salary_sum)) OVER (PARTITION BY companyuuid, month_key) AS company_total_salary
    FROM fact_salary_monthly
    WHERE salary_sum > 0
    GROUP BY companyuuid, practice_id, month_key
)
SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-', fw.practice_id, '-',
           oa.cost_center_id, '-', oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    fw.practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    ROUND(oa.opex_amount_dkk * (fw.fte_billable / fw.company_total_fte), 2) AS opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
JOIN fte_practice_weights fw
    ON  fw.company_id = oa.company_uuid
    AND fw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
WHERE oa.cost_type = 'OPEX'
  AND oa.opex_amount_dkk > 0

UNION ALL

SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-', sw.practice_id, '-',
           oa.cost_center_id, '-', oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    sw.practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    ROUND(oa.opex_amount_dkk * (sw.practice_salary_sum / sw.company_total_salary), 2) AS opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
JOIN salary_practice_weights sw
    ON  sw.company_id = oa.company_uuid
    AND sw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
WHERE oa.cost_type = 'SALARIES'
  AND oa.opex_amount_dkk > 0

UNION ALL

SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-ALL-', oa.cost_center_id, '-',
           oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    NULL AS practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
WHERE oa.cost_type = 'OPEX'
  AND oa.opex_amount_dkk > 0
  AND NOT EXISTS (
      SELECT 1 FROM fte_practice_weights fw
      WHERE fw.company_id = oa.company_uuid
        AND fw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
  )

UNION ALL

SELECT
    CONCAT(oa.posting_status, '-', oa.company_uuid, '-ALL-', oa.cost_center_id, '-',
           oa.expense_category_id, '-', oa.cost_type, '-',
           LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS opex_id,
    oa.company_uuid AS company_id,
    oa.cost_center_id,
    oa.expense_category_id,
    oa.cost_type,
    NULL AS expense_subcategory_id,
    NULL AS practice_id,
    NULL AS sector_id,
    CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0')) AS month_key,
    oa.year_val AS year,
    oa.month_val AS month_number,
    CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END AS fiscal_year,
    CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END AS fiscal_month_number,
    CONCAT('FY', CASE WHEN oa.month_val >= 7 THEN oa.year_val ELSE oa.year_val - 1 END, '-',
           LPAD(CASE WHEN oa.month_val >= 7 THEN oa.month_val - 6 ELSE oa.month_val + 6 END, 2, '0')) AS fiscal_month_key,
    oa.opex_amount_dkk,
    oa.invoice_count,
    oa.is_payroll_flag,
    'ERP_GL' AS data_source,
    oa.posting_status
FROM opex_aggregated oa
WHERE oa.cost_type = 'SALARIES'
  AND oa.opex_amount_dkk > 0
  AND NOT EXISTS (
      SELECT 1 FROM salary_practice_weights sw
      WHERE sw.company_id = oa.company_uuid
        AND sw.month_key  = CONCAT(LPAD(oa.year_val, 4, '0'), LPAD(oa.month_val, 2, '0'))
  );

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

CREATE OR REPLACE VIEW v_finance_details_logical AS
SELECT
    fd.id,
    fd.companyuuid,
    fd.entrynumber,
    fd.accountnumber,
    CASE
        WHEN fd.accountnumber = 1010
         AND fd.companyuuid IN (
             '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
             'e4b0a2a4-0963-4153-b0a2-a409637153a2'
         )
         AND inv.cvr = '35648941'
        THEN 1040
        ELSE fd.accountnumber
    END AS logical_accountnumber,
    CASE
        WHEN fd.accountnumber = 1010
         AND fd.companyuuid IN (
             '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
             'e4b0a2a4-0963-4153-b0a2-a409637153a2'
         )
         AND inv.cvr = '35648941'
        THEN 1 ELSE 0
    END AS is_reclassified,
    fd.invoicenumber,
    fd.amount,
    fd.remainder,
    fd.expensedate,
    fd.text,
    fd.postingstatus,
    fd.journalnumber,
    fd.vouchernumber,
    fd.objectversion,
    fd.entrytypenumber,
    fd.currency,
    fd.exchangerate
FROM finance_details fd
LEFT JOIN invoices inv
    ON inv.invoicenumber = fd.invoicenumber
   AND inv.companyuuid   = fd.companyuuid;
