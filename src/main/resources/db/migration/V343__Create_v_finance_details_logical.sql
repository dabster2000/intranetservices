-- =============================================================================
-- V343: Create read-side view v_finance_details_logical
--
-- Why this change:
--   From Feb 2026 onward, 38 finance_details rows (totalling 5,291,118 DKK) at
--   Trustworks Technology ApS and Trustworks Cyber Security ApS sit on
--   account 1010 instead of 1040. Cause: the per-customer "Trustworks A/S"
--   default-sales-account override was removed in e-conomic admin around
--   Feb 1, 2026 (no audit trail). All Tech/Cyber INTERNAL invoices to
--   Trustworks A/S (cvr=35648941) since then land on 1010 mixed with
--   external customer revenue. See:
--     docs/superpowers/analysis/2026-05-13-ebitda-gap-final-reconciliation.md
--     docs/superpowers/specs/2026-05-13-intercompany-account-override-enforcement.md
--
--   Per locked decision #1, we do NOT mutate e-conomic data nor finance_details.
--   Instead this view virtualizes the correct ("logical") account number at
--   read time, leaving the source rows untouched. Per locked decision #2 the
--   rule is forward-looking by construction — any future row matching the
--   predicate is auto-classified on next read.
--
-- Identification rule (locked decision #3, verified against production data
-- 2026-05-15 — zero false positives):
--
--     accountnumber = 1010
--     AND fd.companyuuid IN (
--         '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',  -- Trustworks Technology ApS
--         'e4b0a2a4-0963-4153-b0a2-a409637153a2'   -- Trustworks Cyber Security ApS
--     )
--     AND inv.cvr = '35648941'                     -- Trustworks A/S (debtor)
--
--   Both UUIDs and the CVR are hard-coded per locked decision #8: three
--   companies → currently one intercompany direction (subs → A/S). If a
--   fourth Trustworks entity appears, extend the CASE branches and ship a
--   new migration; do NOT parameterize via a lookup table without a
--   stakeholder ask.
--
-- View shape:
--   - All forward-relevant finance_details columns (id, companyuuid,
--     entrynumber, accountnumber, invoicenumber, amount, remainder,
--     expensedate, text).
--   - logical_accountnumber INT — derived per the CASE expression above;
--     equals fd.accountnumber for all rows that do NOT match the rule
--     (preserves source for the 99%+ of non-intercompany rows).
--   - is_reclassified TINYINT — 1 when the rule matched, 0 otherwise.
--     Non-null on both branches; never NULL.
--   - LEFT JOIN preserves every finance_details row. Rows with no matching
--     invoice (invoicenumber=0, or invoice synced later, or external GL
--     adjustments without an invoice link) fall through with
--     logical_accountnumber = accountnumber and is_reclassified = 0.
--
-- Affected rows on production data (Feb-Apr 2026 baseline):
--   - 38 rows where is_reclassified=1, SUM(ABS(amount)) ≈ 5,291,118 DKK.
--   - All other ~75K finance_details rows: logical_accountnumber unchanged.
--
-- Forward-looking property:
--   - If accounting cleans up e-conomic (re-applies the override or posts
--     corrective vouchers), next sync mutates the source rows; the rule
--     stops matching them; reclassified count drops on the next read.
--   - If a NEW mis-posting appears, the view auto-classifies it on the
--     next read. The IntercompanyClassificationCheck daily monitor
--     (companion class) Slack-alerts on net growth.
--
-- Idempotent: yes. CREATE OR REPLACE VIEW re-defines the view on every run
-- without touching any underlying data. Safe to re-run.
--
-- Rollback (manual, if needed):
--   DROP VIEW IF EXISTS v_finance_details_logical;
--
-- Performance:
--   - No new index needed; existing indexes on finance_details(invoicenumber,
--     companyuuid) (V199) and invoices PRIMARY KEY support the LEFT JOIN.
--   - View is non-materialized. Each query against it executes the join +
--     CASE; cost proportional to finance_details cardinality (~75K rows).
--
-- Side effects:
--   - None at write time. View is read-only.
--   - v1 has no consumer; IntercompanyClassificationCheck queries the bare
--     predicate (finance_details JOIN invoices) directly for clarity and
--     to keep monitoring independent of view existence. Future per-entity
--     revenue queries may opt in by reading v_finance_details_logical.
-- =============================================================================

CREATE OR REPLACE VIEW v_finance_details_logical AS
SELECT
    fd.id,
    fd.companyuuid,
    fd.entrynumber,
    fd.accountnumber,
    CASE
        WHEN fd.accountnumber = 1010
         AND fd.companyuuid IN (
             '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
             'e4b0a2a4-0963-4153-b0a2-a409637153a2'
         )
         AND inv.cvr = '35648941'
        THEN 1040
        ELSE fd.accountnumber
    END AS logical_accountnumber,
    CASE
        WHEN fd.accountnumber = 1010
         AND fd.companyuuid IN (
             '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
             'e4b0a2a4-0963-4153-b0a2-a409637153a2'
         )
         AND inv.cvr = '35648941'
        THEN 1 ELSE 0
    END AS is_reclassified,
    fd.invoicenumber,
    fd.amount,
    fd.remainder,
    fd.expensedate,
    fd.text
FROM finance_details fd
LEFT JOIN invoices inv
    ON inv.invoicenumber = fd.invoicenumber
   AND inv.companyuuid   = fd.companyuuid;

-- Verification (manual, post-deploy):
--
-- 1. Production-parity baseline — expect 38 rows, 5,291,118 DKK (±1 kr.):
--      SELECT COUNT(*)            AS rows_n,
--             SUM(ABS(amount))    AS misposted_dkk
--      FROM v_finance_details_logical
--      WHERE is_reclassified = 1
--        AND expensedate BETWEEN '2026-02-01' AND '2026-04-30';
--
-- 2. Per-(tenant, month) breakdown of reclassified rows last 6 months:
--      SELECT companyuuid,
--             YEAR(expensedate)  AS y,
--             MONTH(expensedate) AS m,
--             COUNT(*)           AS rows_n,
--             SUM(ABS(amount))   AS misposted_dkk
--      FROM v_finance_details_logical
--      WHERE is_reclassified = 1
--        AND expensedate >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
--      GROUP BY companyuuid, y, m
--      ORDER BY companyuuid, y, m;
--
-- 3. Fall-through invariants (each must return 0):
--      -- 3a. Row with invoicenumber=0 must NOT be reclassified.
--      SELECT COUNT(*) FROM v_finance_details_logical
--      WHERE invoicenumber = 0 AND is_reclassified = 1;
--
--      -- 3b. Reclassified rows must originate from accountnumber=1010 only.
--      SELECT COUNT(*) FROM v_finance_details_logical
--      WHERE is_reclassified = 1 AND accountnumber <> 1010;
--
--      -- 3c. Reclassified rows must be scoped to TECH/CYBER only.
--      SELECT COUNT(*) FROM v_finance_details_logical
--      WHERE is_reclassified = 1
--        AND companyuuid NOT IN (
--            '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3',
--            'e4b0a2a4-0963-4153-b0a2-a409637153a2'
--        );
--
-- 4. Total cardinality of view = total cardinality of finance_details:
--      SELECT (SELECT COUNT(*) FROM finance_details)
--           - (SELECT COUNT(*) FROM v_finance_details_logical) AS delta;
--      -- Expect: 0 (LEFT JOIN preserves every row).
