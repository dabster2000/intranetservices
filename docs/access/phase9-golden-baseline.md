# Phase 9 golden baseline — finance data scope (captured 2026-08-06)

Pre-change equality proof per phase-file step 9.5, captured against the
**production** database (read-only) before any Phase 9 deploy. Personal data
never leaves the database: fixtures are row counts and CRC32 aggregates.

Method: for each scoped query introduced in Phase 9, run **today's** query and
the **scoped** variant with the reach the seeds produce (`OWN` = the user
themselves; unbounded for HR/ADMIN), for a representative production user per
role, and require byte-equal row sets. Any future difference is either an
intended tightening or a defect — there is no third category.

Representative subjects (production user uuids, roles verified in `roles`):

| Subject | Why |
|---|---|
| `bc2f6cba-1a66-4604-8dbe-fedfef8d31fb` | plain USER (no privileged roles), 900+ expense rows |
| `f373048b-5790-47a8-912b-530db199161c` | plain USER with FY2026 bonus-basis rows |
| `4c01ceb8-0b93-4b91-9773-52cee0aa5e57` | HR (unbounded expenses/bonus reach) |
| `0571e4c6-5ff5-11e6-8b77-86f30ca893d3` | ADMIN (unbounded after the V471 bookkeeping seed) |

## Results — before vs after row sets

| # | Surface | Before | After (scoped) | Verdict |
|---|---------|--------|----------------|---------|
| G1 | Expense ledger, plain USER (`useruuid = self` vs `+ useruuid IN (self)`) | 910 rows, CRC 1947771307800 | 910 rows, CRC 1947771307800 | **identical** |
| G2 | Bonus basis FY2026, plain USER (BFF post-filter slice vs `WHERE useruuid IN (self)`) | 12 rows, CRC 24183104400 | 12 rows, CRC 24183104400 | **identical** |
| G3 | Eligibility day rows FY2025 window, plain USER (slice vs `user.uuid in (self)`) | 44 rows, CRC 107159055469 | 44 rows, CRC 107159055469 | **identical** |
| G4 | HR review period list (Jul 1 – Aug 6 2026) — unbounded actor, query unchanged | 294 rows | 294 rows | **identical** |

Invoices (9.1) intentionally have **no** G-row: no query changes at all — the
enforcement filter is a pass-through for every ALL-scope grant, and every
`invoices:*` grant is ALL (owner Decision 5).

## What this baseline does NOT cover

- **Serialized payload shape.** The 9.3 basis payload deliberately changes shape
  (the `BasisUser` trim — access-intent Decision 8); its consumers' *responses*
  are pinned by the BFF unit suites instead.
- **Live per-role API responses (V9.1).** Those need real sessions per role and
  run against staging at each domain deploy (plan principle P7), diffing
  before/after the deploy; this file is the committed pre-picture proving the
  scoped queries are row-set-preserving at the data layer.
