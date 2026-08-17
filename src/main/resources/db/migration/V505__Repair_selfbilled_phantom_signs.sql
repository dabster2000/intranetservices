-- =============================================================================
-- Migration V505: Repair the 29 self-billed PHANTOM rows whose sign was not
--                 derived from the e-conomic entry
--
-- ** THIS MIGRATION MUTATES PRODUCTION DATA. It negates invoiceitems.rate on 29
--    rows and nothing else. Obtain explicit human approval before deploying. **
--
-- Companion to the D1 code fix in EconomicRevenueImportService: the importer now
-- derives the stored amount as the exact negation of the e-conomic entry amount
-- (deriveStoredAmount), guarded so neither abs() nor the raw signed amount can
-- come back. That fixes every FUTURE import; it does nothing for rows already
-- written, which is what this migration is for.
--
-- WHY NOT JUST RE-RUN THE IMPORT (as the findings document suggests):
--   Because the importer is idempotent on economics_entry_number, and that is
--   exactly what defeats the repair. EconomicRevenueImportService.refresh()
--   calls findExistingByEntryNumber() before inserting and skips the voucher as
--   LAYER_3_ENTRY_COLLISION when a row for that entry already exists. Re-running
--   the import over 2026-05-01..2026-06-30 therefore skips all 29 rows and
--   changes nothing. The only re-run that would work is delete-then-reimport,
--   which destroys and recreates 29 invoice+item pairs (new uuids, new audit
--   trail) to change one number on each. A targeted UPDATE is smaller, exactly
--   reversible, and leaves the documents' identities intact.
--
-- POPULATION (measured against production + the e-conomic API, 2026-08-17):
--   All 184 FY2025/26 A/S PHANTOMs were linked to their e-conomic entry by
--   economics_entry_number — zero unmatched — and each stored amount compared
--   against the negated voucher net the fixed importer would now write:
--       23 rows stored NEGATIVE against an e-conomic credit (a sale booked as
--          negative revenue)                                    +5,917,528.88
--        6 rows stored POSITIVE against an e-conomic debit (a reversal booked
--          as revenue)                                          -1,245,658.80
--       --                                                      -------------
--       29 rows                                        net      +4,671,870.08
--   For all 29, plain negation of the stored value is the exact repair
--   (verified row by row: |stored| == |voucher net| in every case), so this
--   migration multiplies rate by -1 rather than writing a computed constant.
--   No row outside FY2025/26 is affected.
--
-- EFFECT ON REPORTED REVENUE:
--   A/S PHANTOM revenue, FY2025/26 (work period, status CREATED):
--       16,864,151.62  ->  21,536,021.70
--   May 2026   -837,313.16 -> +837,313.16   (no longer a negative revenue month)
--   June 2026 -1,943,851.28 -> +1,943,851.28
--   After the repair, no month of FY2025/26 is negative — the second half of the
--   D1 acceptance criterion.
--
--   The first half of that criterion cannot be met and should not be forced:
--   it asks for the PHANTOM total to equal e-conomic's booked total on accounts
--   2104+2106+2130+2180+2185 = 22,694,237.61. The 1,158,215.91 difference is
--   revenue that is deliberately NOT a PHANTOM, because the intranet already
--   holds it as a real invoice — most of it the 9 SEK "The Real Word AB"
--   invoices (995,530.94), which the importer's Layer-4 text dedup correctly
--   skips. Forcing the PHANTOM population to 22,694,238 would double-count them.
--   21,536,021.70 + 1,158,215.91 = 22,694,237.61 exactly.
--
-- Idempotency: NOT idempotent — this multiplies by -1, so running it twice
--   restores the defect. Flyway runs a versioned migration once; do not re-apply
--   by hand. Verification queries below detect the state either way.
--
-- Rollback:
--   Re-run the same 29 UPDATE statements (negation is its own inverse).
-- =============================================================================

UPDATE invoiceitems SET rate = -rate WHERE uuid = 'b323cf58-7a00-4a73-88aa-b72774a7b424';  -- entry 124798, 2025-08-01: 106560.00 -> -106560.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '3f4bf416-30db-4048-9cf8-91b2f2ca4d0a';  -- entry 124801, 2025-08-01: 102010.00 -> -102010.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '4eccdc0b-94f4-48a1-a621-b9ae5d0451d3';  -- entry 124813, 2025-08-01: 97970.00 -> -97970.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'f0bf03b0-9c93-4488-b826-650378364fb7';  -- entry 124457, 2025-08-11: 10419.40 -> -10419.40
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'a41fe49c-bc03-4544-b3c4-26e9ec44e57e';  -- entry 130577, 2025-10-01: 128270.00 -> -128270.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'b739983e-ee6e-4d0e-b4d1-ed6293ca1b2a';  -- entry 148321, 2026-05-04: -240660.97 -> 240660.97
UPDATE invoiceitems SET rate = -rate WHERE uuid = '4375dfbc-775d-4e66-a6c5-499934a453a0';  -- entry 148324, 2026-05-04: -132651.88 -> 132651.88
UPDATE invoiceitems SET rate = -rate WHERE uuid = '32a9e263-1285-46d9-aee7-962eb62f33fa';  -- entry 148327, 2026-05-04: -175614.66 -> 175614.66
UPDATE invoiceitems SET rate = -rate WHERE uuid = '886914b4-d24a-410f-b1e3-1d00eecc0d7f';  -- entry 148330, 2026-05-04: -195569.55 -> 195569.55
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'c590e56e-947c-4afd-8e2d-d97058792574';  -- entry 150396, 2026-05-31: -92816.10 -> 92816.10
UPDATE invoiceitems SET rate = -rate WHERE uuid = '7ad80a18-3d2c-4a5a-8003-d93d4434e936';  -- entry 151622, 2026-06-01: -124320.00 -> 124320.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '8a00bfdc-41e8-4c03-b9f4-e4407d8567e1';  -- entry 151625, 2026-06-01: -133200.00 -> 133200.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'c81a4207-7028-494f-b47f-1ef1e0dd31e8';  -- entry 151628, 2026-06-01: -153520.00 -> 153520.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '46989e00-ca61-4b2e-8133-fbf46b7682a2';  -- entry 151631, 2026-06-01: -186480.00 -> 186480.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'f3f06899-a86f-4e88-bfdc-fc818e3adc81';  -- entry 151634, 2026-06-01: -120990.00 -> 120990.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '22480e4d-8c2b-4f81-9119-745efbbc3faf';  -- entry 151637, 2026-06-01: -112200.00 -> 112200.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'aff9b1b2-90c2-40f5-8e8a-8e27262fe5a2';  -- entry 151640, 2026-06-01: -42180.00 -> 42180.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'ee52be08-b287-49e3-bbfb-210d8c6e9552';  -- entry 151643, 2026-06-01: -129870.00 -> 129870.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '7bdb90d0-37db-4a2c-a2ed-4c8f8576fda9';  -- entry 151646, 2026-06-01: -165600.00 -> 165600.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '63fb11bb-a4f3-4803-9912-62282d18b157';  -- entry 151596, 2026-06-02: -8907.99 -> 8907.99
UPDATE invoiceitems SET rate = -rate WHERE uuid = '986a2e6a-d9a5-4141-b119-7e68af0ca88b';  -- entry 151599, 2026-06-02: -149954.04 -> 149954.04
UPDATE invoiceitems SET rate = -rate WHERE uuid = '75d436ce-d32d-4525-92e0-5a711348f16c';  -- entry 151602, 2026-06-02: -5767.45 -> 5767.45
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'b0a66e9f-f42b-414d-b99c-4349723cf8e5';  -- entry 151607, 2026-06-02: -131603.12 -> 131603.12
UPDATE invoiceitems SET rate = -rate WHERE uuid = '1086a612-a6b8-4c60-b14a-6588b9809fb7';  -- entry 151610, 2026-06-02: -157798.68 -> 157798.68
UPDATE invoiceitems SET rate = -rate WHERE uuid = '1daae864-ff0e-431b-b328-f69addb7dcae';  -- entry 151613, 2026-06-10: -145440.00 -> 145440.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '6d0ec59c-67d3-468e-a79e-0446be79c3a4';  -- entry 151616, 2026-06-10: -51700.00 -> 51700.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'c30b199d-e660-4bf5-9798-40e4011a3659';  -- entry 151619, 2026-06-10: -168720.00 -> 168720.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = '3809580d-ec51-4bbe-84f9-b55df3561f0a';  -- entry 148852, 2026-06-24: 177600.00 -> -177600.00
UPDATE invoiceitems SET rate = -rate WHERE uuid = 'ff3d2476-2121-4c1c-b982-c6dc0e5917ec';  -- entry 148855, 2026-06-24: -133200.00 -> 133200.00

-- 29 rows.

-- Verification:
--   SELECT ROUND(SUM(ii.rate*ii.hours),2) AS phantom_revenue
--     FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
--    WHERE i.type='PHANTOM' AND i.status='CREATED'
--      AND i.companyuuid='d8894494-2fb4-4f72-9e05-e6032e6dd691'
--      AND (i.year*100+i.month) BETWEEN 202507 AND 202606;
--   -- Expect: 21536021.70   (was 16864151.62)
--
--   SELECT i.year, i.month, ROUND(SUM(ii.rate*ii.hours),2) AS dkk
--     FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
--    WHERE i.type='PHANTOM' AND i.status='CREATED'
--      AND i.companyuuid='d8894494-2fb4-4f72-9e05-e6032e6dd691'
--      AND (i.year*100+i.month) BETWEEN 202507 AND 202606
--    GROUP BY i.year, i.month HAVING dkk < 0;
--   -- Expect: zero rows. Before the repair this returned 2026-05 and 2026-06.
