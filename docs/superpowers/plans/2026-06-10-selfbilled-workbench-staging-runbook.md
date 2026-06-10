# Self-billed workbench — staging dry-run runbook (2026-06-10)

Oracle: spec §1/§6.1. Constants: Vattenfall client 2cbb7f5e-9e2b-4edc-870b-e9591dc58891,
Energinet client 16e3ccad-f053-4804-bbcc-cde32de51006, debtor (A/S)
d8894494-2fb4-4f72-9e05-e6032e6dd691, Michelle Cantor (TECH) 1fd3213f-1b93-43c5-b4f6-97ccd98a2d72,
internal #70266 already settlement-stamped (Vattenfall · A/S · 2025 · 8), voucher 2069
`Faktura: 58484650180 - 08-2025 MC` −153.525 booked 2025-10-01 ↔ entry 130580.

Pre-flight (after every nightly refresh):
1. `SELECT COUNT(*) FROM selfbilled_source` — 2 rows; tables exist (V364/V365 re-applied at boot).
2. `SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history` ≥ 365.

Capture + suggestions:
3. POST /invoices/cross-company/selfbilled/capture?from=2024-05-01&to=<today>
   (window reaches the observed extreme lag: work 05-2024 self-billed 06-2025).
4. GET /documents?client=<vattenfall>&from=2025-07-01&to=2026-06-30 →
   - voucher 2069 present, bookingDate 2025-10-01, amount 153525.00, status UNASSIGNED
   - suggestion MC → Michelle, period 08-2025, confidence ≥ 90
   - tie-out chip: capturedNet == phantomImported (entries match the PHANTOM import) — AC9.
5. Confirm the MC code map exists (POST /code-map if a code is unmapped).

The Michelle oracle (AC1, AC4, AC10):
6. Assign voucher 2069 → Michelle / 2025-08 (workbench Assign + confirm).
7. GET /consultants?client=<vattenfall>&fromYm=202508&toYm=202508 →
   assigned 153525.00 · settled 153525.00 (internal #70266) · delta 0 · Settle DISABLED.
   The legacy +9.200 must be GONE. Work (check) column shows 162725 — display-only.
8. Voucher 2069 status flips to SETTLED (delta 0). Mirror check (AC10):
   `SELECT consultant_uuid, attributed_amount, source FROM invoice_item_attributions iia
    JOIN invoiceitems ii ON ii.uuid = iia.invoiceitem_uuid
    JOIN invoices p ON p.uuid = ii.invoiceuuid WHERE p.economics_entry_number = 130580`
   → exactly one row: (Michelle, 153525.00, SELFBILLED_ASSIGNMENT).
9. Nightly-guard probe: POST /invoices/{voucher-2069-phantom-uuid}/phantom-attribution/derive
   → {"status":"SKIPPED_SELFBILLED"}; the mirror row unchanged.

A real settle (pick an UNSETTLED cross-company month if one exists, else simulate by
assigning a doc with no stamped internal):
10. Settle → ONE QUEUED internal, settlement_* stamped, items consultantuuid set,
    rate = delta, hours = 1. Re-clicking Settle is a no-op (delta 0 / open-QUEUED guard).
11. Force-create flow on the pairs grid still finalizes it (AC6 unchanged).

Energinet parity (spec §10.3):
12. Repeat steps 4–7 for client 16e3ccad-f053-4804-bbcc-cde32de51006 month 2025-08 (codes TD/FSP/MHB/JK/MH);
    verify net-zero correction vouchers 2291/2292 are auto-IGNORED.

Legacy grid hand-off + link queue:
13. GET /invoices/cross-company/phantoms/settlement → NO Vattenfall/Energinet rows.
14. GET /invoices/cross-company/selfbilled/internals/unlinked → expected small/empty
    (2026-06-08 remediation stamped the known prod set); if a candidate exists, Link it
    and verify it joins settled(g) + an attribution_audit_log row (SELFBILLED_LINK) exists.

Coverage (AC3): the Invoices tab bar must satisfy
captured = assigned + sameCompany + unassigned + ignored for both clients.

Sign-off: paste each step's evidence below before promoting to prod. Prod stays HELD
until finance signs off the settle-per-group plan (standing decision 2026-06-09).

---

## Execution evidence — 2026-06-10 (executed by Claude w/ read-only DB + system JWT, actor hans.lassen 7948c5e8)

Deployed under test: BE `2a86dd7d` (Flyway v366, /health 200, PRIMARY image SHA verified), FE `a602c42`.
JWT lacked `users:read` → consultant identities masked in all reads (masking verified working).

| Step | Verdict | Evidence |
|---|---|---|
| 1 | ✅ | `selfbilled_source` = 2 rows (Vattenfall 2104, Energinet 2106, both enabled, debtor A/S) |
| 2 | ✅ | `MAX(CAST(version AS UNSIGNED))` = 366 ≥ 365 |
| 3 | ✅ | Capture 200 in 2.5s: `{"Vattenfall(2104)":143,"Energinet(2106)":38}` |
| 4 | ✅* | Voucher 2069: bookingDate 2025-10-01, amount 153525.00, UNASSIGNED, suggestion MC → 08-2025, conf 90 ("code map + period from line text"). *Tie-out chip honestly NOT ok: phantomImported − capturedNet = 1,309,636.80, fully decomposed = 890,458.80 (5 negative correction entries the legacy PHANTOM import stored as ABS, e.g. entry 130577 −128,270 vs phantom +128,270) + 419,178.00 (5 negative entries never imported, = the IGNORED bucket to the øre). Pre-existing import-data issue, NOT a workbench bug — chip surfaces it as designed (AC9). |
| 5 | ✅ | Code map existed but was WRONG (seeded 2026-06-09 by 'test' with COMPANY uuids: MC→TECH, JK→CYBER). Fixed via POST /code-map (204 ×2): MC(2104)→Michelle 1fd3213f-…, JK(2106)→Julie d0033634-8c85-4efc-83d8-8fdf2a2a95d2. Upsert confirmed (no dup rows), `created_by` = actor (AC7). |
| 6 | ✅ | Assign 2069 → Michelle/2025-08: 204 |
| 7 | ✅ | Consultants 202508: assigned 153525.00 · settled 153525.00 (#70266) · delta 0.0 · canSettle false · workValue 162725 display-only. Legacy +9.200 GONE. |
| 8 | ✅ | Voucher 2069 status SETTLED. Assignment row: (Michelle, 2025-08, −153525.00 signed, HUMAN, assigned_by hans, 16:31:43). Mirror: EXACTLY ONE row (Michelle, 153525.00, SELFBILLED_ASSIGNMENT) on entry 130580 (phantom efa3455d). |
| 9 | ✅ | POST /invoices/efa3455d-…/phantom-attribution/derive → 200 `{"status":"SKIPPED_SELFBILLED"}`; mirror row byte-identical after probe (AC10). |
| 10 | ✅ | Real settle: assigned JK voucher 2563 → Julie/2026-02 (Energinet had no stamped internal for 2026-02). Consultants: assigned 142613.74 · settled 0 · delta 142613.74 · canSettle true. POST /settle → 200 `["53df09ce-…"]` = ONE QUEUED internal, stamps (Energinet · A/S · 2026 · 2), item (Julie, hours 1.0, rate 142613.74). Re-click → 200 `[]` no-op (QUEUED counts toward settled → delta 0). |
| 11 | ✅fix / ◐config | FIRST RUN → 500 "Identifier may not be null": phantom representatives carry NO contractuuid → BillingContextResolver hit Contract.findById(null); blocked finalization of ALL workbench settles. **Fixed in `9aa89534`** (createSettlementInternal resolves the client's active contract for the settled consultant when the source has none, fail-closed 400; resolver null-guards). **RE-VERIFIED post-deploy (image 9aa89534, task-def :240, rollout COMPLETED):** fresh settle MC 2066 → Michelle/2025-09 (delta 78,200) → ONE QUEUED internal `73c812fb` with **contractuuid 7b9cfe31 stamped** (same Vattenfall contract as legacy #70266/#70297), item (Michelle, 1h, 78200). Force-create now proceeds past billing-context and stops at a PRE-EXISTING config gap: 400 "e-conomic invoice product number is not configured for Trustworks Technology ApS" — `integration_keys` has `invoice-product-number` ONLY for A/S (=1); TECH 44592d3b + CYBER e4b0a2a4 lack it (prod too — staging is a prod copy). forceFinalizeQueued delegates to the same finalizeAutomatically as the nightly batchlet, so both routes need it; requirement pre-dates the workbench (AC6 intact). **ADMIN ACTION: set `invoice-product-number` for TECH + CYBER, then force-create 73c812fb finalizes.** Internal stays QUEUED/recoverable as designed. |
| 12 | ◐ | Energinet docs ✅ (34 docs); JK suggestions conf 90 ✅. Net-zero 2291/2292 were UNASSIGNED not auto-IGNORED — statuses pre-date the new import code (captured 2026-06-09 by old code; upsert preserves status when net unchanged). STAGING-ONLY artifact: prod's first capture runs new code → will auto-IGNORE. Human-marked both via POST /ignore (204; sticky path exercised). TD/FSP/MHB/MH same-company assigns deferred — staging user data is anonymized, initials unresolvable; they are A/S consultants (same-company, no settlement impact). |
| 13 | ✅ | GET /invoices/cross-company/phantoms/settlement → 0 groups, 0 Vattenfall/Energinet rows (hand-off complete). |
| 14 | ✅* | 245 unlinked — NOT prod-shaped: all dated 2026-06-09 = artifacts of the failed PR #99 restamp dry-run on staging (nightly refresh wipes tonight; prod expectation remains small/empty). Masking ✅ (itemNames [], description null). Link flow exercised on artifact 04a46764 (only-Michelle items) → Vattenfall/2025-10: 204, settlement key stamped, `attribution_audit_log` row SELFBILLED_LINK changed_by hans with old/new state (AC7+AC8). |
| AC3 | ✅ | Final coverage, both clients, after all mutations: VATT captured 15,127,646.18 = 153,525.00 + 0 + 15,393,299.18 + (−419,178.00) ✓; ENER 3,432,989.07 = 142,613.74 + 0 + 3,290,375.33 + 0 ✓ |

Caveats: all staging mutations (assignments, QUEUED internals 53df09ce + 73c812fb, link stamp, code map, ignores) are wiped by the nightly prod→staging refresh (~02:00). The step-11 contract fix (`9aa89534`) is permanent code. Outstanding before prod: (1) admin sets `invoice-product-number` integration key for TECH + CYBER (blocks finalization of any TECH/CYBER-issued internal via the orchestrator, legacy nightly route included); (2) on prod, re-seed the code map via POST /code-map (MC→Michelle on 2104, JK→Julie on 2106) — the staging fix is wiped nightly and prod tables start empty.

Prod stays HELD until finance signs off the settle-per-group plan (standing decision 2026-06-09).
