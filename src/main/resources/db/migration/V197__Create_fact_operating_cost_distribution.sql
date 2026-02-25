-- =============================================================================
-- Migration V197: Create fact_operating_cost_distribution view
--
-- Purpose:
--   Pre-compute the monthly operating cost distribution across Trustworks
--   sister companies based on consultant headcount ratios. This view exposes
--   the distribution algorithm at the database layer so that BI tools and
--   audit queries can inspect allocated amounts without running the full Java
--   distribution engine.
--
-- Grain: origin_company × payer_company × account_code × month
--   Each row represents how much of an origin company's shared GL amount is
--   allocated to a specific payer company for one calendar month.
--
-- Data Sources:
--   - finance_details: GL transactions from e-conomic ERP (shared accounts only)
--   - accounting_accounts: Chart of accounts; shared flag and salary flag
--   - fact_user_day: Daily consultant headcount per company (renamed from
--     bi_data_per_day in V168; do NOT use the deprecated bi_data_per_day name)
--
-- Algorithm (simplified — see Known Limitation below):
--   1. Count distinct active consultants per company per calendar month
--      (consultant_type = 'CONSULTANT', status NOT IN terminated/non-pay-leave)
--   2. Compute each company's ratio = company_count / total_all_companies
--   3. For each shared GL account in the origin company, multiply the GL amount
--      by the payer ratio to arrive at the allocated amount
--   4. intercompany_owe: amount owed when payer != origin (cross-company charge)
--
-- Key Output Columns:
--   distribution_id     – surrogate key: concat of origin/payer/account/YYYYMM
--   origin_company      – UUID of the company that holds the GL entry
--   payer_company       – UUID of the company that should bear the cost
--   account_code        – GL account number
--   category_uuid       – FK to accounting_categories (for OPEX mapping)
--   shared              – 1 if the account is shared across companies
--   salary              – 1 if the account is a salary account
--   year_val / month_val – calendar year and month
--   month_key           – YYYYMM string for easy range filtering
--   origin_gl_amount    – raw GL total for the origin company
--   payer_ratio         – fraction of total consultants attributed to payer
--   payer_consultants   – count of consultants for the payer company
--   allocated_amount    – origin_gl_amount × payer_ratio (cost borne by payer)
--   intercompany_owe    – allocated_amount when payer != origin, else 0
--
-- Known Limitation:
--   This view does NOT replicate the salary pool capping logic (staff base
--   calculation, salaryBufferMultiplier = 1.02) or lump sum adjustments from
--   accounting_lump_sums.  It covers ~90% of typical distribution scenarios
--   (non-salary shared accounts).  The Java IntercompanyCalcService remains
--   the authoritative source for exact distribution calculations.  This view
--   is intended for audit reporting and BI tooling; use the Java service for
--   CXO endpoint calculations.
--
-- FY Convention (Trustworks):
--   July 1 → June 30.  fiscal_year: month >= 7 uses calendar year, else year - 1.
--
-- Dependencies:
--   finance_details, accounting_accounts, fact_user_day
--
-- Idempotent: CREATE OR REPLACE VIEW — safe to re-run.
-- =============================================================================

CREATE OR REPLACE ALGORITHM = UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_operating_cost_distribution` AS

WITH

-- ---------------------------------------------------------------------------
-- 1) Count distinct active consultants per company per calendar month.
--    Uses fact_user_day (renamed from bi_data_per_day in V168).
--    Filters: consultant_type = 'CONSULTANT' only (not STAFF/STUDENT).
--    Excludes TERMINATED and NON_PAY_LEAVE statuses so that headcount
--    reflects only billable-capacity contributors.
-- ---------------------------------------------------------------------------
consultant_counts AS (
    SELECT
        YEAR(fud.document_date)          AS year_val,
        MONTH(fud.document_date)         AS month_val,
        fud.companyuuid,
        COUNT(DISTINCT fud.useruuid)     AS consultant_count
    FROM fact_user_day fud
    WHERE fud.consultant_type = 'CONSULTANT'
      AND fud.status_type NOT IN ('TERMINATED', 'NON_PAY_LEAVE')
    GROUP BY
        YEAR(fud.document_date),
        MONTH(fud.document_date),
        fud.companyuuid
),

-- ---------------------------------------------------------------------------
-- 2) Sum consultant counts across all companies per month.
--    Used as the denominator when computing each company's ratio.
-- ---------------------------------------------------------------------------
monthly_totals AS (
    SELECT
        year_val,
        month_val,
        SUM(consultant_count) AS total_consultants
    FROM consultant_counts
    GROUP BY year_val, month_val
),

-- ---------------------------------------------------------------------------
-- 3) Compute each company's headcount ratio for each month.
--    ratio = company_consultants / total_consultants (NULLIF guards /0).
-- ---------------------------------------------------------------------------
ratios AS (
    SELECT
        cc.year_val,
        cc.month_val,
        cc.companyuuid,
        cc.consultant_count,
        cc.consultant_count
            / NULLIF(mt.total_consultants, 0)   AS ratio
    FROM consultant_counts cc
    JOIN monthly_totals mt
        ON cc.year_val  = mt.year_val
       AND cc.month_val = mt.month_val
),

-- ---------------------------------------------------------------------------
-- 4) Aggregate shared GL entries per origin company, account, and month.
--    Only includes accounts flagged as shared (aa.shared = 1).
--    Amount sign: ABS(fd.amount) matches the V125 fact_opex convention
--    where credit/reversal entries contribute to OPEX totals as positive.
--    account_code stored as VARCHAR in accounting_accounts; the filter here
--    uses string comparison consistent with fact_opex (V125).
-- ---------------------------------------------------------------------------
gl_shared AS (
    SELECT
        fd.companyuuid                          AS origin_company,
        fd.accountnumber                        AS account_code,
        aa.shared,
        aa.salary,
        aa.categoryuuid,
        YEAR(fd.expensedate)                    AS year_val,
        MONTH(fd.expensedate)                   AS month_val,
        SUM(ABS(fd.amount))                      AS gl_amount
    FROM finance_details fd
    JOIN accounting_accounts aa
        ON  fd.accountnumber = aa.account_code
        AND fd.companyuuid   = aa.companyuuid
    WHERE aa.shared = 1
      AND fd.expensedate IS NOT NULL
      AND fd.amount      != 0
    GROUP BY
        fd.companyuuid,
        fd.accountnumber,
        aa.shared,
        aa.salary,
        aa.categoryuuid,
        YEAR(fd.expensedate),
        MONTH(fd.expensedate)
)

-- ---------------------------------------------------------------------------
-- 5) Final SELECT: cross-join GL amounts with company ratios for the same
--    month to produce per-payer allocated amounts.
-- ---------------------------------------------------------------------------
SELECT
    -- Surrogate key: stable for a given origin/payer/account/month combination
    CONCAT(
        gs.origin_company, '-',
        r.companyuuid,     '-',
        gs.account_code,   '-',
        LPAD(gs.year_val,  4, '0'),
        LPAD(gs.month_val, 2, '0')
    )                                                    AS distribution_id,

    -- Dimension keys
    gs.origin_company,
    r.companyuuid                                        AS payer_company,
    gs.account_code,
    gs.categoryuuid                                      AS category_uuid,
    gs.shared,
    gs.salary,

    -- Calendar time dimensions
    gs.year_val,
    gs.month_val,
    CONCAT(
        LPAD(gs.year_val,  4, '0'),
        LPAD(gs.month_val, 2, '0')
    )                                                    AS month_key,

    -- Fiscal year dimensions (July–June fiscal year)
    CASE
        WHEN gs.month_val >= 7 THEN gs.year_val
        ELSE gs.year_val - 1
    END                                                  AS fiscal_year,

    CASE
        WHEN gs.month_val >= 7 THEN gs.month_val - 6   -- Jul=1, ..., Dec=6
        ELSE gs.month_val + 6                           -- Jan=7, ..., Jun=12
    END                                                  AS fiscal_month_number,

    -- Metrics
    gs.gl_amount                                         AS origin_gl_amount,
    r.ratio                                              AS payer_ratio,
    r.consultant_count                                   AS payer_consultants,
    gs.gl_amount * r.ratio                               AS allocated_amount,

    -- Intercompany owe: cost the payer owes to the origin company.
    -- Zero when payer == origin (no cross-company transfer needed).
    CASE
        WHEN gs.origin_company != r.companyuuid
        THEN gs.gl_amount * r.ratio
        ELSE 0
    END                                                  AS intercompany_owe

FROM gl_shared gs
-- Cross-join with ratios filtered to the same month
JOIN ratios r
    ON  gs.year_val  = r.year_val
    AND gs.month_val = r.month_val;
