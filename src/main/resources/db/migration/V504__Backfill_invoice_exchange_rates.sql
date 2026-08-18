-- =============================================================================
-- Migration V504: Backfill exchange_rate on the 73 historical non-DKK invoices
--
-- ** THIS MIGRATION MUTATES PRODUCTION DATA. It writes one column on 73 invoice
--    rows and nothing else. Obtain explicit human approval before deploying. **
--
-- Companion to V503, which added invoices.exchange_rate and taught the two
-- revenue views to apply it. Without this backfill V503 changes nothing: every
-- historical foreign-currency invoice keeps a NULL rate, COALESCEs to 1, and is
-- still counted at face value as kroner — the defect V503 exists to fix.
--
-- Source of every rate below (read-only GET, 2026-08-17): the rate e-conomic
-- ACTUALLY BOOKED for that specific invoice, computed as
-- amountInBaseCurrency / amount on the entry carrying the invoice's number.
-- Not a published mid-market rate and not a monthly average — the realised
-- booking rate, so each invoice reconciles to its own GL entry by construction.
-- 70 of the 73 were matched to their own booked entry that way.
--
-- The 3 exceptions are Technology 70054/70055/70056 (2024-07-31, iptiQ). Those
-- were booked in e-conomic AS DKK — the EUR face value posted as kroner — and
-- then reversed with the text "ført forkert". They therefore have no correct
-- booking to derive a rate from, so they take the same-month realised EUR rate
-- (7.46189600, from A/S invoice 17256 booked 2024-07-31). Flagged inline below.
-- This is a FY2024/25 bookkeeping problem in e-conomic, not a code defect; the
-- alternative was to leave 33,648.75 EUR counted as kroner indefinitely.
--
-- Population: SELECT COUNT(*) FROM invoices WHERE COALESCE(currency,'DKK')<>'DKK'
--   -> 73 rows, two currencies, three clients:
--        Technology EUR (iptiQ Group Holding, Zurich)   50 docs  2024-01..2025-12
--        A/S        EUR                                  9 docs  2024-05..2024-09
--        A/S        SEK (The Real Word AB)              14 docs  2025-02..2026-03
--   Every other invoice in the table is DKK and is not touched.
--
-- Effect on reported revenue once fact_company_revenue_mat is refreshed:
--   FY2025/26  Technology EUR   170,200 face -> 1,270,765.52   +1,100,565.52
--   FY2025/26  A/S SEK        1,449,000 face ->   995,530.94     -453,469.06
--   FY2025/26  net                                              +647,096.46
--   Earlier years move too (FY2024/25 EUR at ~7.458 rather than 1.0).
--
-- Idempotency: each UPDATE targets one immutable invoice uuid and writes a
-- constant, so re-running is a no-op. Rows are addressed by uuid, never by
-- invoicenumber — invoicenumber is not unique per company.
--
-- Rollback:
--   UPDATE invoices SET exchange_rate = NULL WHERE COALESCE(currency,'DKK') <> 'DKK';
-- =============================================================================

UPDATE invoices SET exchange_rate = 7.45412304 WHERE uuid = '45e4180d-9d25-4958-b495-4e27e732382b';  -- Technology 70012 EUR 2024-01-31
UPDATE invoices SET exchange_rate = 7.45412319 WHERE uuid = 'eb5a7b82-12e8-4559-a343-1dd0e102a122';  -- Technology 70013 EUR 2024-01-31
UPDATE invoices SET exchange_rate = 7.45412346 WHERE uuid = '7f923c55-7367-4c91-beda-e4bd8cea56b0';  -- Technology 70014 EUR 2024-01-31
UPDATE invoices SET exchange_rate = 7.45402030 WHERE uuid = 'f0f0dfe7-c421-4e70-9379-7eff26e11977';  -- Technology 70020 EUR 2024-02-29
UPDATE invoices SET exchange_rate = 7.45402039 WHERE uuid = '05f55c83-b80e-4463-924d-3cf8c58eee36';  -- Technology 70021 EUR 2024-02-29
UPDATE invoices SET exchange_rate = 7.45402003 WHERE uuid = '1b509f97-491e-4fc1-ad7e-b10c014d47f2';  -- Technology 70022 EUR 2024-02-29
UPDATE invoices SET exchange_rate = 7.46036062 WHERE uuid = 'abb0ed21-9301-49b9-9b87-69dbe9569fcc';  -- Technology 70028 EUR 2024-03-31
UPDATE invoices SET exchange_rate = 7.46036052 WHERE uuid = 'b2bf9582-6df7-40af-8459-335408d1885a';  -- Technology 70029 EUR 2024-03-31
UPDATE invoices SET exchange_rate = 7.46036057 WHERE uuid = '78079934-5107-4dfe-b320-466117e43847';  -- Technology 70030 EUR 2024-03-31
UPDATE invoices SET exchange_rate = 7.45840386 WHERE uuid = '5551d39f-63aa-4d9e-9dbc-105cdd30fff3';  -- Technology 70034 EUR 2024-04-30
UPDATE invoices SET exchange_rate = 7.45840426 WHERE uuid = 'aab6e8a3-569e-4bbb-b83f-804824c0b352';  -- Technology 70035 EUR 2024-04-30
UPDATE invoices SET exchange_rate = 7.45840390 WHERE uuid = 'ccaa1963-5b5b-40b0-8f87-abee2dfa37af';  -- Technology 70036 EUR 2024-04-30
UPDATE invoices SET exchange_rate = 7.45851217 WHERE uuid = 'f2f9408c-aaef-40c9-b443-b9a0e44c37ec';  -- Technology 70039 EUR 2024-05-31
UPDATE invoices SET exchange_rate = 7.45851210 WHERE uuid = '147795c4-722e-4a80-b8d3-00d62b074b44';  -- Technology 70040 EUR 2024-05-31
UPDATE invoices SET exchange_rate = 7.45851244 WHERE uuid = '3ee2c54a-402c-4a5d-a19f-8ba3535bdd11';  -- Technology 70041 EUR 2024-05-31
UPDATE invoices SET exchange_rate = 7.45766776 WHERE uuid = '9cdd5d2e-e009-4bd4-9df6-a28edb9f46d8';  -- Technology 70047 EUR 2024-06-30
UPDATE invoices SET exchange_rate = 7.45766780 WHERE uuid = '72d18ec1-a1c9-41af-8609-d11c452c9168';  -- Technology 70048 EUR 2024-06-30
UPDATE invoices SET exchange_rate = 7.45766830 WHERE uuid = '3a4d7991-edf6-4776-8412-4766840aafcf';  -- Technology 70049 EUR 2024-06-30
UPDATE invoices SET exchange_rate = 7.46189600 WHERE uuid = 'dc96b2a0-3d4b-44ab-b96d-04bf0ea0531a';  -- Technology 70054 EUR 2024-07-31  -- derived: booked as DKK then reversed 'ført forkert'; same-month realised EUR rate
UPDATE invoices SET exchange_rate = 7.46189600 WHERE uuid = '291b150b-7cc9-4726-a4c2-4cb81b7941b4';  -- Technology 70055 EUR 2024-07-31  -- derived: booked as DKK then reversed 'ført forkert'; same-month realised EUR rate
UPDATE invoices SET exchange_rate = 7.46189600 WHERE uuid = '9c31993a-7171-4a33-a9de-d1b341c18095';  -- Technology 70056 EUR 2024-07-31  -- derived: booked as DKK then reversed 'ført forkert'; same-month realised EUR rate
UPDATE invoices SET exchange_rate = 7.45875485 WHERE uuid = '2a782118-20a0-42e1-b468-e9cbb42dfa7f';  -- Technology 70072 EUR 2024-08-31
UPDATE invoices SET exchange_rate = 7.45875461 WHERE uuid = '7fe757cd-1bfe-4b66-854a-6b6eb66ca6cd';  -- Technology 70073 EUR 2024-08-31
UPDATE invoices SET exchange_rate = 7.45875481 WHERE uuid = '9fa988a3-7fe8-491b-add6-8a696f17fd4e';  -- Technology 70074 EUR 2024-08-31
UPDATE invoices SET exchange_rate = 7.45679195 WHERE uuid = '9938c4de-3b23-45dc-b4cb-1387677573e0';  -- Technology 70081 EUR 2024-09-30
UPDATE invoices SET exchange_rate = 7.45679188 WHERE uuid = 'eccc37b3-0b32-4007-b787-10588e3d6f7e';  -- Technology 70082 EUR 2024-09-30
UPDATE invoices SET exchange_rate = 7.45679116 WHERE uuid = '57f62acc-68a2-4a25-9e58-3672360057c5';  -- Technology 70083 EUR 2024-09-30
UPDATE invoices SET exchange_rate = 7.45874239 WHERE uuid = 'ecbadb9d-0cc9-469d-b3dc-92c01bc1196e';  -- Technology 70090 EUR 2024-10-31
UPDATE invoices SET exchange_rate = 7.45874267 WHERE uuid = 'f6e8a132-b076-47c4-bbed-994ee48ce3cc';  -- Technology 70091 EUR 2024-10-31
UPDATE invoices SET exchange_rate = 7.45829194 WHERE uuid = '7838f398-a98c-4ae0-b621-938d5211bd6c';  -- Technology 70100 EUR 2024-11-30
UPDATE invoices SET exchange_rate = 7.45829214 WHERE uuid = '288f6c11-7a1d-4958-85de-297db16c2f73';  -- Technology 70101 EUR 2024-11-30
UPDATE invoices SET exchange_rate = 7.45791064 WHERE uuid = 'f89388e9-9fde-4270-b5b2-93c450859532';  -- Technology 70103 EUR 2024-12-05
UPDATE invoices SET exchange_rate = 7.45791065 WHERE uuid = '4fb906ce-d299-4701-8cad-735b322959b0';  -- Technology 70104 EUR 2024-12-05
UPDATE invoices SET exchange_rate = 7.46256128 WHERE uuid = '4c0a6ab2-6f1f-45f5-8f93-a432a4232c4e';  -- Technology 70116 EUR 2025-01-31
UPDATE invoices SET exchange_rate = 7.46256110 WHERE uuid = '65afc3a5-e258-4a3e-9fa0-492199077bba';  -- Technology 70117 EUR 2025-01-31
UPDATE invoices SET exchange_rate = 7.45826150 WHERE uuid = '28adb717-24ec-481c-9da1-301f24b2c07a';  -- Technology 70126 EUR 2025-02-28
UPDATE invoices SET exchange_rate = 7.45826163 WHERE uuid = 'b2592d57-ee17-40d6-91ca-1162f76eab0d';  -- Technology 70127 EUR 2025-02-28
UPDATE invoices SET exchange_rate = 7.47060250 WHERE uuid = 'f60eee5d-fbf1-4308-9706-782e30178245';  -- Technology 70143 EUR 2025-03-31
UPDATE invoices SET exchange_rate = 7.47060231 WHERE uuid = '7f9d717c-7dad-4838-96bd-649cbb08f167';  -- Technology 70144 EUR 2025-03-31
UPDATE invoices SET exchange_rate = 7.46451093 WHERE uuid = 'c91d81f0-8b26-4308-8b11-c8b58ff6067e';  -- Technology 70166 EUR 2025-04-30
UPDATE invoices SET exchange_rate = 7.46451109 WHERE uuid = '2049312a-b1ff-4823-ac4b-94bb96a027af';  -- Technology 70167 EUR 2025-04-30
UPDATE invoices SET exchange_rate = 7.45953101 WHERE uuid = 'e4485489-82f0-41aa-87da-6f8e4e2488f5';  -- Technology 70183 EUR 2025-05-31
UPDATE invoices SET exchange_rate = 7.45953149 WHERE uuid = '86418ce2-a40d-4f8e-b39a-07615c049409';  -- Technology 70184 EUR 2025-05-31
UPDATE invoices SET exchange_rate = 7.46070426 WHERE uuid = '9f86fbca-ff27-4c02-bbbc-a9488e98b21a';  -- Technology 70197 EUR 2025-06-30
UPDATE invoices SET exchange_rate = 7.46331579 WHERE uuid = 'f689a48a-61de-444c-b5f8-be9819432a94';  -- Technology 70215 EUR 2025-07-31
UPDATE invoices SET exchange_rate = 7.46276283 WHERE uuid = '543e18be-1036-4baa-a498-1b6412535826';  -- Technology 70224 EUR 2025-08-31
UPDATE invoices SET exchange_rate = 7.46461362 WHERE uuid = '06b4bfb9-dd51-4959-b54c-a7d7d21c2686';  -- Technology 70252 EUR 2025-09-30
UPDATE invoices SET exchange_rate = 7.46838501 WHERE uuid = '037f20f3-e086-4a2b-b29e-c8fb8cd59a2b';  -- Technology 70263 EUR 2025-10-31
UPDATE invoices SET exchange_rate = 7.46866736 WHERE uuid = 'bafdb48a-2a9f-42a7-ae44-20bb51b1c486';  -- Technology 70279 EUR 2025-11-30
UPDATE invoices SET exchange_rate = 7.46810482 WHERE uuid = '70145118-dd95-4e9c-9e83-e57137e98aad';  -- Technology 70287 EUR 2025-12-31
UPDATE invoices SET exchange_rate = 7.45851229 WHERE uuid = '33a1047b-2f84-4b17-8cf4-49d71a05ba83';  -- A/S 17193 EUR 2024-05-31
UPDATE invoices SET exchange_rate = 7.45851229 WHERE uuid = '5e9a4722-d6f8-4634-b0b8-735f3889e206';  -- A/S 17201 EUR 2024-05-31
UPDATE invoices SET exchange_rate = 7.45851229 WHERE uuid = '317aa041-0d3f-4cd5-a668-92f7fa69da75';  -- A/S 17200 EUR 2024-06-09
UPDATE invoices SET exchange_rate = 7.45766800 WHERE uuid = '0a2dbdf5-07b1-4222-ab9d-94c74d26a7a4';  -- A/S 17233 EUR 2024-06-30
UPDATE invoices SET exchange_rate = 7.46189567 WHERE uuid = '9be094c7-a248-47a8-ad23-a71a5089649a';  -- A/S 17256 EUR 2024-07-31
UPDATE invoices SET exchange_rate = 7.46189549 WHERE uuid = '8356cac1-6d57-4743-8ff3-3d57a1b98326';  -- A/S 17258 EUR 2024-07-31
UPDATE invoices SET exchange_rate = 7.46226507 WHERE uuid = 'c1997de9-f8cd-46b9-8a9a-96c6ab16ff53';  -- A/S 17257 EUR 2024-08-08
UPDATE invoices SET exchange_rate = 7.45875489 WHERE uuid = '7611f563-0cdd-4a93-b637-918a4f7237bc';  -- A/S 17343 EUR 2024-08-31
UPDATE invoices SET exchange_rate = 7.45679255 WHERE uuid = '70678152-478c-46f8-8bc2-24be28ef8d43';  -- A/S 17344 EUR 2024-09-30
UPDATE invoices SET exchange_rate = 0.66758467 WHERE uuid = '6428c85d-5e2a-4db2-852a-aef3b3bfe4de';  -- A/S 17554 SEK 2025-02-28
UPDATE invoices SET exchange_rate = 0.68840838 WHERE uuid = '6c8e3760-1071-4631-bcaf-27859c2def0b';  -- A/S 17615 SEK 2025-03-31
UPDATE invoices SET exchange_rate = 0.68090649 WHERE uuid = '60b9b584-d990-4664-9696-5eb6705031e1';  -- A/S 17675 SEK 2025-04-30
UPDATE invoices SET exchange_rate = 0.68508088 WHERE uuid = '74439217-1f01-4655-ad11-9c126b5e9b46';  -- A/S 17730 SEK 2025-05-31
UPDATE invoices SET exchange_rate = 0.67095943 WHERE uuid = 'c21dcd87-be75-4110-bea7-207ae31baa55';  -- A/S 17769 SEK 2025-06-30
UPDATE invoices SET exchange_rate = 0.66960125 WHERE uuid = '8ed7fd0c-4c15-4116-ba0c-540bffadb279';  -- A/S 17858 SEK 2025-07-31
UPDATE invoices SET exchange_rate = 0.67496260 WHERE uuid = '00cf6e42-7026-44ff-963a-a31bb1942a2d';  -- A/S 17894 SEK 2025-08-31
UPDATE invoices SET exchange_rate = 0.67794475 WHERE uuid = '6a752eaf-42a6-4409-a702-1038b4115ce7';  -- A/S 17982 SEK 2025-09-30
UPDATE invoices SET exchange_rate = 0.68406315 WHERE uuid = 'c65b397f-7bb2-419a-8572-a42911a024ad';  -- A/S 18040 SEK 2025-10-31
UPDATE invoices SET exchange_rate = 0.68129375 WHERE uuid = '882c6add-1775-4223-ac7d-3a8d08214b6f';  -- A/S 18160 SEK 2025-11-30
UPDATE invoices SET exchange_rate = 0.69277359 WHERE uuid = '510ab585-4999-4f6b-9d39-5aae9e32724f';  -- A/S 18244 SEK 2025-12-31
UPDATE invoices SET exchange_rate = 0.70855172 WHERE uuid = '1a0bcb9b-a778-41c4-bd26-2925de6ee7de';  -- A/S 18283 SEK 2026-01-31
UPDATE invoices SET exchange_rate = 0.69923212 WHERE uuid = '85b5e2c1-eb16-4432-a676-0505a554f994';  -- A/S 18392 SEK 2026-02-28
UPDATE invoices SET exchange_rate = 0.68588861 WHERE uuid = '253efbf8-820f-4926-bc2d-3b2509b8d765';  -- A/S 27823 SEK 2026-03-31

-- Verification:
--   SELECT currency, COUNT(*) AS docs, SUM(exchange_rate IS NULL) AS missing_rate
--     FROM invoices WHERE COALESCE(currency,'DKK') <> 'DKK' GROUP BY currency;
--   -- Expect: EUR 59 docs / 0 missing, SEK 14 docs / 0 missing.
--
--   SELECT ROUND(SUM(ii.rate*ii.hours*COALESCE(i.exchange_rate,1)),2) AS dkk
--     FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
--    WHERE i.currency='EUR' AND i.companyuuid='44592d3b-2be5-4b29-bfaf-4fafc60b0fa3'
--      AND (i.year*100+i.month) BETWEEN 202507 AND 202606;
--   -- Expect: 1270765.52  (matches e-conomic account 1021 for the same 6 docs)
--
--   SELECT ROUND(SUM(ii.rate*ii.hours*COALESCE(i.exchange_rate,1)),2) AS dkk
--     FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
--    WHERE i.currency='SEK' AND (i.year*100+i.month) BETWEEN 202507 AND 202606;
--   -- Expect: 995530.94   (matches e-conomic account 2130 for the same 9 docs)
