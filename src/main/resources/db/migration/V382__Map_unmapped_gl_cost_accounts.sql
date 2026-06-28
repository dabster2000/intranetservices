-- =============================================================================
-- Migration V382: Map unmapped GL accounts (F18 — executive-dashboard EBITDA audit)
--
-- Problem (verified against production twservices4 + e-conomic, 2026-06-28):
--   Five A/S GL accounts carry FY25/26 activity but are ENTIRELY ABSENT from
--   accounting_accounts. Because fact_opex / fact_opex_distribution_mat classify
--   cost by JOINing accounting_accounts, the four COST accounts are silently
--   dropped from the cost side, overstating headline (group) EBITDA by 751,493 DKK
--   (~2.4% of the group's 31.0M). See:
--   docs/superpowers/analysis/2026-06-28-executive-dashboard-cost-revenue-audit-verified.md (F18)
--
--   Account  Name                          FY25/26 BOOKED   Classification
--   -------  ----------------------------  --------------   --------------
--   3561     Kurser EU (Course EU)            +93,926.49     OPEX  (training)
--   3562     Kurser udland (Course non-EU)   +60,792.79     OPEX  (training)
--   3587     Kantineordning (staff canteen) +441,633.72     OPEX  (staff/admin)
--   4010     Rekruttering (JOBINDEX/NS)     +155,140.00     OPEX  (recruitment)
--                                           -----------
--                              cost total =  751,493.00     -> lands in OPEX cost
--
--   2106     Konsulenthonorar Energinet   -4,177,486.13     REVENUE (NOT a cost)
--
-- Why 2106 = REVENUE (critical): its GL amount is NEGATIVE (income). If it were
--   ever classified as a cost account, that negative would SUBTRACT from cost and
--   INFLATE EBITDA by ~4.18M. Mapping it REVENUE keeps it excluded from the cost
--   views; its revenue already flows via the Energinet PHANTOM / self-billed path,
--   so there is no double-count and zero EBITDA change from this row.
--
-- Classification basis: each new row mirrors its nearest existing A/S sibling
--   (cost_type / salary / shared / category) so the auto-classification model stays
--   internally consistent:
--     3561/3562  <- 3560 "Kurser/udd./konferencer"  (OPEX, salary=0, shared=1, Delte services       732fb626-fd28-49e5-87ce-b0739557a75c)
--     3587       <- 3586 "Frokostordning"            (OPEX, salary=0, shared=0, Delte services       732fb626-fd28-49e5-87ce-b0739557a75c)
--     4010       <- 4003 "Annoncer, reklame ..."     (OPEX, salary=0, shared=1, Salgsfremmende omk.   e3247f83-7583-44ad-9bc6-2e49d02c75ec)
--     2106       <- 2104 "Konsulenthonorar Vattenf." (REVENUE, salary=0, shared=0, Varesalg           fa83ddc1-52a4-44cb-9717-06a64b01747a)
--   None of the four cost accounts is balance-sheet (IGNORE), payroll (SALARIES),
--   or project-delivery (DIRECT_COSTS); all are operating expenses -> OPEX.
--
-- Effect after the next fact-table refresh (sp_refresh_fact_tables /
--   OpexDistributionRefreshService rebuild the mats wholesale): the 751,493 OPEX
--   lands in the cost side; group BOOKED operating result moves toward the
--   e-conomic group "Resultat foer renter" of -31,000,225.
--
-- Idempotency: there is NO unique index on (companyuuid, account_code), so each
--   INSERT is guarded by NOT EXISTS to avoid creating duplicate rows if an account
--   was added manually (admin UI) before this migration runs.
--
-- Rollback:
--   DELETE FROM accounting_accounts
--   WHERE companyuuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'
--     AND account_code IN ('3561','3562','3587','4010','2106');
-- =============================================================================

SET @company := 'd8894494-2fb4-4f72-9e05-e6032e6dd691';  -- Trustworks A/S
SET @cat_delte_services      := '732fb626-fd28-49e5-87ce-b0739557a75c';  -- Delte services
SET @cat_salgsfremmende      := 'e3247f83-7583-44ad-9bc6-2e49d02c75ec';  -- Salgsfremmende omkostninger
SET @cat_varesalg            := 'fa83ddc1-52a4-44cb-9717-06a64b01747a';  -- Varesalg (revenue)

-- 3561 Kurser EU -> OPEX (mirror 3560)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_delte_services, '3561', 'Kurser EU', 1, 0, 'OPEX'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '3561');

-- 3562 Kurser udland -> OPEX (mirror 3560)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_delte_services, '3562', 'Kurser udland', 1, 0, 'OPEX'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '3562');

-- 3587 Kantineordning -> OPEX (mirror 3586 Frokostordning)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_delte_services, '3587', 'Kantineordning', 0, 0, 'OPEX'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '3587');

-- 4010 Rekruttering -> OPEX (mirror 4003)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_salgsfremmende, '4010', 'Rekruttering', 1, 0, 'OPEX'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '4010');

-- 2106 Konsulenthonorar Energinet -> REVENUE (mirror 2104); excluded from cost views.
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @company, @cat_varesalg, '2106', 'Konsulenthonorar Energinet', 0, 0, 'REVENUE'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @company AND account_code = '2106');
