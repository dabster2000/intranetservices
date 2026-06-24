-- =============================================================================
-- Migration V378: Fix self-billed PHANTOM client attribution in fact_client_revenue
--                 (sibling-correction to V345)
--
-- PROBLEM
--   Self-billed clients (Vattenfall acct 2104, Energinet acct 2106) book their
--   revenue via self-billing in e-conomic. That revenue is auto-imported nightly
--   by EconomicRevenueImportService as invoices.type='PHANTOM' rows with
--   projectuuid = NULL — the importer never maps the e-conomic account to an
--   internal project. The resolved client instead lives on
--   invoices.billing_client_uuid (stamped by PhantomAttributionService via
--   phantom_client_map).
--
--   V345 derives client_id purely from `p.clientuuid` (LEFT JOIN project on
--   projectuuid). For imported phantoms projectuuid is NULL, so client_id
--   resolves to NULL and the entire self-billed revenue stream is bucketed under
--   the "NULL client" key instead of Vattenfall/Energinet. CXO Client Portfolio
--   Bubble / Client Revenue Pareto (CxoClientService, reading
--   fact_client_revenue_mat) therefore undercount these clients to ~0, and the
--   Invoice Controlling "Client Status" dashboard shows a false under-billing gap
--   equal to their whole self-billed revenue (~16M Vattenfall / ~3M Energinet TTM).
--
-- FIX
--   client_id = COALESCE(p.clientuuid,
--                        CASE WHEN i.type='PHANTOM' THEN i.billing_client_uuid END).
--   Normal invoices keep resolving through their project (projectuuid is always
--   set, so p.clientuuid wins). PHANTOMs (projectuuid NULL) fall back to
--   billing_client_uuid. The fallback is scoped to type='PHANTOM' ON PURPOSE so
--   INTERNAL rows with a NULL project (settlement internals — 6 rows / ~1.0M to the
--   internal client 40c93307 on prod) keep the EXACT V345 behaviour (client_id NULL);
--   internal_dkk client attribution is byte-for-byte unchanged. This is the ONLY
--   change vs V345 — same column set, same amount math, same LEFT JOIN project,
--   same invoicedate month bucketing.
--
-- INVARIANT PRESERVED
--   SUM(net_revenue_dkk) GROUP BY company_id, month_key is unchanged: the COALESCE
--   only relabels which client_id a phantom row belongs to WITHIN a company×month;
--   it neither adds, drops, nor re-values any invoice. So the V345/V344 cross-view
--   reconciliation (fact_client_revenue == fact_company_revenue per company×month)
--   still holds.
--
-- MATERIALIZATION
--   fact_client_revenue_mat is refreshed nightly by sp_refresh_fact_tables
--   (V330) / sp_nightly_bi_refresh — no TRUNCATE here (DB is read-only by default;
--   schema unchanged so no refresh is required for correctness, only freshness).
--   CXO client-grain numbers correct on the next nightly refresh; the Client
--   Status dashboard reads invoices/invoiceitems live (separate fix in
--   ClientStatusService) and is correct immediately.
-- =============================================================================

CREATE OR REPLACE VIEW fact_client_revenue AS

WITH

-- ---------------------------------------------------------------------------
-- 1) Per-invoice aggregation with client_id from project join, falling back to
--    billing_client_uuid when the invoice has no project (self-billed PHANTOMs).
--    No cross-company consultant filter — all consultant lines accrue to the
--    issuing company × client. No discount/fee re-allocation — CALCULATED items
--    also stay with the issuer × client.
-- ---------------------------------------------------------------------------
invoice_data AS (
    SELECT
        i.companyuuid                                               AS company_id,
        i.uuid                                                      AS invoice_uuid,
        i.type                                                      AS invoice_type,
        COALESCE(p.clientuuid,
                 CASE WHEN i.type = 'PHANTOM'
                      THEN i.billing_client_uuid END)               AS client_id,
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
    LEFT JOIN project p ON p.uuid = i.projectuuid
    WHERE (
              (i.type IN ('INVOICE', 'PHANTOM') AND i.status = 'CREATED')
           OR (i.type = 'INTERNAL'              AND i.status IN ('QUEUED', 'CREATED'))
           OR (i.type = 'CREDIT_NOTE'           AND i.status = 'CREATED')
          )
    GROUP BY i.companyuuid, i.uuid, i.type,
             COALESCE(p.clientuuid,
                      CASE WHEN i.type = 'PHANTOM'
                           THEN i.billing_client_uuid END),
             YEAR(i.invoicedate), MONTH(i.invoicedate)
),

-- ---------------------------------------------------------------------------
-- 2) Issuer-centric attribution: all consultant lines + all CALCULATED lines
--    on the invoice stay with the issuing company × client. No proportional
--    re-split.
-- ---------------------------------------------------------------------------
proportional AS (
    SELECT
        company_id,
        invoice_uuid,
        invoice_type,
        client_id,
        month_key,
        year_val,
        month_val,
        (total_base + calc_lines)                                  AS company_amount
    FROM invoice_data
)

-- ---------------------------------------------------------------------------
-- 3) Final aggregation: client × company × month
--    Column set is identical to V209/V345.
-- ---------------------------------------------------------------------------
SELECT
    CONCAT(COALESCE(client_id, 'NULL'), '-', company_id, '-', month_key)
                                                                     AS client_revenue_id,
    client_id,
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
GROUP BY client_id, company_id, month_key, year_val, month_val;
