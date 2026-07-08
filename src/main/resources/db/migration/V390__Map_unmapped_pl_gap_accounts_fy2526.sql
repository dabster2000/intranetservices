-- =============================================================================
-- Migration V390: Close the remaining P&L GL-mapping gaps (2026-07-08 investigation)
--
-- Follow-up to V382 (F18). Triggered by the UnmappedGlAccountCheck Slack alert for
-- FY 2026/2027. Investigation on 2026-07-08 replicated the check's anti-join against
-- production twservices4 for both fiscal years and classified EVERY flagged account
-- against e-conomic's own chart of accounts (authoritative `accountType`):
--
--   * The current FY 2026/2027 warning (6 accounts) is 100% balance-sheet (status):
--     8610/5600 Debitorer, 9418/6902 Udgående moms. These MUST NOT be mapped — they
--     would inject balance-sheet churn into fact_opex/EBITDA. No action taken on them.
--   * FY 2025/2026: 282 unmapped (company,account) pairs. 258 are balance-sheet
--     `status` accounts (a-skat, moms, bank, debitorer, kreditorer, egenkapital) that
--     are correctly excluded. Only 24 are `profitAndLoss`; restricting to each
--     company's mapped account-code band leaves 16 genuine P&L drops — 15 operating
--     costs plus one canteen-recovery income account. All 16 are mapped here. Net
--     EBITDA impact is immaterial (see cost table) — this is a data-completeness fix,
--     not a material restatement.
--
-- The 16th account, A/S 2180 "Salg kantineordning" (-353,307 = canteen recovery
-- INCOME), is mapped as an OPEX CONTRA-COST so it nets against the already-mapped
-- (V382) canteen cost 3587 "Kantineordning". Rationale: it is a staff cost-recovery,
-- not consulting revenue; treating it as REVENUE would inflate the revenue line,
-- whereas OPEX-contra keeps the canteen shown at its true net cost in EBITDA. This
-- mirrors the established precedent of income accounts that offset a cost being
-- classified OPEX (e.g. A/S 5255 "Forsikringsskader indtægt"). shared=0/category
-- "Delte services" match the canteen cost family (3586/3587).
--
-- Classification basis: each new row mirrors its nearest existing sibling in the SAME
-- company (cost_type / shared / salary / category) so the auto-classification stays
-- internally consistent. All are operating expenses -> OPEX (none are balance-sheet,
-- payroll, or project-delivery); 2180 is OPEX-contra (negative amount) as noted above.
--
--   Company     Acct  Name                         FY25/26   Mirror sibling
--   ----------  ----  ---------------------------  --------  ---------------------------------
--   A/S         2180  Salg kantineordning         -353,307  3587 Kantineordning / 5255 (OPEX contra)
--   A/S         3563  Kurser/uddannelse u/moms      +10,101  3560/3561/3562 Kurser (Delte serv.)
--   A/S         4009  Bespisning i egne lokaler     +13,643  4008/4010 (Salgsfremmende omk.)
--   A/S         5215  Software u/moms               +27,923  5214/5216/5217 Software (Øvrige adm.)
--   A/S         5295  Ej fradragsberettigede omk.      +972  5296/5297/5298 Diverse (Øvrige adm.)
--   Technology  2246  Kurser/uddannelse EU          +40,766  2245 Kurser/uddannelse (Delte serv.)
--   Technology  2247  Kurser/uddannelse UDLAND       +8,878  2245 Kurser/uddannelse (Delte serv.)
--   Technology  2248  Kurser/uddannelse u/moms          +62  2245 Kurser/uddannelse (Delte serv.)
--   Technology  3607  Software Udland                +4,488  3605/3606 Software (Øvrige adm.)
--   Technology  3608  Software u/moms               +16,380  3605/3606 Software (Øvrige adm.)
--   Technology  3641  Revisor - tidligere år        +29,800  3640 Revisor (Øvrige adm.)
--   Cyber       2246  Kurser/uddannelse EU          +24,224  2245 Kurser/uddannelse (Delte serv.)
--   Cyber       2247  Kurser/uddannelse UDLAND      +60,530  2245 Kurser/uddannelse (Delte serv.)
--   Cyber       2720  Forplejning med kunder           +273  2750/2753 Restaurationsbesøg (Delte serv.)
--   Cyber       3608  Software u/moms                +2,490  3605/3606 Software (Øvrige adm.)
--   Cyber       3641  Revisor - tidligere år        +26,500  3640 Revisor (Øvrige adm.)
--                                 operating cost  = +267,029
--                                 canteen recovery=  -353,307  (2180 OPEX contra)
--                                 -------------------------------------------------
--                                 net cost change =   -86,278  -> EBITDA +86,278 (immaterial)
--
-- Effect after the next fact-table refresh (sp_refresh_fact_tables /
--   OpexDistributionRefreshService rebuild the mats wholesale): the 15 operating-cost
--   accounts add +267,029 DKK of OPEX, the 2180 recovery nets -353,307 DKK against it,
--   for a net -86,278 DKK cost change across the three entities (EBITDA rises ~86k).
--
-- Idempotency: there is NO unique index on (companyuuid, account_code), so each INSERT
--   is guarded by NOT EXISTS (mirrors V382) — safe to re-run and safe if an account was
--   added manually via the admin UI before this migration runs.
--
-- Rollback:
--   DELETE FROM accounting_accounts WHERE companyuuid='d8894494-2fb4-4f72-9e05-e6032e6dd691' AND account_code IN ('2180','3563','4009','5215','5295');
--   DELETE FROM accounting_accounts WHERE companyuuid='44592d3b-2be5-4b29-bfaf-4fafc60b0fa3' AND account_code IN ('2246','2247','2248','3607','3608','3641');
--   DELETE FROM accounting_accounts WHERE companyuuid='e4b0a2a4-0963-4153-b0a2-a409637153a2' AND account_code IN ('2246','2247','2720','3608','3641');
-- =============================================================================

SET @as    := 'd8894494-2fb4-4f72-9e05-e6032e6dd691';  -- Trustworks A/S
SET @tech  := '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3';  -- Trustworks Technology ApS
SET @cyber := 'e4b0a2a4-0963-4153-b0a2-a409637153a2';  -- Trustworks Cyber Security ApS

SET @cat_delte_services  := '732fb626-fd28-49e5-87ce-b0739557a75c';  -- Delte services
SET @cat_salgsfremmende  := 'e3247f83-7583-44ad-9bc6-2e49d02c75ec';  -- Salgsfremmende omkostninger
SET @cat_oevrige_adm     := 'e8900f9f-dc8c-42de-a038-8477a1e5c18f';  -- Øvrige administrationsomk

-- -----------------------------------------------------------------------------
-- Trustworks A/S
-- -----------------------------------------------------------------------------
-- 2180 Salg kantineordning -> OPEX contra-cost (nets vs 3587 Kantineordning; precedent 5255, shared=0)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_delte_services, '2180', 'Salg kantineordning', 0, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '2180');

-- 3563 Kurser/uddannelse u/moms -> OPEX (mirror 3560/3561/3562, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_delte_services, '3563', 'Kurser/uddannelse u/moms', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '3563');

-- 4009 Bespisning i egne lokaler -> OPEX (mirror 4008/4010, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_salgsfremmende, '4009', 'Bespisning i egne lokaler', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '4009');

-- 5215 Software u/moms -> OPEX (mirror 5214/5216/5217, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_oevrige_adm, '5215', 'Software u/moms', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '5215');

-- 5295 Ej fradragsberettigede omkostninger -> OPEX (mirror 5296/5297/5298, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_oevrige_adm, '5295', 'Ej fradragsberettigede omkostninger', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '5295');

-- -----------------------------------------------------------------------------
-- Trustworks Technology ApS
-- -----------------------------------------------------------------------------
-- 2246 Kurser/uddannelse EU -> OPEX (mirror 2245, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_delte_services, '2246', 'Kurser/uddannelse EU', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '2246');

-- 2247 Kurser/uddannelse UDLAND -> OPEX (mirror 2245, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_delte_services, '2247', 'Kurser/uddannelse UDLAND', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '2247');

-- 2248 Kurser/uddannelse u/moms -> OPEX (mirror 2245, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_delte_services, '2248', 'Kurser/uddannelse u/moms', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '2248');

-- 3607 Software Udland -> OPEX (mirror 3605/3606, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_oevrige_adm, '3607', 'Software Udland', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '3607');

-- 3608 Software u/moms -> OPEX (mirror 3605/3606, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_oevrige_adm, '3608', 'Software u/moms', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '3608');

-- 3641 Revisor - tidligere år -> OPEX (mirror 3640 Revisor, shared=0)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @tech, @cat_oevrige_adm, '3641', 'Revisor - tidligere år', 0, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @tech AND account_code = '3641');

-- -----------------------------------------------------------------------------
-- Trustworks Cyber Security ApS
-- -----------------------------------------------------------------------------
-- 2246 Kurser/uddannelse EU -> OPEX (mirror 2245, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_delte_services, '2246', 'Kurser/uddannelse EU', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '2246');

-- 2247 Kurser/uddannelse UDLAND -> OPEX (mirror 2245, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_delte_services, '2247', 'Kurser/uddannelse UDLAND', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '2247');

-- 2720 Forplejning med kunder -> OPEX (mirror 2750/2753 Restaurationsbesøg, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_delte_services, '2720', 'Forplejning med kunder', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '2720');

-- 3608 Software u/moms -> OPEX (mirror 3605/3606, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_oevrige_adm, '3608', 'Software u/moms', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '3608');

-- 3641 Revisor - tidligere år -> OPEX (mirror 3640 Revisor, shared=0)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_oevrige_adm, '3641', 'Revisor - tidligere år', 0, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '3641');
