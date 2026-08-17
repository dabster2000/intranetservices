-- =============================================================================
-- Migration V506: Mirror e-conomic's UNBOOKED revenue, and make the cost-source
--                 toggle symmetric
--
-- D2 + D10 from the 2026-08-17 FY2025/26 EBITDA reconciliation.
--
-- D2 — accrued self-billing was invisible to every dashboard.
--   14 entries sit in e-conomic on Trustworks A/S accounts 2104/2106 as UNBOOKED
--   drafts dated 30 June 2026, with texts of the form
--   "Faktura: 58484650284 - 05-2026 LJ, periodiseret" — Vattenfall (2104,
--   1,601,680.00) and Energinet (2106, 794,514.68) self-billing for May and June
--   2026, accrued at the year end. Total 2,396,194.68 DKK. The importer read
--   booked entries only, so none of it existed in `invoices` under any setting.
--
--   The importer now keeps a re-synced DRAFT MIRROR: every run deletes the whole
--   draft-sourced PHANTOM population and re-imports whatever e-conomic currently
--   holds as a draft. A "periodiseret" accrual is normally REVERSED and the real
--   invoice booked separately, often under a different entry number, so keying
--   the created row on the draft's entry number would not dedupe reliably and the
--   failure mode is silently doubled revenue. Delete-and-rebuild makes double
--   counting impossible by construction rather than by matching.
--
-- D10 — the cost-source toggle was cost-only.
--   BOOKED_PLUS_DRAFT widened the posting-status filter on COST with no matching
--   widening on REVENUE, so the toggle could only ever move the result DOWN and
--   neither position showed a complete year. With the mirror in place the revenue
--   builders admit draft-sourced rows in the same toggle position that admits
--   draft cost.
--
-- economics_posting_status: 'BOOKED' | 'DRAFT' on importer-created PHANTOMs, NULL
--   on every other invoice — so BOOKED keeps exactly the historical population
--   and the column is a pure addition for every invoice nobody imported.
--
-- The two revenue VIEWS stay BOOKED-ONLY.
--   fact_company_revenue and fact_company_revenue_workperiod have no
--   posting-status concept and are the booked reconciliation reference against
--   e-conomic, so they exclude draft-sourced rows outright. The consequence is
--   explicit and belongs in the methodology doc: the documented "a single-company
--   dashboard figure equals that company's fact_company_revenue net revenue to
--   the øre" invariant holds under costSource=BOOKED, NOT under BOOKED_PLUS_DRAFT.
--
-- Expected effect: nothing moves until the importer next runs. When it does, and
--   with costSource=BOOKED_PLUS_DRAFT, FY2025/26 group revenue gains the draft
--   revenue that is not already represented in `invoices` — measured against
--   production on 2026-08-17 as 3,060,513.22 DKK: the 2,396,194.68 of accrued
--   self-billing, 662,140.54 of standing Kantineordning drafts on 2180, and
--   2,178.00 of expense corrections on 2186. costSource=BOOKED is unchanged.
--
--   NOT included, deliberately: 7,039,435.31 DKK of unbooked drafts on accounts
--   2170/2175 (the 30 June management-fee run, vouchers 28233-28256). That is
--   intercompany, and the intranet already holds the identical run as 24 CREATED
--   INTERNAL_SERVICE invoices totalling exactly 7,039,435.31. Importing it would
--   double-count AND inject an intercompany transfer into group revenue, which is
--   supposed to eliminate. Those two accounts are now on the importer's deny-list,
--   which also protects the BOOKED path on the day the accountant posts that run.
--
-- Idempotency: ADD COLUMN IF NOT EXISTS + CREATE OR REPLACE VIEW.
--
-- Rollback: drop the column, re-run V503 to restore the view bodies without the
--   posting-status predicate, and first remove any mirror rows:
--     DELETE ii FROM invoiceitems ii JOIN invoices i ON i.uuid = ii.invoiceuuid
--      WHERE i.type='PHANTOM' AND i.economics_posting_status='DRAFT';
--     DELETE FROM invoices WHERE type='PHANTOM' AND economics_posting_status='DRAFT';
-- =============================================================================

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS economics_posting_status VARCHAR(10) NULL AFTER economics_accounting_year;

ALTER TABLE invoices
    MODIFY COLUMN economics_posting_status VARCHAR(10) NULL
        COMMENT 'e-conomic posting status this auto-imported PHANTOM came from: BOOKED, or DRAFT for the re-synced unbooked mirror (V506). NULL on every invoice not created by EconomicRevenueImportService. DRAFT rows are admitted only under costSource=BOOKED_PLUS_DRAFT and never by the fact_company_revenue* views.';

CREATE INDEX IF NOT EXISTS idx_invoices_economics_posting_status
    ON invoices (economics_posting_status);

-- ---------------------------------------------------------------------------
-- fact_company_revenue — V503 body + booked-only gate (bucketed by invoicedate)
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
      AND (i.economics_posting_status IS NULL OR i.economics_posting_status = 'BOOKED')
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
-- fact_company_revenue_workperiod — V503 body + booked-only gate
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
      AND (i.economics_posting_status IS NULL OR i.economics_posting_status = 'BOOKED')
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

-- Verification:
--   SELECT economics_posting_status, COUNT(*) FROM invoices
--    WHERE type='PHANTOM' GROUP BY economics_posting_status;
--   -- Expect immediately after migration: every existing PHANTOM NULL, no DRAFT rows.
--   -- After the first importer run: BOOKED for the booked population, DRAFT for the mirror.
--
--   SELECT ROUND(SUM(ii.rate*ii.hours*COALESCE(i.exchange_rate,1)),2) AS draft_revenue
--     FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
--    WHERE i.type='PHANTOM' AND i.economics_posting_status='DRAFT'
--      AND (i.year*100+i.month) BETWEEN 202507 AND 202606;
--   -- Expect after the first run: 3060513.22
--   --   2104 1,601,680.00 + 2106 794,514.68 (the 14 "periodiseret" accruals)
--   -- + 2180   662,140.54 (Kantineordning) + 2186 2,178.00 (expense corrections)
