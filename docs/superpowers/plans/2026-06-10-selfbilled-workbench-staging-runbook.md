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
