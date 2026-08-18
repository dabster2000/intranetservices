-- =============================================================================
-- Migration V503: Convert foreign-currency invoices to DKK in the revenue views
--
-- Problem (verified against production twservices4 + e-conomic, 2026-08-17):
--   Every revenue path in the app computes an invoice's value as
--   SUM(invoiceitems.rate * invoiceitems.hours) and ignores invoices.currency
--   entirely, so a EUR or SEK invoice is counted at face value AS IF it were
--   kroner. Nothing in the schema stored a rate, so nothing could convert.
--
--   FY2025/26 effect (15 invoices):
--     TW Technology, EUR, e-conomic account 1021 — 6 docs
--         face value    170,200  counted as DKK
--         e-conomic DKK 1,270,766           -> revenue UNDERSTATED 1,100,566
--     Trustworks A/S, SEK, e-conomic account 2130 — 9 docs
--         face value  1,449,000  counted as DKK
--         e-conomic DKK   995,531           -> revenue OVERSTATED   453,469
--     net FY25/26 understatement: 647,097 DKK
--
--   All time: 73 non-DKK invoices (2 clients — iptiQ EUR on Technology,
--   The Real Word AB SEK on A/S; plus 9 A/S EUR docs from 2024).
--
-- Fix, part 1 — store the rate on the invoice:
--   invoices.exchange_rate = units of DKK per one unit of invoices.currency.
--   NULL means "no conversion applies or none is known", and every reader
--   COALESCEs it to 1, so DKK invoices are untouched and the column is a pure
--   addition for them. A rate is NOT derivable from a monthly table: the
--   realised rates e-conomic booked moved from 0.669601 to 0.708552 within
--   FY25/26 for SEK alone, so a single constant would be several thousand DKK
--   wrong. V504 backfills the historical rows from the rate e-conomic actually
--   booked; InvoiceService stamps the rate on new non-DKK invoices at creation.
--
--   DECIMAL(18,8), not DOUBLE: this multiplies money, and 8 decimals is well
--   beyond the 6 that e-conomic itself publishes.
--
-- Fix, part 2 — apply it in the two revenue views:
--   fact_company_revenue            (V344, bucketed by invoicedate)
--   fact_company_revenue_workperiod (V384, bucketed by invoices.year/month)
--   Both are recreated verbatim from their current definitions with the single
--   change that each line total is multiplied by COALESCE(i.exchange_rate, 1).
--   The dashboard's own builders in CostAnalyticsResource get the same factor
--   in the same commit — they must move together, because the documented
--   invariant is that a single-company dashboard figure equals that company's
--   fact_company_revenue net revenue to the øre.
--
-- Blast radius: every revenue figure derived from these views, for ALL periods,
--   not just FY25/26 — the legacy expected-accumulated-ebitda endpoint,
--   revenue-by-practice, revenue-per-FTE and the bonus calculations. Only the
--   73 non-DKK invoices move; every DKK invoice is bit-identical (COALESCE -> 1).
--   fact_company_revenue_mat is materialized, so its numbers change only after
--   the next sp_refresh_fact_tables() run.
--
-- Idempotency: ADD COLUMN IF NOT EXISTS + CREATE OR REPLACE VIEW.
--
-- Rollback:
--   ALTER TABLE invoices DROP COLUMN exchange_rate;
--   -- then re-run V344 and V384 to restore the un-converted view bodies.
-- =============================================================================

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS exchange_rate DECIMAL(18, 8) NULL AFTER currency;

ALTER TABLE invoices
    MODIFY COLUMN exchange_rate DECIMAL(18, 8) NULL
        COMMENT 'DKK per 1 unit of invoices.currency, as realised in e-conomic. NULL for DKK invoices and for any invoice whose rate is unknown; every reader COALESCEs to 1. Set at invoice creation for non-DKK invoices (InvoiceService); historical rows backfilled by V504.';

-- ---------------------------------------------------------------------------
-- fact_company_revenue — V344 body, FX-converted (bucketed by invoicedate)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW fact_company_revenue AS

WITH

invoice_data AS (
    SELECT
        i.companyuuid                                               AS company_id,
        i.uuid                                                      AS invoice_uuid,
        i.type                                                      AS invoice_type,
        CONCAT(LPAD(YEAR(i.invoicedate), 4, '0'),
               LPAD(MONTH(i.invoicedate), 2, '0'))                 AS month_key,
        YEAR(i.invoicedate)                                         AS year_val,
        MONTH(i.invoicedate)                                        AS month_val,

        -- total_base: all consultant lines (no cross-company filter),
        -- converted to DKK at the invoice's own rate.
        SUM(CASE WHEN ii.consultantuuid IS NOT NULL
                 THEN ii.rate * ii.hours * COALESCE(i.exchange_rate, 1)
                 ELSE 0 END)                                        AS total_base,

        -- calc_lines: non-consultant (CALCULATED) items, same conversion
        SUM(CASE WHEN ii.consultantuuid IS NULL
                 THEN ii.rate * ii.hours * COALESCE(i.exchange_rate, 1)
                 ELSE 0 END)                                        AS calc_lines

    FROM invoices i
    JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
    WHERE (
              (i.type IN ('INVOICE', 'PHANTOM') AND i.status = 'CREATED')
           OR (i.type = 'INTERNAL'              AND i.status IN ('QUEUED', 'CREATED'))
           OR (i.type = 'CREDIT_NOTE'           AND i.status = 'CREATED')
          )
    GROUP BY i.companyuuid, i.uuid, i.type,
             YEAR(i.invoicedate), MONTH(i.invoicedate)
),

proportional AS (
    SELECT
        company_id,
        invoice_uuid,
        invoice_type,
        month_key,
        year_val,
        month_val,
        (total_base + calc_lines)                                  AS company_amount
    FROM invoice_data
)

SELECT
    CONCAT(company_id, '-', month_key)                               AS revenue_id,
    company_id,
    month_key,
    CAST(year_val AS SIGNED)                                         AS year,
    CAST(month_val AS SIGNED)                                        AS month_number,
    CASE WHEN month_val >= 7 THEN year_val ELSE year_val - 1 END    AS fiscal_year,
    CASE WHEN month_val >= 7 THEN month_val - 6 ELSE month_val + 6 END
                                                                     AS fiscal_month_number,

    ROUND(SUM(CASE WHEN invoice_type IN ('INVOICE', 'PHANTOM')
                   THEN company_amount ELSE 0 END), 2)              AS invoice_phantom_dkk,

    ROUND(SUM(CASE WHEN invoice_type = 'INTERNAL'
                   THEN company_amount ELSE 0 END), 2)              AS internal_dkk,

    ROUND(SUM(CASE WHEN invoice_type = 'CREDIT_NOTE'
                   THEN company_amount ELSE 0 END), 2)              AS credit_note_dkk,

    ROUND(
        SUM(CASE WHEN invoice_type IN ('INVOICE', 'PHANTOM')
                 THEN company_amount ELSE 0 END)
      + SUM(CASE WHEN invoice_type = 'INTERNAL'
                 THEN company_amount ELSE 0 END)
      - SUM(CASE WHEN invoice_type = 'CREDIT_NOTE'
                 THEN company_amount ELSE 0 END)
    , 2)                                                             AS net_revenue_dkk

FROM proportional
GROUP BY company_id, month_key, year_val, month_val;

-- ---------------------------------------------------------------------------
-- fact_company_revenue_workperiod — V384 body, FX-converted
-- (bucketed by invoices.year / invoices.month)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW fact_company_revenue_workperiod AS

WITH

invoice_data AS (
    SELECT
        i.companyuuid                                               AS company_id,
        i.uuid                                                      AS invoice_uuid,
        i.type                                                      AS invoice_type,
        CONCAT(LPAD(i.year, 4, '0'),
               LPAD(i.month, 2, '0'))                               AS month_key,
        i.year                                                      AS year_val,
        i.month                                                     AS month_val,

        SUM(CASE WHEN ii.consultantuuid IS NOT NULL
                 THEN ii.rate * ii.hours * COALESCE(i.exchange_rate, 1)
                 ELSE 0 END)                                        AS total_base,

        SUM(CASE WHEN ii.consultantuuid IS NULL
                 THEN ii.rate * ii.hours * COALESCE(i.exchange_rate, 1)
                 ELSE 0 END)                                        AS calc_lines

    FROM invoices i
    JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
    WHERE (
              (i.type IN ('INVOICE', 'PHANTOM') AND i.status = 'CREATED')
           OR (i.type = 'INTERNAL'              AND i.status IN ('QUEUED', 'CREATED'))
           OR (i.type = 'CREDIT_NOTE'           AND i.status = 'CREATED')
          )
    GROUP BY i.companyuuid, i.uuid, i.type, i.year, i.month
),

proportional AS (
    SELECT
        company_id, invoice_uuid, invoice_type,
        month_key, year_val, month_val,
        (total_base + calc_lines)                                  AS company_amount
    FROM invoice_data
)

SELECT
    CONCAT(company_id, '-', month_key)                              AS revenue_id,
    company_id,
    month_key,
    CAST(year_val AS SIGNED)                                        AS year,
    CAST(month_val AS SIGNED)                                       AS month_number,
    CASE WHEN month_val >= 7 THEN year_val ELSE year_val - 1 END    AS fiscal_year,
    CASE WHEN month_val >= 7 THEN month_val - 6 ELSE month_val + 6 END
                                                                    AS fiscal_month_number,

    ROUND(SUM(CASE WHEN invoice_type IN ('INVOICE', 'PHANTOM')
                   THEN company_amount ELSE 0 END), 2)              AS invoice_phantom_dkk,

    ROUND(SUM(CASE WHEN invoice_type = 'INTERNAL'
                   THEN company_amount ELSE 0 END), 2)              AS internal_dkk,

    ROUND(SUM(CASE WHEN invoice_type = 'CREDIT_NOTE'
                   THEN company_amount ELSE 0 END), 2)              AS credit_note_dkk,

    ROUND(
        SUM(CASE WHEN invoice_type IN ('INVOICE', 'PHANTOM')
                 THEN company_amount ELSE 0 END)
      + SUM(CASE WHEN invoice_type = 'INTERNAL'
                 THEN company_amount ELSE 0 END)
      - SUM(CASE WHEN invoice_type = 'CREDIT_NOTE'
                 THEN company_amount ELSE 0 END)
    , 2)                                                            AS net_revenue_dkk

FROM proportional
GROUP BY company_id, month_key, year_val, month_val;

-- Verification (after V504 has backfilled the rates):
--   SELECT ROUND(SUM(ii.rate*ii.hours*COALESCE(i.exchange_rate,1)),2)
--     FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
--    WHERE i.currency='EUR' AND i.companyuuid='44592d3b-2be5-4b29-bfaf-4fafc60b0fa3'
--      AND (i.year*100+i.month) BETWEEN 202507 AND 202606;
--   -- Expect: 1,270,765.52  (e-conomic account 1021, same 6 documents)
--
--   SELECT ROUND(SUM(ii.rate*ii.hours*COALESCE(i.exchange_rate,1)),2)
--     FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
--    WHERE i.currency='SEK' AND (i.year*100+i.month) BETWEEN 202507 AND 202606;
--   -- Expect: 995,530.94    (e-conomic account 2130, same 9 documents)
