-- =============================================================================
-- V355: One-time backfill of invoices.creditnote_for_uuid for orphan CREDIT_NOTE
--       rows whose link to their original INVOICE was never persisted.
--
-- Why this change:
--   Spec: docs/superpowers/specs/2026-05-25-internal-invoices-credit-note-filtering-design.md
--   (§6.1 "Backfill migration", §5 tie-break rule, §8 AC1).
--
--   The /accounting/internal-invoices page is being changed to hide INVOICE
--   rows that have been cancelled by a CREDIT_NOTE. The cancellation signal
--   is structural: invoices.creditnote_for_uuid IS NOT NULL on the CN.
--
--   Today only ~58% of CREDIT_NOTE rows have that FK populated:
--     FY 25/26  125/125 (100%)
--     FY 24/25   85/92  ( 92%)
--     FY 23/24   13/51  ( 25%)
--   Total orphans across all history: ~209.
--
--   The frontend filter would otherwise leak old "cancelled" INVOICE rows back
--   onto the page (because their CN has no link to them). This migration
--   reconstructs the link for historical orphans by parsing the Danish
--   description text the legacy createCreditNote() flow wrote on every CN:
--       specificdescription = "Kreditnota til faktura <prefix>-<invoicenumber>"
--   e.g., "Kreditnota til faktura A-17775".
--
-- Algorithm (single ranked UPDATE; per spec §6.1):
--   1. Source rows: invoices where type='CREDIT_NOTE' AND creditnote_for_uuid IS NULL.
--   2. Extract the candidate original invoicenumber from specificdescription via
--      REGEXP_REPLACE: strip the leading "Kreditnota til faktura " prefix, then
--      strip the single "-" separator between prefix code and number, then CAST
--      AS UNSIGNED. Any non-matching row yields 0 and is skipped in step 3.
--   3. Discard candidates where the extracted number is 0 or NULL (these are the
--      "Kreditnota til faktura 0" cases — see spec §7 placeholders).
--   4. Join to candidate originals on
--          orig.invoicenumber = <extracted>
--      AND orig.companyuuid  = cn.companyuuid     -- company scope (prevents cross-tenant collisions)
--      AND orig.type         = 'INVOICE'           -- explicitly excludes CREDIT_NOTE-of-CN
--   5. Tie-break for multi-CN-per-original via window:
--          ROW_NUMBER() OVER (PARTITION BY orig.uuid
--                             ORDER BY cn.invoicedate ASC, cn.uuid ASC)
--      Only rank=1 is applied. Losers remain NULL and are recorded in the audit
--      table with skip_reason='tiebreak_loser'.
--   6. The unique index ux_invoices_creditnote_for_uuid (V72) is the final
--      safety net — the tie-break above is designed to prevent any duplicate
--      attempt from reaching the index. (Per spec §6.1 final note.)
--
-- Audit table (creditnote_backfill_audit):
--   Permanent. One row per processed CN. resolved_to_uuid is set on success;
--   skip_reason is set on skip. Values used:
--     'number_zero_or_null'    extracted number was 0 or unparseable
--     'no_original_match'      no orig.invoicenumber in same company / type=INVOICE
--     'original_is_credit_note' the matching row is type=CREDIT_NOTE (CN-of-CN
--                              — explicitly excluded by the JOIN; counted via
--                              a separate diagnostic insert)
--     'tiebreak_loser'         multiple CNs claim the same original; this one
--                              did not win the ROW_NUMBER race
--
--   The audit table is left in place after the migration so operations can
--   inspect the result later. It is not consulted by application code.
--
-- Backwards compatibility / idempotence:
--   * Filter `creditnote_for_uuid IS NULL` on the source set: re-running the
--     migration on a DB where every CN is already linked produces zero updates.
--   * `CREATE TABLE IF NOT EXISTS` for the audit table — re-run safe.
--   * Audit insertion is wrapped with `INSERT IGNORE` so re-run of the audit
--     selects against an already-populated table will not produce PK violations.
--     A re-run will, however, leave the original audit rows in place (single
--     historical record of the first backfill execution).
--
-- Rollback:
--   No structural rollback shipped. To "undo" the backfill in dev:
--       UPDATE invoices i
--       JOIN creditnote_backfill_audit a ON a.uuid = i.uuid
--       SET i.creditnote_for_uuid = NULL
--       WHERE a.resolved_to_uuid IS NOT NULL;
--   Do NOT run this in production without explicit approval.
--
-- Expected impact (per memory-context.md / spec §8 AC1):
--   * FY 24/25 sample of 7 orphans:
--       5 resolved: 17503, 70128, 70135-original?, 17718, 17604
--       2 tie-break losers: 17506->17503 (loser), 70125->70128 (loser)
--       1 unresolvable (CN-of-CN): #70135 maps to a CREDIT_NOTE original
--   * Across all FY 22/23+: target ≥98% of CREDIT_NOTE rows linked.
--
-- Verification queries (manual, post-deploy):
--   -- Remaining orphan rate from FY 22/23 onward:
--   SELECT COUNT(*) AS still_orphan
--     FROM invoices
--    WHERE type = 'CREDIT_NOTE'
--      AND creditnote_for_uuid IS NULL
--      AND invoicedate >= '2022-07-01';
--   -- Audit summary by reason:
--   SELECT COALESCE(skip_reason,'_resolved_') AS bucket, COUNT(*)
--     FROM creditnote_backfill_audit
--    GROUP BY 1 ORDER BY 2 DESC;
--   -- Spec smoke test: invoice 27839 (CN for 27811) is linked:
--   SELECT i.invoicenumber AS cn_number,
--          (SELECT invoicenumber FROM invoices o WHERE o.uuid = i.creditnote_for_uuid) AS orig_number
--     FROM invoices i
--    WHERE i.invoicenumber = 27839 AND i.type = 'CREDIT_NOTE';
--   -- Expect: orig_number = 27811.
--
-- Security / safety:
--   * No user input — pure DDL/DML against trusted schema.
--   * Runs as the Flyway-owner DB role.
--   * Audit table stores only UUIDs (no PII).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Step 1: Audit table (permanent record of this backfill run).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creditnote_backfill_audit (
    uuid              VARCHAR(36) NOT NULL,
    resolved_to_uuid  VARCHAR(36) NULL,
    skip_reason       VARCHAR(64) NULL,
    run_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- Step 2: Apply the backfill UPDATE.
--   Wrapped in a CTE-style derived table so the ROW_NUMBER() tie-break is
--   evaluated before the UPDATE writes a single row. MariaDB 10.2+ supports
--   window functions but NOT inside a directly-joined UPDATE in older releases,
--   so we materialise the ranked candidates into a derived table first.
-- -----------------------------------------------------------------------------
UPDATE invoices cn
JOIN (
    SELECT  ranked.cn_uuid,
            ranked.orig_uuid
      FROM (
        SELECT  cn2.uuid       AS cn_uuid,
                orig.uuid      AS orig_uuid,
                ROW_NUMBER() OVER (
                    PARTITION BY orig.uuid
                    ORDER BY cn2.invoicedate ASC, cn2.uuid ASC
                ) AS rn
          FROM invoices cn2
          JOIN invoices orig
            ON orig.invoicenumber = CAST(
                    REGEXP_REPLACE(
                        REGEXP_REPLACE(cn2.specificdescription,
                                       '^Kreditnota til faktura ', ''),
                        '-', ''
                    ) AS UNSIGNED
               )
           AND orig.companyuuid = cn2.companyuuid
           AND orig.type        = 'INVOICE'
         WHERE cn2.type               = 'CREDIT_NOTE'
           AND cn2.creditnote_for_uuid IS NULL
           AND cn2.specificdescription IS NOT NULL
           AND CAST(
                 REGEXP_REPLACE(
                     REGEXP_REPLACE(cn2.specificdescription,
                                    '^Kreditnota til faktura ', ''),
                     '-', ''
                 ) AS UNSIGNED
               ) > 0
      ) ranked
     WHERE ranked.rn = 1
) winners ON winners.cn_uuid = cn.uuid
SET cn.creditnote_for_uuid = winners.orig_uuid
WHERE cn.type = 'CREDIT_NOTE'
  AND cn.creditnote_for_uuid IS NULL;

-- -----------------------------------------------------------------------------
-- Step 3a: Audit — successfully resolved rows.
--   Insert one row per CN we just linked. resolved_to_uuid = the chosen orig.
--   INSERT IGNORE so re-running the migration after the audit already exists
--   does not error on duplicate PKs.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO creditnote_backfill_audit (uuid, resolved_to_uuid, skip_reason)
SELECT cn.uuid, cn.creditnote_for_uuid, NULL
  FROM invoices cn
 WHERE cn.type = 'CREDIT_NOTE'
   AND cn.creditnote_for_uuid IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM invoices orig
        WHERE orig.uuid          = cn.creditnote_for_uuid
          AND orig.companyuuid   = cn.companyuuid
          AND orig.type          = 'INVOICE'
   )
   AND cn.specificdescription LIKE 'Kreditnota til faktura %';

-- -----------------------------------------------------------------------------
-- Step 3b: Audit — skip reason 'number_zero_or_null'.
--   CN rows whose description either does not parse to a positive integer or
--   parses to 0 / NULL. Includes "Kreditnota til faktura 0" rows and rows with
--   non-conforming descriptions.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO creditnote_backfill_audit (uuid, resolved_to_uuid, skip_reason)
SELECT cn.uuid, NULL, 'number_zero_or_null'
  FROM invoices cn
 WHERE cn.type = 'CREDIT_NOTE'
   AND cn.creditnote_for_uuid IS NULL
   AND (
        cn.specificdescription IS NULL
     OR COALESCE(CAST(
            REGEXP_REPLACE(
                REGEXP_REPLACE(COALESCE(cn.specificdescription,''),
                               '^Kreditnota til faktura ', ''),
                '-', ''
            ) AS UNSIGNED
        ), 0) = 0
   );

-- -----------------------------------------------------------------------------
-- Step 3c: Audit — skip reason 'original_is_credit_note'.
--   The extracted number resolves only to a CREDIT_NOTE (i.e., the description
--   points at another CN, not an INVOICE). These are CN-of-CN cases (e.g.,
--   #70135 in the FY 24/25 sample).
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO creditnote_backfill_audit (uuid, resolved_to_uuid, skip_reason)
SELECT cn.uuid, NULL, 'original_is_credit_note'
  FROM invoices cn
 WHERE cn.type = 'CREDIT_NOTE'
   AND cn.creditnote_for_uuid IS NULL
   AND CAST(
         REGEXP_REPLACE(
             REGEXP_REPLACE(COALESCE(cn.specificdescription,''),
                            '^Kreditnota til faktura ', ''),
             '-', ''
         ) AS UNSIGNED
       ) > 0
   AND EXISTS (
        SELECT 1 FROM invoices x
         WHERE x.invoicenumber = CAST(
                 REGEXP_REPLACE(
                     REGEXP_REPLACE(cn.specificdescription,
                                    '^Kreditnota til faktura ', ''),
                     '-', ''
                 ) AS UNSIGNED
               )
           AND x.companyuuid = cn.companyuuid
           AND x.type        = 'CREDIT_NOTE'
   )
   AND NOT EXISTS (
        SELECT 1 FROM invoices y
         WHERE y.invoicenumber = CAST(
                 REGEXP_REPLACE(
                     REGEXP_REPLACE(cn.specificdescription,
                                    '^Kreditnota til faktura ', ''),
                     '-', ''
                 ) AS UNSIGNED
               )
           AND y.companyuuid = cn.companyuuid
           AND y.type        = 'INVOICE'
   );

-- -----------------------------------------------------------------------------
-- Step 3d: Audit — skip reason 'tiebreak_loser'.
--   The CN parses to a valid INVOICE number in the same company, but another
--   CN won the (invoicedate ASC, uuid ASC) tie-break for the same orig.uuid.
--   Detected by: still NULL after the UPDATE AND a successful audit row exists
--   for the same target orig with a different CN UUID.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO creditnote_backfill_audit (uuid, resolved_to_uuid, skip_reason)
SELECT cn.uuid, NULL, 'tiebreak_loser'
  FROM invoices cn
  JOIN invoices orig
    ON orig.invoicenumber = CAST(
           REGEXP_REPLACE(
               REGEXP_REPLACE(cn.specificdescription,
                              '^Kreditnota til faktura ', ''),
               '-', ''
           ) AS UNSIGNED
       )
   AND orig.companyuuid = cn.companyuuid
   AND orig.type        = 'INVOICE'
 WHERE cn.type = 'CREDIT_NOTE'
   AND cn.creditnote_for_uuid IS NULL
   AND CAST(
         REGEXP_REPLACE(
             REGEXP_REPLACE(cn.specificdescription,
                            '^Kreditnota til faktura ', ''),
             '-', ''
         ) AS UNSIGNED
       ) > 0
   AND EXISTS (
        SELECT 1
          FROM invoices winner
         WHERE winner.creditnote_for_uuid = orig.uuid
           AND winner.uuid                != cn.uuid
   );

-- -----------------------------------------------------------------------------
-- Step 3e: Audit — skip reason 'no_original_match' (catch-all fallback).
--   Description parses to a positive integer but no INVOICE row exists with
--   that (invoicenumber, companyuuid). Includes invoices migrated from legacy
--   systems with renumbered originals.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO creditnote_backfill_audit (uuid, resolved_to_uuid, skip_reason)
SELECT cn.uuid, NULL, 'no_original_match'
  FROM invoices cn
 WHERE cn.type = 'CREDIT_NOTE'
   AND cn.creditnote_for_uuid IS NULL
   AND CAST(
         REGEXP_REPLACE(
             REGEXP_REPLACE(COALESCE(cn.specificdescription,''),
                            '^Kreditnota til faktura ', ''),
             '-', ''
         ) AS UNSIGNED
       ) > 0
   AND NOT EXISTS (
        SELECT 1 FROM invoices any_match
         WHERE any_match.invoicenumber = CAST(
                 REGEXP_REPLACE(
                     REGEXP_REPLACE(cn.specificdescription,
                                    '^Kreditnota til faktura ', ''),
                     '-', ''
                 ) AS UNSIGNED
               )
           AND any_match.companyuuid = cn.companyuuid
   );
