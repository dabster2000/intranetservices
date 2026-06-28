-- =============================================================================
-- Migration V384: Work-period revenue recognition view
--
-- Adds an OPT-IN "work-period" recognition basis for the Executive Dashboard,
-- alongside the existing invoiced basis (fact_company_revenue, V344).
--
-- WHY
--   fact_company_revenue buckets every invoice by i.invoicedate (when it was
--   billed). Internal year-end catch-up billing therefore piles ~13M DKK of
--   intercompany revenue into a single June month, even though the underlying
--   work was performed Sep 2025 -> May 2026. The work-period basis buckets the
--   SAME invoices by their WORK/SERVICE period (invoices.year / invoices.month)
--   so revenue (and its matching invoice-derived cost, recognised in Java)
--   lands in the month the work was actually delivered.
--
-- RELATION TO fact_company_revenue (V344)
--   This view is a FAITHFUL TWIN of V344 with exactly ONE difference: it
--   buckets by (i.year, i.month) instead of (YEAR(i.invoicedate),
--   MONTH(i.invoicedate)). Everything else is identical:
--     - same type+status gate
--         (INVOICE|PHANTOM & CREATED) OR (INTERNAL & QUEUED|CREATED)
--         OR (CREDIT_NOTE & CREATED);  INTERNAL_SERVICE and DRAFT excluded
--     - same per-invoice amount  = SUM(rate*hours) over invoiceitems
--                                  (consultant lines + CALCULATED lines), all
--                                  attributed to the issuing company
--     - same output columns
--   Because the invoice SET and per-invoice amount are identical, the all-time
--   total per company is byte-identical to the invoiced basis; only the
--   month distribution differs. (Verified: all-time net revenue = 618,307,867
--   DKK under both bucketings. Invoice 70368 moves June->Jan; the 13.0M June
--   internal block spreads to Sep'25-May'26.)
--
-- NOT MATERIALIZED (by design)
--   The invoiced twin has a _mat table because 12 endpoints read it on every
--   request. The work-period basis is opt-in and consumed by only two endpoints
--   (expected-accumulated-ebitda, revenue-cost-forecast). Those endpoints
--   already scan invoices x invoiceitems live (the internal-cost synth helpers),
--   so a live view query here is consistent and cheap (~6k invoices). No _mat,
--   no change to sp_refresh_fact_tables / sp_nightly_bi_refresh, no new event.
--   A _mat twin can be added later if the basis is ever read more widely.
--
-- CAVEAT (documented in the dashboard caption, not in SQL)
--   On the work-period basis the most RECENT months understate, because work
--   performed but not yet invoiced has no invoice row to bucket. This is the
--   mirror image of the invoiced basis overstating the billing month. The
--   invoiced basis remains the default and the e-conomic reconciliation target.
--
-- Idempotency: CREATE OR REPLACE VIEW — safe to re-run.
-- Rollback:    DROP VIEW IF EXISTS fact_company_revenue_workperiod;
-- =============================================================================

CREATE OR REPLACE VIEW fact_company_revenue_workperiod AS

WITH

-- ---------------------------------------------------------------------------
-- 1) Per-invoice aggregation, bucketed by the WORK period (i.year, i.month).
--    No cross-company consultant filter — all lines (consultant + CALCULATED)
--    accrue to the issuing company, identical to V344.
-- ---------------------------------------------------------------------------
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
                 THEN ii.rate * ii.hours ELSE 0 END)                AS total_base,

        SUM(CASE WHEN ii.consultantuuid IS NULL
                 THEN ii.rate * ii.hours ELSE 0 END)                AS calc_lines

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
