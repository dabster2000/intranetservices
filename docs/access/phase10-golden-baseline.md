# Phase 10 golden baseline — delivery & people data scope (captured 2026-08-06)

Pre-change picture per phase-file step 10.8 / the 9.5 method, captured against the
**production** database (read-only) before any Phase 10 deploy. Personal data never
leaves the database: fixtures are role names, uuids already public inside the company,
counts and flags.

**Phase 10 differs from Phase 9 in one structural way: no query changes shape.**
Every Phase 10 enforcement is a *guard* (`@ScopeEnforced` deny-bounded, or a
subject-reach check before an already-per-subject query) — never a new `WHERE`
binding. A guard that passes leaves the row set byte-identical by construction, so
this baseline proves the two things that actually decide behaviour:

1. the **grant matrix pre-picture** (which guard verdicts each role produces), and
2. the **DPO re-key equivalence** (the one rule moving from hardcode to grant).

## Grant matrix pre-picture (prod `role_permission`, pre-V470 shape — no `data_scope` column yet)

| Key | Holders (prod, 2026-08-06) |
|---|---|
| `timeregistration:read` / `write` | USER |
| `timeregistration:admin` | *(nobody — the BFF authority setting governs unlock, Decision 11)* |
| `teams:read` | ADMIN, TEAMLEAD |
| `teams:write` | ADMIN, HR |
| `crm:read` | USER · `crm:write` SALES, ADMIN, PARTNER |
| `contracts:read` | USER · `contracts:write` ADMIN, PARTNER, SALES |
| `recruitment:read` | ADMIN, HR, PARTNER, TEAMLEAD, TECHPARTNER, USER |
| `recruitment:write` | ADMIN, HR, PARTNER, TEAMLEAD, TECHPARTNER |
| `recruitment:gdpr` | ADMIN, DPO |
| `documents:read` | ADMIN, HR, USER · `documents:write` ADMIN |
| `documents:gdpr` | *(nobody — Phase 10 seeds ADMIN + DPO as bookkeeping)* |
| `conference:read` | *(nobody — reads stay session-gated)* · `conference:write` ADMIN, MARKETING |

Verdicts these produce after the Phase 10 seeds (V473/V474), for actor-carrying calls:

- **timeregistration:** every grant stays `ALL` (Decision 14) → every guard passes for
  every employee; the `@ScopeEnforced` and subject-check paths are inert pins for a
  future console narrowing. Zero behaviour change.
- **teams:** `TEAMLEAD → teams:write @ TEAM` is a new sub-`ALL` row → boolean-invisible;
  membership-write guard passes HR/ADMIN (unbounded) and headerless callers unchanged.
  Zero behaviour change today; the row is Decision 10's forward intent.
- **documents:** `USER → documents:read @ OWN` narrows reach exactly to the audience the
  BFF already enforces (`allowSelf: true, allowTeamLead: false`); `HR → documents:write @ ALL`
  is bookkeeping matching the BFF's HR upload audience (without it, the upload guard
  would 403 HR the day actor headers arrive); `documents:gdpr → ADMIN + DPO` closes a
  matrix hole (held by nobody). Zero behaviour change.
- **recruitment / clients / contracts / conferences:** no grant or scope rows change at all.

## DPO re-key equivalence (10.5 — the only rule moving from hardcode to grant)

`RecruitmentVisibility.HIRED_FILE_ROLES` hardcodes `DPO`; Phase 10 re-keys that
membership on holding `recruitment:gdpr`. Equivalence in prod:

- DPO-role holders: `7948c5e8-162c-4053-b905-0f59a21d7746` (exactly one).
- Grant-resolvable `recruitment:gdpr` holders: the 5 ADMINs ∪ the 1 DPO holder.
- The DPO holder's own roles include **ADMIN and HR** — they short-circuit the
  hired-file check at the ADMIN tier (and would pass the HR role tier regardless).
- ADMINs short-circuit before the hired-file check by construction.

∴ For every current production user, hired-file access before and after the re-key is
**identical**. The re-key's value is governance: revoking `(DPO, recruitment:gdpr)` in
the console now removes the access without a code change.

## Employee documents pre-picture

`employee_documents` holds exactly **1 row** (user `7948c5e8-…`, `hr_only = 1`,
not archived, not in review). The S3-only document system is newly live; the scoped
guards land while the table is near-empty, which is the safest possible moment.

## Sentinels (no-change proof points for the deploy diffs)

| Sentinel | Value |
|---|---|
| Current `teamroles` LEADER rows (input to the own-team write guard) | 14 |
| `work` rows, July 2026 (timeregistration surfaces untouched) | 2 883 |
| `recruitment_candidates` with status `HIRED` (hired-file gate population) | 5 |

## What this baseline does NOT cover

- **Live per-role API responses (V10.1).** Those need real sessions per role and run
  against staging at each domain deploy (plan principle P7), diffing before/after.
- **The 10.7 guard retirements.** Each is its own commit, deployed only after the
  replacement is verified live in its domain; behaviour equality there is asserted by
  the FE unit suites pinning old-vs-new denial parity per route.
