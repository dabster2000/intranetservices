-- =============================================================================
-- Migration V198: Create fact_intercompany_settlement view
--
-- Purpose:
--   Track the settlement status between Trustworks sister companies by
--   combining the EXPECTED intercompany charges (from the distribution
--   algorithm) with ACTUAL settlement invoices (INTERNAL_SERVICE type).
--
-- Grain: payer_company × receiver_company × month
--   Each row represents the expected amount, actual invoiced amount, and the
--   resulting gap for one payer-receiver pair in one calendar month.
--
-- Business Context:
--   When Trustworks A/S pays shared expenses (rent, shared salaries, etc.) on
--   behalf of all sister companies, the other companies owe a proportional
--   share.  INTERNAL_SERVICE invoices are raised monthly to settle these
--   obligations.  This view surfaces the reconciliation:
--     - expected_amount: calculated via fact_operating_cost_distribution (V197)
--     - actual_amount:   from invoices of type 'INTERNAL_SERVICE' (non-DRAFT)
--     - settlement_gap:  expected − actual (negative = overpaid)
--     - settlement_status:
--         PENDING     – no invoice raised yet
--         SETTLED     – invoice amount within DKK 100 of expected
--         PARTIAL     – invoice exists but gap > DKK 100
--         UNEXPECTED  – invoice exists with no matching expected distribution
--
-- Data Sources:
--   - fact_operating_cost_distribution (V197 view): expected intercompany charges
--   - invoices: INTERNAL_SERVICE invoices; companyuuid = receiver (issuer), clientname = payer (text)
--   - companies: maps clientname → company UUID for payer identification
--   - invoiceitems: line amounts (hours × rate) for each invoice
--
-- MariaDB 10 Compatibility:
--   MariaDB 10 does not support FULL OUTER JOIN.
--   This view uses the LEFT JOIN UNION ALL ... WHERE IS NULL pattern to
--   simulate a full outer join between expected_settlement and actual_settlement.
--   Block 1: expected rows with optional matching actual (LEFT JOIN)
--   Block 2: actual rows that have no matching expected row (RIGHT side only)
--
-- Key Output Columns:
--   payer_company       – UUID of the company that owes the payment
--   receiver_company    – UUID of the company that is owed the payment
--   year_val / month_val – calendar year and month
--   month_key           – YYYYMM string for easy range filtering
--   expected_amount     – sum of intercompany_owe from distribution view
--   actual_amount       – sum of invoice line amounts (hours × rate)
--   settlement_gap      – expected_amount − actual_amount
--   invoice_count       – number of invoice lines contributing to actual_amount
--   settlement_status   – PENDING / SETTLED / PARTIAL / UNEXPECTED
--
-- Settlement Threshold:
--   A gap < DKK 100 (absolute value) is treated as SETTLED to accommodate
--   minor rounding differences between the distribution algorithm and
--   manual invoice amounts.
--
-- FY Convention (Trustworks):
--   July 1 → June 30.  fiscal_year: month >= 7 uses calendar year, else year - 1.
--
-- Dependencies:
--   fact_operating_cost_distribution (V197), invoices, invoice_items
--
-- Idempotent: CREATE OR REPLACE VIEW — safe to re-run.
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_intercompany_settlement` AS

WITH

-- ---------------------------------------------------------------------------
-- 1) expected_settlement: aggregate intercompany_owe from distribution view
--    per payer_company × receiver_company (= origin_company) × month.
--    Only rows where intercompany_owe > 0 represent real obligations.
-- ---------------------------------------------------------------------------
expected_settlement AS (
    SELECT
        focd.payer_company,
        focd.origin_company                             AS receiver_company,
        focd.year_val,
        focd.month_val,
        focd.month_key,
        SUM(focd.intercompany_owe)                      AS expected_amount
    FROM fact_operating_cost_distribution focd
    WHERE focd.intercompany_owe > 0
    GROUP BY
        focd.payer_company,
        focd.origin_company,
        focd.year_val,
        focd.month_val,
        focd.month_key
),

-- ---------------------------------------------------------------------------
-- 2) actual_settlement: aggregate INTERNAL_SERVICE invoice line amounts
--    per payer × receiver × invoice month.
--    For INTERNAL_SERVICE invoices:
--      - companyuuid = the sender (company issuing the invoice = receiver of payment)
--      - clientname  = the payer company name (text, joined to companies.name for UUID)
--    Excludes DRAFT invoices — only invoices that have been committed.
--    invoiceitems.hours × invoiceitems.rate gives the line amount.
-- ---------------------------------------------------------------------------
actual_settlement AS (
    SELECT
        c.uuid                                          AS payer_company,
        i.companyuuid                                   AS receiver_company,
        YEAR(i.invoicedate)                             AS year_val,
        MONTH(i.invoicedate)                            AS month_val,
        CONCAT(
            LPAD(YEAR(i.invoicedate),  4, '0'),
            LPAD(MONTH(i.invoicedate), 2, '0')
        )                                               AS month_key,
        SUM(ii.hours * ii.rate)                         AS actual_amount,
        COUNT(*)                                        AS invoice_count
    FROM invoices i
    JOIN invoiceitems ii
        ON i.uuid = ii.invoiceuuid
    JOIN companies c
        ON c.name = i.clientname
    WHERE i.type   = 'INTERNAL_SERVICE'
      AND i.status != 'DRAFT'
    GROUP BY
        c.uuid,
        i.companyuuid,
        YEAR(i.invoicedate),
        MONTH(i.invoicedate)
)

-- ---------------------------------------------------------------------------
-- 3a) Left side: expected rows with an optional matching actual row.
--     Covers: PENDING (no actual), SETTLED, PARTIAL.
-- ---------------------------------------------------------------------------
SELECT
    e.payer_company,
    e.receiver_company,
    e.year_val,
    e.month_val,
    e.month_key,

    -- Fiscal year dimensions (July–June fiscal year)
    CASE
        WHEN e.month_val >= 7 THEN e.year_val
        ELSE e.year_val - 1
    END                                                 AS fiscal_year,

    CASE
        WHEN e.month_val >= 7 THEN e.month_val - 6     -- Jul=1, ..., Dec=6
        ELSE e.month_val + 6                            -- Jan=7, ..., Jun=12
    END                                                 AS fiscal_month_number,

    -- Metrics
    e.expected_amount,
    COALESCE(a.actual_amount, 0)                        AS actual_amount,
    e.expected_amount - COALESCE(a.actual_amount, 0)    AS settlement_gap,
    COALESCE(a.invoice_count, 0)                        AS invoice_count,

    -- Status classification
    CASE
        WHEN a.actual_amount IS NULL
            THEN 'PENDING'
        WHEN ABS(e.expected_amount - a.actual_amount) < 100
            THEN 'SETTLED'
        ELSE 'PARTIAL'
    END                                                 AS settlement_status

FROM expected_settlement e
LEFT JOIN actual_settlement a
    ON  e.payer_company    = a.payer_company
    AND e.receiver_company = a.receiver_company
    AND e.month_key        = a.month_key

UNION ALL

-- ---------------------------------------------------------------------------
-- 3b) Right side: actual rows that have no matching expected distribution.
--     These represent invoices raised without a corresponding distribution
--     entry — flagged as UNEXPECTED for finance review.
-- ---------------------------------------------------------------------------
SELECT
    a.payer_company,
    a.receiver_company,
    a.year_val,
    a.month_val,
    a.month_key,

    -- Fiscal year dimensions (July–June fiscal year)
    CASE
        WHEN a.month_val >= 7 THEN a.year_val
        ELSE a.year_val - 1
    END                                                 AS fiscal_year,

    CASE
        WHEN a.month_val >= 7 THEN a.month_val - 6     -- Jul=1, ..., Dec=6
        ELSE a.month_val + 6                            -- Jan=7, ..., Jun=12
    END                                                 AS fiscal_month_number,

    -- Metrics: no expected distribution found
    0                                                   AS expected_amount,
    a.actual_amount,
    0 - a.actual_amount                                 AS settlement_gap,
    a.invoice_count,
    'UNEXPECTED'                                        AS settlement_status

FROM actual_settlement a
LEFT JOIN expected_settlement e
    ON  a.payer_company    = e.payer_company
    AND a.receiver_company = e.receiver_company
    AND a.month_key        = e.month_key
WHERE e.payer_company IS NULL;
