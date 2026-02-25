-- =============================================================================
-- Migration V195: Create fact_internal_invoice_cost view
--
-- Purpose:
--   Track intercompany (INTERNAL) invoice costs at the debtor company × month
--   grain so that they can be included in EBITDA calculations on the CXO
--   Dashboard (Expected Accumulated EBITDA chart).
--
-- Business problem (Section 11 of docs/finalized/invoicing/business-rules-and-math.md):
--   When a client invoice includes work by consultants from multiple Trustworks
--   sister companies, an INTERNAL invoice is created from the cross-company
--   entity (issuer) to the company holding the client contract (debtor).
--   This cost currently falls through a gap:
--     - fact_project_financials excludes INTERNAL invoice types from revenue
--       and uses work-entry salary data for costs (not billing rates)
--     - fact_opex excludes 'Direkte omkostninger' accounts entirely
--   The result: intercompany transfer pricing costs are invisible in EBITDA.
--
-- Grain: debtor_company_id × month_key
--   Each row represents the total internal invoice cost charged to a debtor
--   company in a given calendar month, regardless of individual projects.
--
-- Status-based data source switch (anti-double-count rule):
--
--   QUEUED → invoices + invoiceitems (GL entries not yet posted to ERP)
--     The invoice is agreed and will not change; it is treated as final for
--     financial reporting even though it has not been uploaded to e-conomics.
--     Data source: invoices + invoiceitems tables.
--
--   CREATED → finance_details GL accounts (ERP has been updated)
--     Once uploaded to e-conomics, the authoritative cost data is in the GL.
--     Using both invoice data AND GL data simultaneously would double-count.
--     Data source: finance_details, accounts 3050/3055/3070/3075 (TW A/S as
--     debtor) and 1350 (subsidiaries as debtor).
--     Only positive (debit) GL amounts are costs; negative amounts are
--     reversals and are netted into the sum (SUM not ABS).
--
-- GL Account Mapping for INTERNAL invoice costs:
--   Debtor = TW A/S (d8894494-2fb4-4f72-9e05-e6032e6dd691):
--     3050 – Konsulentbistand TW TECH  (cost of Technology ApS consultants)
--     3055 – Konsulentbistand TW CYBER (cost of Cyber Security ApS consultants)
--     3070 – Administration TW TECH    (admin charges from Technology ApS)
--     3075 – Administration TW CYBER   (admin charges from Cyber Security ApS)
--   Debtor = Subsidiaries (Technology ApS, Cyber Security ApS):
--     1350 – Administration Trustworks A/S
--
-- Currency conversion:
--   QUEUED invoices only: non-DKK currencies are converted using the currences
--   table (same pattern as fact_project_financials). CREATED invoices are
--   already in DKK in finance_details (the ERP converts on posting).
--
-- Financial year convention (Trustworks):
--   July 1 → June 30
--   fiscal_year: for month >= 7 use calendar year, else calendar year - 1
--   fiscal_month_number: July = 1, August = 2, ..., June = 12
--
-- Columns:
--   internal_invoice_cost_id  – surrogate key: debtor_company_id + '-' + month_key
--   company_id                – debtor_companyuuid (the company paying the cost)
--   month_key                 – YYYYMM as VARCHAR(6)
--   year                      – calendar year
--   month_number              – calendar month 1-12
--   fiscal_year               – fiscal year start year (July FY)
--   fiscal_month_number       – 1=July … 12=June
--   internal_invoice_cost_dkk – net total internal invoice cost in DKK
--   queued_cost_dkk           – cost from QUEUED invoices (invoices table)
--   created_cost_dkk          – cost from CREATED invoices (GL finance_details)
--   entry_count               – total number of contributing rows
--   data_sources              – comma-separated list of data sources present
--
-- Dependencies:
--   invoices, invoiceitems, finance_details, currences
--
-- Idempotent: CREATE OR REPLACE VIEW — safe to re-run.
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_internal_invoice_cost` AS

WITH

-- ---------------------------------------------------------------------------
-- 1) QUEUED internal invoices: cost from invoices + invoiceitems
--    No GL entries exist yet — the invoice table is authoritative.
--    Includes currency conversion for non-DKK invoices.
-- ---------------------------------------------------------------------------
queued_costs AS (
    SELECT
        i.debtor_companyuuid                                    AS debtor_company_id,
        CONCAT(
            LPAD(YEAR(i.invoicedate), 4, '0'),
            LPAD(MONTH(i.invoicedate), 2, '0')
        )                                                       AS month_key,
        YEAR(i.invoicedate)                                     AS year_val,
        MONTH(i.invoicedate)                                    AS month_val,
        SUM(
            ii.rate * ii.hours *
            CASE
                WHEN i.currency = 'DKK' THEN 1.0
                ELSE COALESCE(
                    (
                        SELECT c.conversion
                        FROM currences c
                        WHERE c.currency = i.currency
                          AND c.month    = DATE_FORMAT(i.invoicedate, '%Y%m')
                        LIMIT 1
                    ),
                    1.0
                )
            END
        )                                                       AS cost_dkk,
        'QUEUED_INVOICE'                                        AS data_source
    FROM invoices i
    JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
    WHERE i.type                 = 'INTERNAL'
      AND i.status               = 'QUEUED'
      AND i.debtor_companyuuid   IS NOT NULL
      AND ii.rate                IS NOT NULL
      AND ii.hours               IS NOT NULL
    GROUP BY
        i.debtor_companyuuid,
        YEAR(i.invoicedate),
        MONTH(i.invoicedate)
),

-- ---------------------------------------------------------------------------
-- 2) CREATED internal invoices: cost from finance_details GL accounts
--    When uploaded to e-conomics, GL entries in finance_details become the
--    authoritative source.  Positive amounts = costs, negative = reversals.
--    We use SUM (not ABS) so that reversals correctly reduce the net cost.
--    Accounts:
--      3050, 3055, 3070, 3075 – TW A/S as debtor (interco cost accounts)
--      1350                   – Subsidiaries as debtor (admin cost from TW A/S)
--    finance_details amounts are already in DKK (ERP converts on posting).
-- ---------------------------------------------------------------------------
created_costs AS (
    SELECT
        fd.companyuuid                                          AS debtor_company_id,
        CONCAT(
            LPAD(YEAR(fd.expensedate), 4, '0'),
            LPAD(MONTH(fd.expensedate), 2, '0')
        )                                                       AS month_key,
        YEAR(fd.expensedate)                                    AS year_val,
        MONTH(fd.expensedate)                                   AS month_val,
        SUM(fd.amount)                                          AS cost_dkk,
        'CREATED_GL'                                            AS data_source
    FROM finance_details fd
    WHERE fd.expensedate       IS NOT NULL
      AND fd.amount            != 0
      AND fd.accountnumber     IN (3050, 3055, 3070, 3075, 1350)
    GROUP BY
        fd.companyuuid,
        YEAR(fd.expensedate),
        MONTH(fd.expensedate)
),

-- ---------------------------------------------------------------------------
-- 3) Union both sources
--    Each source row is tagged with data_source.
--    No overlap: QUEUED invoices never have GL entries; CREATED invoices
--    are not re-counted from the invoice table.
-- ---------------------------------------------------------------------------
combined AS (
    SELECT
        debtor_company_id,
        month_key,
        year_val,
        month_val,
        cost_dkk,
        data_source
    FROM queued_costs

    UNION ALL

    SELECT
        debtor_company_id,
        month_key,
        year_val,
        month_val,
        cost_dkk,
        data_source
    FROM created_costs
)

-- ---------------------------------------------------------------------------
-- 4) Final aggregation at debtor_company × month grain
-- ---------------------------------------------------------------------------
SELECT
    -- Surrogate key
    CONCAT(debtor_company_id, '-', month_key)               AS internal_invoice_cost_id,

    -- Dimension keys
    debtor_company_id                                        AS company_id,
    month_key,

    -- Calendar time dimensions
    year_val                                                 AS year,
    month_val                                                AS month_number,

    -- Fiscal year dimensions (July-June fiscal year)
    CASE
        WHEN month_val >= 7 THEN year_val
        ELSE year_val - 1
    END                                                      AS fiscal_year,

    CASE
        WHEN month_val >= 7 THEN month_val - 6   -- Jul=1, Aug=2, ..., Dec=6
        ELSE month_val + 6                        -- Jan=7, Feb=8, ..., Jun=12
    END                                                      AS fiscal_month_number,

    -- Metrics: total net cost in DKK
    SUM(cost_dkk)                                            AS internal_invoice_cost_dkk,

    -- Breakdown by source for transparency
    SUM(CASE WHEN data_source = 'QUEUED_INVOICE' THEN cost_dkk ELSE 0 END)
                                                             AS queued_cost_dkk,
    SUM(CASE WHEN data_source = 'CREATED_GL'     THEN cost_dkk ELSE 0 END)
                                                             AS created_cost_dkk,

    -- Diagnostics
    COUNT(*)                                                 AS entry_count,
    GROUP_CONCAT(DISTINCT data_source ORDER BY data_source)  AS data_sources

FROM combined
GROUP BY
    debtor_company_id,
    month_key,
    year_val,
    month_val;
