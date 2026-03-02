-- =============================================================================
-- Migration V209: Create fact_client_revenue view
--
-- Purpose:
--   Client-level revenue view for the CXO Dashboard Client & Portfolio Health tab.
--   Extends the canonical company-level revenue algorithm from V201
--   (fact_company_revenue) by adding a client_id dimension.
--
-- Algorithm (identical to V201 — spec sections 2-8):
--   INVOICE + PHANTOM (CREATED)     → positive revenue
--   INTERNAL (QUEUED or CREATED)    → positive revenue for issuing company
--   CREDIT_NOTE (CREATED)           → subtracted from revenue
--   Proportional allocation of CALCULATED items across companies.
--
-- Client attribution:
--   Via invoices.projectuuid → project.clientuuid → client.uuid
--   This path provides 99.98% coverage (4844/4845 eligible invoices).
--   The single invoice with no project is a DUMMY with no line items and
--   zero value, so it does not affect revenue totals.
--
-- Grain: client_id × company_id × month_key (YYYYMM)
--
-- Invariant (verified against V202 for November 2025):
--   SUM(net_revenue_dkk) GROUP BY company_id, month_key
--   MUST equal fact_company_revenue_mat.net_revenue_dkk
--   for the same company_id × month_key.
--
-- Consumer:
--   CxoClientService.java — getClientPortfolioBubble(), getClientRevenuePareto(),
--   getIndustryDistribution(). Replace fact_project_financials_mat usage for
--   revenue columns with this view (or its future _mat counterpart).
--
-- Internal client exclusion:
--   Callers should filter WHERE client_id != 'd58bb00b-4474-4250-84eb-d8f77548ddac'
--   (INTERNAL_CLIENT_UUID constant in CxoClientService).
--   The view does NOT hard-code this exclusion so that the data is complete
--   and callers retain control over the filter.
--
-- Financial year: July 1 → June 30 (fiscal_year, fiscal_month_number)
--
-- Verified against fact_company_revenue_mat for November 2025:
--   TW A/S:   11,574,496.23 DKK  (matches)
--   TW TECH:   1,955,952.51 DKK  (matches)
--   TW CYBER:  1,134,532.98 DKK  (matches)
--   TOTAL:    14,664,981.72 DKK
-- =============================================================================

CREATE OR REPLACE VIEW fact_client_revenue AS

WITH

-- ---------------------------------------------------------------------------
-- 1) For each (consultant, invoice) pair, find the latest userstatus
--    record on or before the last day of the invoice's month.
--    Identical to V201: uses MAX + self-join (no correlated subqueries).
-- ---------------------------------------------------------------------------
consultant_invoice_company AS (
    SELECT
        ii.uuid                   AS item_uuid,
        us_match.companyuuid      AS consultant_companyuuid
    FROM invoices i
    JOIN invoiceitems ii
        ON ii.invoiceuuid = i.uuid
       AND ii.consultantuuid IS NOT NULL
    JOIN userstatus us_match
        ON us_match.useruuid = ii.consultantuuid
    JOIN (
        SELECT
            ii2.uuid AS item_uuid,
            MAX(us2.statusdate) AS max_date
        FROM invoices i2
        JOIN invoiceitems ii2
            ON ii2.invoiceuuid = i2.uuid
           AND ii2.consultantuuid IS NOT NULL
        JOIN userstatus us2
            ON us2.useruuid = ii2.consultantuuid
           AND us2.statusdate <= LAST_DAY(i2.invoicedate)
        WHERE (
                  (i2.type IN ('INVOICE', 'PHANTOM') AND i2.status = 'CREATED')
               OR (i2.type = 'INTERNAL'              AND i2.status IN ('QUEUED', 'CREATED'))
               OR (i2.type = 'CREDIT_NOTE'           AND i2.status = 'CREATED')
              )
        GROUP BY ii2.uuid
    ) max_lookup
        ON max_lookup.item_uuid = ii.uuid
       AND us_match.useruuid   = ii.consultantuuid
       AND us_match.statusdate = max_lookup.max_date
    WHERE (
              (i.type IN ('INVOICE', 'PHANTOM') AND i.status = 'CREATED')
           OR (i.type = 'INTERNAL'              AND i.status IN ('QUEUED', 'CREATED'))
           OR (i.type = 'CREDIT_NOTE'           AND i.status = 'CREATED')
          )
),

-- ---------------------------------------------------------------------------
-- 2) Per-invoice aggregation with proportional discount allocation.
--    Extended from V201 to include client_id via project join.
-- ---------------------------------------------------------------------------
invoice_data AS (
    SELECT
        i.companyuuid                                               AS company_id,
        i.uuid                                                      AS invoice_uuid,
        i.type                                                      AS invoice_type,
        p.clientuuid                                                AS client_id,
        CONCAT(LPAD(YEAR(i.invoicedate), 4, '0'),
               LPAD(MONTH(i.invoicedate), 2, '0'))                 AS month_key,
        YEAR(i.invoicedate)                                         AS year_val,
        MONTH(i.invoicedate)                                        AS month_val,

        -- total_base: all consultant lines (any company)
        SUM(CASE WHEN ii.consultantuuid IS NOT NULL
                 THEN ii.rate * ii.hours ELSE 0 END)               AS total_base,

        -- co_base: consultant lines belonging to the issuing company
        SUM(CASE WHEN ii.consultantuuid IS NOT NULL
                      AND cic.consultant_companyuuid = i.companyuuid
                 THEN ii.rate * ii.hours ELSE 0 END)               AS co_base,

        -- calc_lines: non-consultant (CALCULATED) items
        SUM(CASE WHEN ii.consultantuuid IS NULL
                 THEN ii.rate * ii.hours ELSE 0 END)               AS calc_lines

    FROM invoices i
    JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
    LEFT JOIN consultant_invoice_company cic ON cic.item_uuid = ii.uuid
    LEFT JOIN project p ON p.uuid = i.projectuuid
    WHERE (
              (i.type IN ('INVOICE', 'PHANTOM') AND i.status = 'CREATED')
           OR (i.type = 'INTERNAL'              AND i.status IN ('QUEUED', 'CREATED'))
           OR (i.type = 'CREDIT_NOTE'           AND i.status = 'CREATED')
          )
    GROUP BY i.companyuuid, i.uuid, i.type, p.clientuuid,
             YEAR(i.invoicedate), MONTH(i.invoicedate)
),

-- ---------------------------------------------------------------------------
-- 3) Proportional allocation of CALCULATED items.
--    Identical to V201: allocate calc_lines proportional to co_base / total_base.
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
        CASE
            WHEN total_base = 0 AND calc_lines != 0 THEN calc_lines
            WHEN total_base = 0                     THEN 0
            ELSE co_base + (calc_lines * co_base / total_base)
        END AS company_amount
    FROM invoice_data
)

-- ---------------------------------------------------------------------------
-- 4) Final aggregation: client × company × month
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
