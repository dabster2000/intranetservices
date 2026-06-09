# Self-Billed Settlement — Staging Dry-Run Runbook

Run with an `invoices:write` JWT against staging. Window: FY2025/26 → `from=2025-07-01 to=2026-06-30`, ym `from=202507 to=202606`.

1. **Capture** — `POST /invoices/cross-company/selfbilled/capture?from=2025-07-01&to=2026-06-30`
   - Expect ~170 lines across accounts 2104/2106. Spot-check `selfbilled_line`: Michelle MC 08-2025 amount −153525.00, status RESOLVED; the 4 correction vouchers (THG/TD/FSP/MHB) — confirm `SUM(amount) GROUP BY voucher_number` nets correctly AND every correction sibling carries a non-null `work_year`/`work_month`.
2. **Restamp REPORT** — `POST /invoices/cross-company/selfbilled/restamp?from=202507&to=202606&apply=false`
   - Expect Michelle Sep/Jan = RESTAMP, Aug/Jul/Nov = NO_CHANGE, Julie Apr = UNMATCHED (arrears — must NOT be reversed). Any AMBIGUOUS/UNMATCHED on a *billed* period → stop, investigate, do not apply.
3. **Restamp APPLY** — only after the report is sane: same call with `apply=true`.
4. **Settle (queue)** — `POST /invoices/cross-company/selfbilled/settle?from=202507&to=202606&queue=true`
   - Expect Michelle Aug delta 0 (no doc), Sep/Jan now recognised (no duplicate), Julie Apr untouched. Re-running must create nothing new (duplicate guard).
5. **Verify** vs the verification report's reconciliation tables. Prod re-derive is a separate, human-gated repeat.
