-- =============================================================================
-- Migration V540: Close the FY2025/2026 GL-mapping gaps the gate can no longer see
--
-- Follow-up to V539 (A/S 3590 Personalerejser). Same class of defect, different
-- fiscal year. Investigated 2026-08-30 against production twservices4 and the
-- live e-conomic chart of accounts for all three entities.
--
-- WHY THESE WERE NEVER ALERTED. UnmappedGlAccountCheck windows on
--   DateUtils.getCurrentFiscalStartDate(), so it only ever scans the CURRENT
--   fiscal year. When the window rolled to FY2026/2027 on 2026-07-01, every
--   unmapped account carrying FY2025/2026 activity became permanently invisible
--   to the gate. These are late/year-end postings that landed after V507 was
--   authored on 2026-08-17 — which is why V507's "expect zero rows" verification
--   block is now stale: re-running it for FY2025/2026 today returns eight rows.
--   All eight are INSIDE their company's mapped [MIN..MAX] band, so this is not
--   the band limitation — it is purely the current-fiscal-year window.
--
-- SCOPE. Six of the eight in-band accounts are mapped here. Cyber 1070
--   "Administration Technology" (-81,842.59) and Cyber 1370 "Administration TW
--   Technology" (+90,749.76) are DELIBERATELY EXCLUDED: they are intercompany
--   administration accounts, and classifying them without first reconciling
--   against intercompany_account_mapping / fact_intercompany_settlement risks
--   double-counting the same internal flow on both the settlement path and the
--   cost path. That is a Finance decision, not a mirror of a sibling row.
--
--   A seventh row, Cyber 3795, is added that the gate structurally cannot see —
--   see the band section below. It is the only genuine operating cost the
--   [MIN..MAX] band has ever hidden.
--
-- THE ROWS. Each mirrors its nearest already-mapped sibling in the SAME company,
--   per the V382/V390/V507 house pattern. e-conomic's own account name is used
--   as account_description, and e-conomic's `accountType` + P&L block structure
--   is the authority for whether an account belongs inside EBITDA at all.
--
--   Co   Acct  e-conomic name                       FY25/26     cost_type  Mirror / basis
--   ---  ----  -----------------------------------  ----------  ---------  --------------------------------
--   A/S  2186  Bortfaldne gældsforpligtelser         -2,178.00  REVENUE    2185 (V507), Varesalg block
--   A/S  3592  Andre personaleomkostninger           +2,688.87  OPEX       3589/3591, Lønninger block
--   A/S  5269  Revisor                              +55,000.00  OPEX       5268 Advokat / 5270, Administration
--   TWC  1080  Bortfaldne gældsforpligtelser         -1,787.50  REVENUE    1010-1030, Omsætning block
--   TWC  2248  Kurser/uddannelse u/moms              +4,946.89  OPEX       TWC 2245/2246/2247 (V390 mapped 2246/2247)
--   TWC  2270  Andre personaleomkostninger           +1,053.00  OPEX       TWC 2250/2255/2258/2260/2280 (9/9 shared=1)
--   TWC  3795  Ej fradragsberettigede omkostninger     +309.00  OPEX       TWC 3780 "Diverse m moms"; A/S 5295 (V390)
--                                    OPEX added   =  +63,997.76  (raw signed GL)
--
--   cost_type OPEX is block-evidenced, not assumed: A/S 3501..3599 is 32/33 OPEX
--   (sole SALARIES is 3502 "Løn AM-grundlag", salary=1); TWC 2200..2299 is 21/22
--   OPEX (sole SALARIES is 2210 "Lønninger", salary=1); TWC 3700..3799 is 3/3
--   OPEX. e-conomic places all of them above the EBITDA line — A/S 3592 and 5269
--   under 6199 "Resultat før renter"; TWC 2270/2248/3795 above 3800 "Resultat før
--   afskrivninger" and 4000 "Resultat før renter" — so none is a financial item
--   or depreciation.
--
-- IMPACT — TWO DIFFERENT PER-COMPANY ANSWERS. READ BOTH BEFORE VERIFYING.
--   Only the five OPEX rows move anything. The group figure is ~+63,997.76 DKK of
--   FY2025/2026 OPEX, so group EBITDA falls by about that much, against a FY2025
--   fact_opex_mat OPEX base of 34,369,447.89 — 0.19%.
--
--   Do NOT expect the number to land to the øre. fact_opex emits
--   ROUND(bucket_total * practice_weight, 2) per practice row, and the rounded
--   allocations do not re-sum to the bucket: measured over all 157 FY2025 buckets,
--   44 carry a ±0.01 residue (net FY2025 residue -0.06 DKK; raw GL
--   127,785,501.72 vs the live view 127,785,501.66). Expect the same order of
--   noise here — raw GL +63,997.76, fact_opex_mat +63,997.77,
--   fact_opex_distribution_mat +63,997.75. That residue is not a mapping defect.
--
--   BY ORIGIN COMPANY (what fact_opex_mat stores — NOT an EBITDA figure):
--     A/S +57,688.88 (3592 + 5269), Cyber +6,308.89 (2248 + 2270 + 3795), TWT 0.
--   BY PAYER COMPANY (what fact_opex_distribution_mat stores, and what
--   CxoFinanceService actually serves to the Annual P&L EBITDA chart):
--     A/S ~+61,180, Technology ~+1,710, Cyber ~+1,110.
--   The two differ because 5269 is shared=0 and stays wholly with A/S, while the
--   other four rows (8,997.76 combined) are shared=1 and are redistributed across
--   all three entities by each month's consultant ratio (V197 gl_shared /
--   IntercompanyCalcService). A reviewer checking the Cyber EBITDA line against
--   "+6,308.89" will find roughly +1,110 and wrongly conclude the migration
--   failed. Check origin figures in fact_opex_mat and payer figures in
--   fact_opex_distribution_mat, not one against the other.
--
-- WHY 2186 AND 1080 ARE REVENUE, NOT AN OPEX CONTRA-COST. Both are negative
--   (income) and both sit in their entity's revenue block — A/S inside
--   2100 [heading] "Varesalg", Cyber inside 1001 [heading] "Omsætning" ..
--   1099 [totalFrom] "Omsætning i alt". Two precedents exist in the A/S block and
--   they disagree, so the discriminator matters:
--     * V507 mapped 2185 "Salg af IT-udstyr" REVENUE.
--     * V390 mapped 2180 "Salg kantineordning" OPEX-contra — but only because it
--       is a cost RECOVERY offsetting one specific, identified, already-mapped
--       cost account (3587 Kantineordning), so netting restores that one pool to
--       its true net cost.
--   The operative rule is V390's condition, and 2186/1080 fail it: there is no
--   cost account to net against. Verified by pulling every posting and its
--   voucher-mates in production — all four are balanced two-line entries whose
--   sole counterpart is a per-employee BALANCE-SHEET clearing account
--   (e-conomic accountType=status), never a cost account:
--     A/S  v450327  2186 -1,700.00 <-> 9747 +1,700.00  "Kor. primo udlæg"  (Rebecca Mandrup)
--     A/S  v450334  2186   -339.00 <-> 9799   +339.00  "Kor. udlæg dif."   (Camilla Alm)
--     A/S  v450390  2186   -139.00 <-> 9789   +139.00  "Kor. udlæg dif."   (Jacob Boeskov)
--     TWC  v292     1080 -1,787.50 <-> 8011 +1,787.50  "Kor. udlæg dif."   (Niklas Rendboe)
--   A/S 3592 carries superficially similar "Kor. udlæg dif." texts but is NOT the
--   other half of these entries: disjoint vouchers, disjoint people, and each 3592
--   row is its own balanced pair against that person's clearing account with the
--   sign mirrored. 2186 and 3592 are the two sign-directions of the same
--   reconciliation routine applied to different employees. Those four rows are the
--   entire lifetime history of both accounts, so no other fiscal year is affected.
--
--   EFFECT OF THE TWO REVENUE ROWS: none on any figure. fact_opex and
--   fact_opex_distribution_mat consume only cost_type IN ('OPEX','SALARIES').
--   Note the narrow reason this is safe, because it does NOT generalise:
--   CxoFinanceService.queryOpexEntries (the EBITDA source export) filters on
--   CATEGORY — groupname NOT IN ('Varesalg','Direkte omkostninger','Igangvaerende
--   arbejde') — with account_code in [3000,6000) and NO cost_type predicate. 2186
--   and 1080 are both below 3000, so that reader never sees them either. For an
--   account inside 3000-5999 the categoryuuid would matter regardless of
--   cost_type. cost_type is also surfaced to humans in the accounting admin UI via
--   AccountingResource.listAccounts(), so the label is not inert even when no
--   number moves.
--
--   shared MUST BE 0 ON BOTH REVENUE ROWS, and this is not cosmetic. V197's
--   gl_shared CTE filters on `WHERE aa.shared = 1` with NO cost_type predicate and
--   aggregates SUM(ABS(fd.amount)). A shared=1 row of ANY cost_type therefore
--   enters the live intercompany distribution as a POSITIVE cost. Their Varesalg
--   siblings are all shared=0, which is both the mirror and the safe value.
--
-- A/S 5269 "Revisor" — AND A STALE-DESCRIPTION TRAP THAT WAS CHECKED FIRST.
--   The posting is "Afsat revisor 2025/26", 55,000.00, expensedate 2026-06-01,
--   BOOKED — the accrued audit fee for the year just closed, and the largest
--   single item in this backlog.
--   accounting_accounts already holds an A/S row for code 5270 whose
--   account_description reads "Revisor", which looks like it might already be this
--   mapping under a different number. It is not. In e-conomic 5269 = "Revisor" and
--   5270 = "Revisor - tidligere år"; the DB row is carrying a stale label, and the
--   fact_opex join is on the account NUMBER (fd.accountnumber = aa.account_code),
--   never the description. Both accounts have independent production activity —
--   5269: 1 entry / 55,000.00; 5270: 10 entries / 134,277.50 spanning 2024-12 to
--   2026-07 — so they are distinct accounts and adding 5269 cannot double-count.
--   (For the record: 20 of the 328 accounting_accounts rows across the three
--   entities have descriptions that no longer match e-conomic, e.g. A/S 3587
--   "Kantineordning" vs "Kantinetilskud", 5271 "Rådgivende assistance" vs
--   "Bogføringsassistance". Scattered staleness, not a systematic renumbering.
--   Cosmetic only — the join never reads the description. Not corrected here.)
--
--   shared=0 for 5269 follows the professional-services pattern that IS consistent
--   in this data: A/S 5268 "Advokat" shared=0, the A/S 5270 row shared=0, A/S 5275
--   "Konsulentbistand" shared=0, and V390 set both Technology and Cyber 3641
--   "Revisor - tidligere år" shared=0 mirroring 3640 "Revisor". Contrast the
--   surrounding generic admin accounts 5295-5298, which are shared=1.
--
-- shared ON THE FOUR OPEX STAFF/ADMIN ROWS — one derived, three judgement calls.
--   `shared` is the cross-company ALLOCATION switch (V197), not an ownership
--   label, and it never moves group EBITDA — only the split between entities and
--   the team dashboard.
--     TWC 2270  shared=1  DERIVED. TWC 2245-2280 is 9/9 shared=1, unanimous.
--     TWC 2248  shared=1  DERIVED. TWC's own 2245/2246/2247 are all shared=1
--                         (V390 set 2246/2247). The Technology 2248 twin agrees
--                         but is a cross-company sibling, so it is corroboration,
--                         not the basis.
--     A/S 3592  shared=1  JUDGEMENT. Its neighbours disagree — 3591 is shared=1,
--                         3593 is shared=0 — and the A/S Lønninger block splits
--                         25x1 / 8x0 with no documented rule. Chosen because the
--                         collective-staff family 3585/3588/3589/3591 is uniformly
--                         shared=1, as V539 recorded for 3590 in the same block.
--     TWC 3795  shared=1  JUDGEMENT. Mirrors TWC 3780 "Diverse m moms" (shared=1),
--                         the direct TWC analogue of the A/S 5296/5297/5298
--                         "Diverse" rows V390 used to set A/S 5295. COUNTER-
--                         PRESSURE: TWC 2230 "KM penge" is shared=0, as is its A/S
--                         twin 3570. At 309.00 DKK the flag can misallocate at
--                         most ~200 DKK between entities.
--
-- BAND SIDE EFFECT — ONE CEILING MOVES, DELIBERATELY.
--   Six of the seven rows are inside their company's existing band (A/S
--   2101..6160; Cyber 1010..3780), so those bounds do not move.
--   Cyber 3795 is the exception: it lifts Cyber's ceiling from 3780 to 3795. That
--   is the point — 3795 is the ONE genuine operating cost the band has ever hidden
--   (Cyber's operating P&L runs to 3799 "Administrationsomkostninger i alt", above
--   the 4000 "Resultat før renter" line, so 3795 is inside EBITDA, and the
--   fact_opex INNER JOIN drops it whether or not the gate can see it). Verified
--   against production before writing this: NO Cyber account numbered 3781..3799
--   other than 3795 itself carries any finance_details activity in any year, so
--   the wider band surfaces no new findings.
--   A/S's ceiling is deliberately left at 6160 — see V539 on why 6875 stays
--   unmapped.
--
-- FY2025/2026 IS A CLOSED YEAR — checked before restating it. The only frozen
--   artefact that consumes company profit is locked_bonus_pool_data, and it holds
--   exactly one row: fiscal_year 2024, locked 2025-10-08 by michael.bruun. FY2025
--   is NOT locked, so this restatement invalidates nothing — and landing it before
--   the FY2025 pool is locked is the correct ordering.
--
-- KNOWN PRE-EXISTING DEFECT, NOT FIXED HERE (affects where 5269 and 3795 land):
--   the fact_opex category CASE tests ac.groupname = 'Øvrige administrationsomk.
--   i alt', but the actual value in accounting_categories for e8900f9f is 'Øvrige
--   administrationsomk'. That arm never matches, so the category falls through to
--   ELSE and lands in cost_center GENERAL / expense_category OTHER_OPEX rather
--   than ADMIN. Totals are unaffected; only the bucket label is. Out of scope.
--
-- Effect after the next fact-table refresh. Both mats must be rebuilt and both
--   checked: `fact_opex` -> `fact_opex_mat` (SQL path) and
--   OpexDistributionRefreshService -> `fact_opex_distribution_mat` (the Annual
--   P&L EBITDA chart). They run as different jobs at different times.
--
-- Idempotency: there is no unique index on (companyuuid, account_code), so each
--   INSERT is guarded by NOT EXISTS — matching V382/V390/V507/V539. Verified
--   2026-08-30 that none of the seven rows currently exists.
--
-- Rollback:
--   DELETE FROM accounting_accounts WHERE companyuuid='d8894494-2fb4-4f72-9e05-e6032e6dd691' AND account_code IN ('2186','3592','5269');
--   DELETE FROM accounting_accounts WHERE companyuuid='e4b0a2a4-0963-4153-b0a2-a409637153a2' AND account_code IN ('1080','2248','2270','3795');
-- =============================================================================

SET @as    := 'd8894494-2fb4-4f72-9e05-e6032e6dd691';  -- Trustworks A/S
SET @cyber := 'e4b0a2a4-0963-4153-b0a2-a409637153a2';  -- Trustworks Cyber Security ApS

SET @cat_varesalg       := 'fa83ddc1-52a4-44cb-9717-06a64b01747a';  -- Varesalg (revenue)
SET @cat_delte_services := '732fb626-fd28-49e5-87ce-b0739557a75c';  -- Delte services
SET @cat_oevrige_adm    := 'e8900f9f-dc8c-42de-a038-8477a1e5c18f';  -- Øvrige administrationsomk

-- -----------------------------------------------------------------------------
-- Trustworks A/S
-- -----------------------------------------------------------------------------
-- 2186 Bortfaldne gældsforpligtelser -> REVENUE (mirror 2185; Varesalg block, negative income)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_varesalg, '2186', 'Bortfaldne gældsforpligtelser', 0, 0, 'REVENUE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '2186');

-- 3592 Andre personaleomkostninger -> OPEX (mirror 3589/3591, Lønninger block, shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_delte_services, '3592', 'Andre personaleomkostninger', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '3592');

-- 5269 Revisor -> OPEX (mirror 5268 Advokat / 5270; professional services, shared=0)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @as, @cat_oevrige_adm, '5269', 'Revisor', 0, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @as AND account_code = '5269');

-- -----------------------------------------------------------------------------
-- Trustworks Cyber Security ApS
-- -----------------------------------------------------------------------------
-- 1080 Bortfaldne gældsforpligtelser -> REVENUE (mirror 1010-1030; Omsætning block, negative income)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_varesalg, '1080', 'Bortfaldne gældsforpligtelser', 0, 0, 'REVENUE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '1080');

-- 2248 Kurser/uddannelse u/moms -> OPEX (mirror 2245/2246/2247; V390 mapped the Technology twin)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_delte_services, '2248', 'Kurser/uddannelse u/moms', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '2248');

-- 2270 Andre personaleomkostninger -> OPEX (mirror 2250/2255/2258/2260/2280, all shared=1)
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_delte_services, '2270', 'Andre personaleomkostninger', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '2270');

-- 3795 Ej fradragsberettigede omkostninger -> OPEX (mirror A/S 5295 per V390; TWC 3770/3780)
-- NOTE: this row lifts Cyber's UnmappedGlAccountCheck band ceiling 3780 -> 3795, deliberately.
INSERT INTO accounting_accounts (uuid, companyuuid, categoryuuid, account_code, account_description, shared, salary, cost_type)
SELECT UUID(), @cyber, @cat_oevrige_adm, '3795', 'Ej fradragsberettigede omkostninger', 1, 0, 'OPEX'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts WHERE companyuuid = @cyber AND account_code = '3795');

-- Verification (READ-ONLY, run after deploy):
--   SELECT companyuuid, account_code, account_description, cost_type, shared, salary
--     FROM accounting_accounts
--    WHERE (companyuuid='d8894494-2fb4-4f72-9e05-e6032e6dd691' AND account_code IN ('2186','3592','5269'))
--       OR (companyuuid='e4b0a2a4-0963-4153-b0a2-a409637153a2' AND account_code IN ('1080','2248','2270','3795'));
--   -- Expect: 7 rows.
--
--   -- UnmappedGlAccountCheck.detect() semantics, run for FY2025/2026:
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
--   -- Expect: exactly 2 rows — Cyber 1070 and 1370, the intercompany accounts
--   --         deliberately left out of scope. Before this migration: 8 rows.
--
--   -- Cost restored, after BOTH mats are rebuilt. Allow ±0.01 per touched
--   -- (company, month) bucket for the practice-allocation rounding described above.
--
--   -- (a) BY ORIGIN COMPANY:
--   SELECT company_id, cost_type, ROUND(SUM(opex_amount_dkk),2)
--     FROM fact_opex_mat WHERE fiscal_year = 2025 GROUP BY 1, 2;
--   -- Expect A/S        OPEX ~29,823,281.56  (was 29,765,592.68, +57,688.88)
--   --        Cyber      OPEX  ~1,720,897.03  (was  1,714,588.14,  +6,308.89)
--   --        Technology OPEX   2,889,267.07  (unchanged)
--
--   -- (b) BY PAYER COMPANY — this is the EBITDA-visible split, and it does NOT
--   --     match (a). Four of the five OPEX rows are shared=1 and are redistributed.
--   SELECT company_id, ROUND(SUM(opex_amount_dkk),2)
--     FROM fact_opex_distribution_mat WHERE fiscal_year = 2025 GROUP BY 1;
--   -- Expect roughly A/S +61,180, Technology +1,710, Cyber +1,110 versus the
--   -- pre-migration totals; group ~+63,997.75. Do NOT expect Cyber +6,308.89 here.
