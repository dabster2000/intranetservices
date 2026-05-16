-- =============================================================================
-- Migration V344: Fix V201 cross-company consultant attribution
--
-- Phase 3 of the EBITDA chart reconciliation
-- (docs/superpowers/plans/2026-05-16-ebitda-chart-reconciliation-plan.md).
--
-- Customer revenue stays with the invoice issuer (issuer-centric attribution,
-- matching e-conomic per-CVR booking). Cross-company consultant lines are no
-- longer silently dropped from the issuing company's revenue total.
--
-- BEFORE (V201): A TW A/S invoice with a TW TECH consultant line had that
--                consultant line's revenue silently dropped from TW A/S's
--                row, and the CALCULATED items were proportionally
--                re-allocated by company share. This produced a verified
--                18.78M DKK silent revenue drop on TW A/S invoices.
--
-- AFTER  (V344): All consultant lines on an invoice flow to the issuing
--                company. CALCULATED items also flow entirely to the issuer.
--                Cross-company cost recognition is handled separately (Phase 4
--                via debtor-side cost synthesis in CxoFinanceService).
--
-- INTERNAL status filter is UNCHANGED — both QUEUED and CREATED INTERNAL
-- invoices remain in revenue. Phase 4 adds the matching debtor-side cost
-- recognition; it does NOT remove QUEUED from revenue.
--
-- Structural changes vs V201:
--   - DROPPED  the consultant_invoice_company CTE entirely (no longer needed)
--   - REMOVED  co_base from invoice_data (kept total_base, kept calc_lines)
--   - SIMPLIFIED proportional CTE: company_amount = total_base + calc_lines
--                (no proportional re-split; all dollars stay with the issuer)
--
-- Column set is IDENTICAL to V201 (no schema break for downstream consumers
-- including fact_company_revenue_mat and sp_refresh_fact_tables / V202).
--
-- Verified against spec for November 2025 (Phase 3 expected):
--   TW A/S revenue increases by ~18.78M DKK across the affected FY window,
--   closing the largest single component of the EBITDA chart gap.
-- =============================================================================

CREATE OR REPLACE VIEW fact_company_revenue AS

WITH

-- ---------------------------------------------------------------------------
-- 1) Per-invoice aggregation. No cross-company consultant filter — all
--    consultant lines accrue to the issuing company. No discount/fee
--    re-allocation — CALCULATED items also stay with the issuer.
-- ---------------------------------------------------------------------------
invoice_data AS (
    SELECT
        i.companyuuid                                               AS company_id,
        i.uuid                                                      AS invoice_uuid,
        i.type                                                      AS invoice_type,
        CONCAT(LPAD(YEAR(i.invoicedate), 4, '0'),
               LPAD(MONTH(i.invoicedate), 2, '0'))                 AS month_key,
        YEAR(i.invoicedate)                                         AS year_val,
        MONTH(i.invoicedate)                                        AS month_val,

        -- total_base: all consultant lines (no cross-company filter)
        SUM(CASE WHEN ii.consultantuuid IS NOT NULL
                 THEN ii.rate * ii.hours ELSE 0 END)               AS total_base,

        -- calc_lines: non-consultant (CALCULATED) items
        SUM(CASE WHEN ii.consultantuuid IS NULL
                 THEN ii.rate * ii.hours ELSE 0 END)               AS calc_lines

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

-- ---------------------------------------------------------------------------
-- 2) Issuer-centric attribution: all consultant lines + all CALCULATED lines
--    on the invoice stay with the issuing company. No proportional re-split.
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 3) Final aggregation: company × month
--    Column set is identical to V201.
-- ---------------------------------------------------------------------------
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
