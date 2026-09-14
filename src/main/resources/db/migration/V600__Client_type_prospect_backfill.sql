-- ============================================================================
-- V600 — client.type gains PROSPECT, and the never-billed rows are backfilled
--
-- Spec: docs/specs/intra-crm-customers-former-prospects-contacts-2026-09-14.md §2.2, §2.6
--
-- NO DDL FOR THE COLUMN. client.type is VARCHAR(10) and Hibernate maps it with
-- EnumType.STRING, so a third value needs nothing but the Java enum. This file exists
-- for the BACKFILL.
--
-- WHAT A PROSPECT IS
--   A company Intra knows and has never billed. Billing details are not required, and
--   EconomicsCustomerSyncService refuses to sync one, so a company somebody had a coffee
--   with stops becoming an e-conomic customer the same minute. The first contract
--   graduates it to CLIENT inside ContractService.save — automatic, one-way, and the
--   place where billing completeness is enforced.
--
-- THE BACKFILL PREDICATE — "never billed", from four facts, not from an opinion
--   A CLIENT row becomes PROSPECT when it has
--     · no contract, as the client OR as the billing client
--     · no invoice against it as the billing client
--     · no work row
--     · no project
--   On production on 2026-09-14 that was 161 rows of 296. Re-count on the day this runs;
--   these are live tables.
--
-- WHAT IS DELIBERATELY NOT TOUCHED
--   · PARTNER rows. A partner is an intermediary billing entity; it is not a prospect
--     even when nothing has been billed through it yet.
--   · The e-conomic customers already created for those 161. Nothing is deleted there —
--     the gate is on new syncs, and unpicking history in two agreements is not a
--     migration's job.
--   · The four placeholder rows Hans cleans by hand: "Unnamed Client" (move its open
--     lead first), "Fortrolig Kunde", "DELETE THIS - Bording Group", "ATP Debitor
--     projekt". They match the predicate and become prospects, which is harmless — they
--     are excluded from every billing picker either way — but they still want deleting
--     or renaming by a person, not by this file.
--
-- The relationship (Customer / Former / Prospect / Contact) is DERIVED and never stored;
-- see AccountService.relationshipsForAll(). type is the one stored fact, and it answers a
-- different question: may this row be billed.
--
-- RESERVED-WORD CHECK: `type`, `client`, `work`, `project` are not reserved in MariaDB
-- 10.11 as used here (`work` is quoted nowhere else in this schema either and V-numbered
-- migrations already select from it unquoted).
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   UPDATE client SET type = 'CLIENT' WHERE type = 'PROSPECT';
--   (safe: PROSPECT did not exist before this migration, so every such row was a CLIENT)
-- ============================================================================

UPDATE client c
   SET c.type = 'PROSPECT'
 WHERE c.type = 'CLIENT'
   AND NOT EXISTS (SELECT 1 FROM contracts ct
                    WHERE ct.clientuuid = c.uuid
                       OR ct.billing_client_uuid = c.uuid)
   AND NOT EXISTS (SELECT 1 FROM invoices i
                    WHERE i.billing_client_uuid = c.uuid)
   AND NOT EXISTS (SELECT 1 FROM work w
                    WHERE w.clientuuid = c.uuid)
   AND NOT EXISTS (SELECT 1 FROM project p
                    WHERE p.clientuuid = c.uuid);
