# Phase 10 golden baseline — delivery & people data scope (captured 2026-08-06)

> **Read the 2026-08-16 amendment at the foot of this file before using the recruitment
> rows.** The recruitment access-model go-live (V486) landed after this capture and changed
> both the recruitment grant matrix and the hired-file role tier. The rest of the baseline —
> timeregistration, teams, documents, conferences, the sentinels — is unaffected.

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

---

## Amendment 2026-08-16 — the recruitment access model changed after this capture

The capture above is dated 2026-08-06 and is left intact as the record of what production
looked like then. Between that date and the Phase 10 deploy, the **recruitment access-model
go-live (V486, decisions D3/D6/D7)** shipped to production. Two parts of this baseline are
superseded by it; everything else stands.

### 1. The recruitment rows of the grant matrix are stale

`recruitment:read` / `recruitment:write` no longer hold the audiences tabulated above —
V486 rewrote them, and D7 removed `TECHPARTNER` from the recruitment module entirely.
**Do not use the two recruitment rows as the pre-picture for the Phase 10 deploy.** Re-capture
them against production immediately before the Phase 10 domain deploy. The non-recruitment
rows (timeregistration, teams, crm, contracts, documents, conference) were not touched by
V486 and remain valid.

### 2. The hired-file tier is a different set than 10.5 assumed

Phase 10.5 was written against `HIRED_FILE_ROLES = {HR, CXO, TECHPARTNER}`. The go-live
changed that tier to `{HR, RECRUITMENT, DPO}`. On rebase (findings, 2026-08-16) the conflict
was resolved as:

```java
static final Set<String> HIRED_FILE_ROLES = Set.of("HR", "RECRUITMENT");   // go-live's D3/D6/D7 membership
// + holdsRecruitmentGdprGrant(viewerUuid)                                  // 10.5's DPO re-key
```

i.e. the go-live's membership is authoritative and Phase 10.5 contributes **only** the DPO
re-key. Replaying 10.5's literal set would have put `CXO` and `TECHPARTNER` back into the
recruitment module, reverting D7.

### 3. What the equivalence argument still rests on

The two load-bearing claims are code, not data, and are unaffected by V486:

- ADMIN short-circuits before the hired-file check.
- `HR` is in the tier both before and after, so an HR-holder's access is unchanged.

The two **data** facts below were true on 2026-08-06 and are what make the re-key
effect-preserving for the DPO specifically. Neither has been re-confirmed since V486/V497
moved role grants around, so **both must be re-checked against production immediately before
the Phase 10 deploy** — this is a deploy gate, not a formality:

| Fact to re-confirm | Value at capture | Why it matters |
|---|---|---|
| `recruitment:gdpr` holders | ADMIN, DPO (seeded V465) | if DPO lost this grant, the re-key *removes* their hired-file access |
| The sole DPO holder's own roles | include ADMIN **and** HR | if that changed, the DPO holder now depends on the grant alone |

If both still hold, hired-file access before and after the re-key is identical for every
production user, exactly as argued in the 2026-08-06 section. If either has changed, stop and
re-decide the re-key — do not deploy 10.5 on the strength of the old capture.
