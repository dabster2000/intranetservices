-- V341: Reclassify A/S intercompany consultant cost accounts from IGNORE to DIRECT_COSTS
--
-- Why this change:
--   Account 3050 "Konsulentbistand TW TECH" and 3055 "Konsulentbistand TW CYBER" at
--   Trustworks A/S have been excluded from the EBITDA chart (cost_type='IGNORE'). This
--   omits 12.02M DKK of FY25-26 YTD intercompany costs from the chart while the
--   corresponding intercompany revenue on the subsidiaries (account 1040 "Konsulent TW")
--   IS counted via INTERNAL invoice flow. The asymmetry inflates apparent group EBITDA
--   and breaks reconciliation against e-conomic's per-entity resultatopgoerelse.
--
--   Per the per-entity gross-sum consolidation methodology (decision: Phase 0.2 of
--   docs/superpowers/plans/2026-05-13-ebitda-system-sync-plan.md), the chart should
--   match the sum of "Resultat foer renter" across all 3 legal entities (~12.32M).
--   Including 3050/3055 as DIRECT_COSTS is required for that alignment.
--
-- Affected rows: 2 (both at companyuuid = Trustworks A/S 'd8894494-2fb4-4f72-9e05-e6032e6dd691')
--   - 3050 Konsulentbistand TW TECH: IGNORE -> DIRECT_COSTS
--   - 3055 Konsulentbistand TW CYBER: IGNORE -> DIRECT_COSTS
--
-- Deployment note:
--   This migration should be deployed concurrently with EXTERNAL invoice import PR 3
--   (V338-V340 from docs/superpowers/specs/2026-05-13-external-invoice-import-design.md).
--   Deploying this migration in isolation will make the chart's EBITDA drop by 12.02M
--   YTD because the offsetting revenue (5.46M EXTERNAL net) hasn't yet been imported.
--
-- Side effects:
--   - CxoFinanceService.getExpectedAccumulatedEBITDA picks up the change at the next
--     chart query (no cache).
--   - fact_opex_distribution_mat does NOT need refresh (it handles SALARIES and OPEX
--     only; DIRECT_COSTS is a live join in the EBITDA query).
--   - No other downstream materializations affected.

UPDATE accounting_accounts
SET cost_type = 'DIRECT_COSTS'
WHERE companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
  AND account_code IN ('3050', '3055')
  AND cost_type = 'IGNORE';

-- Verification (manual, post-deploy):
--   SELECT companyuuid, account_code, account_description, cost_type
--   FROM accounting_accounts
--   WHERE account_code IN ('3050', '3055');
--   -- Expect: both rows show cost_type='DIRECT_COSTS'
--
--   SELECT SUM(ABS(fd.amount))
--   FROM finance_details fd
--   JOIN accounting_accounts aa
--     ON aa.companyuuid = fd.companyuuid
--    AND aa.account_code = CAST(fd.accountnumber AS CHAR)
--   WHERE aa.cost_type = 'DIRECT_COSTS'
--     AND fd.expensedate BETWEEN '2025-07-01' AND '2026-04-30';
--   -- Expect: ~14.06M DKK YTD (previously ~2.04M; +12.02M from 3050/3055).
