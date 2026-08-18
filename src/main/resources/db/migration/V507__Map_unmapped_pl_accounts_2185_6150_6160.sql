-- =============================================================================
-- Migration V507: Map the last three unmapped Trustworks A/S P&L accounts
--
-- D9 from the 2026-08-17 FY2025/26 EBITDA reconciliation. Follow-up to V382 and
-- V390, same pattern: each new row mirrors its nearest existing sibling in the
-- SAME company so the classification stays internally consistent.
--
--   Account  Name                             FY25/26     cost_type  Mirror
--   -------  -------------------------------  ----------  ---------  ------------
--   2185     Salg af IT-udstyr                 -3,600.00  REVENUE    2104 / 2106
--   6150     Afskrivning Inventar             133,896.03  IGNORE     (see below)
--   6160     Afskrivning Lokaler/indretning    36,357.12  IGNORE     (see below)
--                                    net       166,653.15
--
-- WHY 2185 = REVENUE (not a cost):
--   Its GL amount is NEGATIVE — income from selling used IT equipment. Classified
--   as any cost type that negative would SUBTRACT from cost and inflate EBITDA.
--   REVENUE keeps it out of the cost views, exactly as V382 did for 2106.
--
-- WHY 6150/6160 = IGNORE (and why this is a decision, not an oversight):
--   Excluding depreciation from an EBITDA measure is CORRECT — the D and the A in
--   EBITDA. But until now it happened by ACCIDENT: both accounts sit numerically
--   above A/S's mapped band (2101-5298), so they were not merely unclassified,
--   they were invisible to UnmappedGlAccountCheck as well. Mapping them makes the
--   exclusion deliberate, documented and visible in the admin UI.
--
--   IGNORE rather than a new DEPRECIATION cost type: IGNORE already means
--   "excluded from all fact views" (see CostType), every consumer already handles
--   it, and adding an enum value would need a matching frontend change that is
--   out of scope for this backend-only remediation. The distinction is recorded
--   here and in docs/finalized/executive-dashboard/consolidation-methodology.md.
--
--   categoryuuid is inert for an IGNORE row (nothing aggregates it) and is set
--   only because the column is populated on every other row; "Øvrige
--   administrationsomk" is the least-wrong bucket. cost_type carries the meaning.
--
-- EBITDA IMPACT: NONE, by construction. fact_opex and fact_opex_distribution_mat
--   select cost_type IN ('OPEX','SALARIES'); DIRECT_COSTS feeds the delivery-cost
--   query. REVENUE and IGNORE are read by neither, so no cost or revenue figure
--   moves — depreciation stays excluded, which is the point.
--
-- BAND SIDE EFFECT, checked before writing this: UnmappedGlAccountCheck scopes
--   its anti-join to each company's mapped [MIN..MAX] account-code band, so
--   mapping 6160 lifts A/S's ceiling from 5298 to 6160 and widens what the check
--   can see. Verified against production 2026-08-17: the ONLY A/S accounts with
--   finance_details activity between 5299 and 6400 are 6150 and 6160 themselves,
--   in FY25/26 and in FY26/27 alike. Widening the band introduces no new
--   findings, and from now on a newly opened account anywhere up to 6160 will be
--   caught instead of silently dropped.
--
-- Idempotency: there is no unique index on (companyuuid, account_code), so each
--   INSERT is guarded by NOT EXISTS — matching V382/V390.
--
-- Rollback:
--   DELETE FROM accounting_accounts
--    WHERE companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
--      AND account_code IN ('2185','6150','6160');
-- =============================================================================

SET @company           := 'd8894494-2fb4-4f72-9e05-e6032e6dd691';  -- Trustworks A/S
SET @cat_varesalg      := 'fa83ddc1-52a4-44cb-9717-06a64b01747a';  -- Varesalg (revenue)
SET @cat_ovrige_admin  := 'e8900f9f-dc8c-42de-a038-8477a1e5c18f';  -- Øvrige administrationsomk

-- 2185 Salg af IT-udstyr -> REVENUE (mirror 2104 Vattenfall / 2106 Energinet)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_varesalg, '2185', 'Salg af IT-udstyr', 0, 0, 'REVENUE'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '2185');

-- 6150 Afskrivning Inventar -> IGNORE (depreciation, deliberately outside EBITDA)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_ovrige_admin, '6150', 'Afskrivning Inventar', 0, 0, 'IGNORE'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '6150');

-- 6160 Afskrivning Lokaler/indretning -> IGNORE (depreciation, deliberately outside EBITDA)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_ovrige_admin, '6160', 'Afskrivning Lokaler/indretning', 0, 0, 'IGNORE'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '6160');

-- Verification:
--   SELECT account_code, account_description, cost_type FROM accounting_accounts
--    WHERE companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
--      AND account_code IN ('2185','6150','6160');
--   -- Expect: 3 rows — 2185 REVENUE, 6150 IGNORE, 6160 IGNORE.
--
--   -- UnmappedGlAccountCheck.detect() for FY25/26, verbatim:
--   SELECT fd.companyuuid, fd.accountnumber, SUM(fd.amount), COUNT(*)
--     FROM finance_details fd
--     JOIN (SELECT companyuuid, MIN(CAST(account_code AS UNSIGNED)) lo,
--                  MAX(CAST(account_code AS UNSIGNED)) hi
--             FROM accounting_accounts GROUP BY companyuuid) band
--       ON band.companyuuid = fd.companyuuid
--      AND fd.accountnumber BETWEEN band.lo AND band.hi
--     LEFT JOIN accounting_accounts aa
--            ON aa.account_code = fd.accountnumber AND aa.companyuuid = fd.companyuuid
--    WHERE fd.expensedate >= '2025-07-01' AND fd.expensedate <= '2026-06-30'
--      AND aa.account_code IS NULL
--    GROUP BY 1, 2;
--   -- Expect: zero rows. Before this migration it returned exactly one —
--   --         A/S 2185, -3,600.00 over 1 entry.
