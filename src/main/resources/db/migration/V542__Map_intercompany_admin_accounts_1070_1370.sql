-- =============================================================================
-- Migration V542: Map the Cyber/Technology intercompany administration accounts
--
-- Closes the item V540 deliberately deferred. V540 mapped six of the eight
-- FY2025/2026 in-band gaps and left Cyber 1070 / 1370 alone, because classifying
-- an intercompany account without first checking the settlement path risks
-- double-counting the same internal flow. That check has now been done.
--
-- WHAT THESE ACCOUNTS ARE. Cyber and Technology cross-charge each other for
--   administration. The flow is booked on a symmetric pair per company:
--     10xx  the revenue leg  (inside "Omsætning" .. "Omsætning i alt")
--     13xx  the cost leg     (inside "Direkte omkostninger" .. "... i alt")
--   Both are e-conomic accountType=profitAndLoss and both sit above each
--   entity's 4000 "Resultat før renter" line.
--
--   Co   Acct  e-conomic name                   FY24/25      FY25/26     Leg
--   ---  ----  -------------------------------  -----------  ----------  -------
--   TWC  1070  Administration Technology         -17,813.93  -81,842.59  revenue
--   TWC  1370  Administration TW Technology      +71,306.42  +90,749.76  cost
--   TWT  1070  Administration Cyber Security     -71,306.42        none  revenue
--   TWT  1370  Administration Cyber Security     +17,813.93        none  cost
--
--   FY2024/2025 shows the pair reconciling to the øre across the two companies —
--   TWC 1070 -17,813.93 mirrors TWT 1370 +17,813.93, and TWC 1370 +71,306.42
--   mirrors TWT 1070 -71,306.42. See the data note at the bottom about FY2025/2026.
--
-- THE DOUBLE-COUNT QUESTION, ANSWERED. It does not arise, on three counts:
--   1. `intercompany_account_mapping` (V375) is a two-row (debtor, issuer) ->
--      voucher-account resolver used by EconomicsAgreementResolver to pick the
--      debtor-side SupplierInvoice account. It names 3050 and 3055 only, carries
--      no cost_type, and has no opinion about 1070/1370.
--   2. `fact_intercompany_settlement` (V198) does carry these exact amounts as
--      `actual_amount`, but it is a display-only reconciliation surface: its sole
--      consumer is CostAnalyticsResource's GET /intercompany-table. It never
--      reaches EBITDA.
--   3. The path that DOES carry intercompany cost into EBITDA is
--      `fact_internal_invoice_cost` (V195), and it hard-codes
--      `fd.accountnumber IN (3050, 3055, 3070, 3075, 1350)`. 1370 is not in that
--      list, so its amount is not recognised there either. Verified numerically
--      against production: fact_internal_invoice_cost_mat for Cyber equals the
--      1350 total alone, to the øre, in both 202506 and 202606.
--   In short, nothing in the system recognises these GL amounts today — so no
--   mapping can double-count them, and IGNORE keeps it that way.
--
-- CLASSIFICATION: IGNORE, and it is the house convention rather than a judgement.
--   Every intercompany ADMINISTRATION account already mapped, in every company,
--   is IGNORE / shared=0 / salary=0 — without exception:
--     A/S  2170 Administration TW TECH           IGNORE  Varesalg
--     A/S  2175 Administration Cyber             IGNORE  Varesalg
--     A/S  3070 Administration TW TECH           IGNORE  Direkte omkostninger
--     A/S  3075 Administration TW CYBER          IGNORE  Direkte omkostninger
--     TWT  1050 Administration Trustworks A/S    IGNORE  Varesalg
--     TWT  1350 Administration Trustworks A/S    IGNORE  Direkte omkostninger
--     TWC  1050 Administration Trustworks A/S    IGNORE  Varesalg
--     TWC  1350 Administration Trustworks A/S    IGNORE  Direkte omkostninger
--   The only intercompany accounts that carry a real cost type are the
--   CONSULTANT-DELIVERY ones (A/S 3050/3055 = DIRECT_COSTS) — a different branch
--   of the transfer-price model. 1070/1370 are administration, not delivery.
--   Each row below therefore mirrors its own company's 1050 (revenue leg) or
--   1350 (cost leg) exactly: same cost_type, same category, same flags.
--
--   categoryuuid is inert for an IGNORE row (nothing aggregates it) and is set
--   only to match the sibling it mirrors, exactly as V507 did for 6150/6160.
--
--   shared = 0 is NOT cosmetic. V197's gl_shared CTE filters on
--   `WHERE aa.shared = 1` with NO cost_type predicate and aggregates
--   `SUM(ABS(fd.amount))`, so a shared=1 row of ANY cost_type — IGNORE included —
--   enters the intercompany distribution as a POSITIVE cost, with ABS() turning
--   the revenue leg's credit into one. All eight siblings above are shared=0.
--
-- EBITDA IMPACT: 0.00 DKK, by construction. fact_opex and
--   fact_opex_distribution_mat select cost_type IN ('OPEX','SALARIES');
--   DIRECT_COSTS feeds the delivery-cost query. IGNORE is read by none of them.
--   No cost, revenue or EBITDA figure moves in any fiscal year. This is a
--   completeness fix: it records a decision that was previously an omission, and
--   it silences UnmappedGlAccountCheck's last standing finding.
--
-- WHY TECHNOLOGY 1070/1370 ARE INCLUDED. They are the same accounts on the other
--   side of the same cross-charge and are equally unmapped. They escaped every
--   previous sweep only because they carry no FY2025/2026 activity, so the gate's
--   window never covered them — the identical accident that hid the FY2025/2026
--   backlog. Mapping all four legs together leaves the family complete and stops
--   the next Technology-side admin posting from re-opening the alarm.
--
-- BAND SIDE EFFECT: none. All four codes are inside their company's existing
--   scope (Technology and Cyber 1010..3780, and the configured EBITDA span
--   1001..3999), so no bound moves.
--
-- DATA NOTE, NOT FIXED HERE: the FY2025/2026 legs are booked ONE-SIDEDLY. Cyber
--   carries both its 1070 (-81,842.59) and its 1370 (+90,749.76), but Technology
--   has no 1070 or 1370 rows dated in FY2025/2026 at all, despite having 659
--   other finance_details rows for 2026-06-01. FY2024/2025 was booked on both
--   sides. This is a bookkeeping asymmetry in e-conomic, not something a mapping
--   row can repair, and it is reported separately. The mapping is correct either
--   way: IGNORE excludes both sides regardless.
--
-- Idempotency: there is no unique index on (companyuuid, account_code), so each
--   INSERT is guarded by NOT EXISTS — matching V382/V390/V507/V539/V540. Verified
--   2026-08-30 that none of the four rows currently exists.
--
-- Rollback:
--   DELETE FROM accounting_accounts WHERE companyuuid='e4b0a2a4-0963-4153-b0a2-a409637153a2' AND account_code IN ('1070','1370');
--   DELETE FROM accounting_accounts WHERE companyuuid='44592d3b-2be5-4b29-bfaf-4fafc60b0fa3' AND account_code IN ('1070','1370');
-- =============================================================================

SET @tech  := '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3';  -- Trustworks Technology ApS
SET @cyber := 'e4b0a2a4-0963-4153-b0a2-a409637153a2';  -- Trustworks Cyber Security ApS

SET @cat_varesalg := 'fa83ddc1-52a4-44cb-9717-06a64b01747a';  -- Varesalg
SET @cat_direkte  := '1ae2ddb9-270b-453d-8a3b-c55edc052a96';  -- Direkte omkostninger

-- -----------------------------------------------------------------------------
-- Trustworks Cyber Security ApS
-- -----------------------------------------------------------------------------
-- 1070 Administration Technology -> IGNORE (revenue leg; mirror Cyber 1050)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_varesalg, '1070', 'Administration Technology', 0, 0, 'IGNORE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '1070');

-- 1370 Administration TW Technology -> IGNORE (cost leg; mirror Cyber 1350)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_direkte, '1370', 'Administration TW Technology', 0, 0, 'IGNORE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '1370');

-- -----------------------------------------------------------------------------
-- Trustworks Technology ApS
-- -----------------------------------------------------------------------------
-- 1070 Administration Cyber Security -> IGNORE (revenue leg; mirror Technology 1050)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_varesalg, '1070', 'Administration Cyber Security', 0, 0, 'IGNORE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '1070');

-- 1370 Administration Cyber Security -> IGNORE (cost leg; mirror Technology 1350)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_direkte, '1370', 'Administration Cyber Security', 0, 0, 'IGNORE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '1370');

-- Verification (READ-ONLY, run after deploy):
--   SELECT companyuuid, account_code, account_description, cost_type, shared, salary
--     FROM accounting_accounts WHERE account_code IN ('1070','1370')
--      AND companyuuid IN ('44592d3b-2be5-4b29-bfaf-4fafc60b0fa3','e4b0a2a4-0963-4153-b0a2-a409637153a2');
--   -- Expect: 4 rows, all IGNORE / shared=0 / salary=0.
--
--   -- No cost figure may move. Compare before/after:
--   SELECT company_id, cost_type, ROUND(SUM(opex_amount_dkk),2)
--     FROM fact_opex_mat WHERE fiscal_year IN (2024, 2025, 2026) GROUP BY 1, 2;
--   -- Expect: byte-identical to the pre-migration values. IGNORE enters nothing.
--
--   -- With V539 + V540 + this migration applied, UnmappedGlAccountCheck's
--   -- 2-fiscal-year window must come back completely clean:
--   --   FY2026/2027: 0 findings
--   --   FY2025/2026: 0 findings   (was 8 before V540, 2 after V540)
