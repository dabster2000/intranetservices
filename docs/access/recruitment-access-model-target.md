# Recruitment access model — target state

> **Status: DECIDED, NOT BUILT.** Sixteen decisions locked 2026-08-23. No code,
> schema, permission grant or production change has been made. This document is
> the specification to build from — it describes the **target**, not the
> current system.
>
> Companion discussion artifact (as-is grids, evidence, the reasoning):
> <https://claude.ai/code/artifact/a1d112f9-2817-4685-b90b-55d0dec62642>

Spans two repos: `intranetservices` (backend) and `trustworks-intranet-v2`
(frontend/BFF). Both were on `staging` when this was written.

---

## 1. The model

Two axes, and nothing else:

- **What you can do = your role × who you are involved with.**
  Role decides which *kinds* of action; involvement decides which *records*.
- **What you hear about = what happened × who you are involved with.**
  No role appears anywhere in the notification system, and none is being added.

Cell vocabulary used throughout: **●** everything · **◐** scoped (the cell says
how) · **○** none.

### Involvement routes after this change

| Route | Kept? |
|---|---|
| Named hiring owner | Yes |
| Sits on the position's hiring circle | Yes |
| Assigned interviewer | Yes |
| Referred the candidate | Yes |
| Leads the position's team | Redundant — TEAMLEAD now qualifies by role |
| **Runs the position's practice** | **REMOVED from recruitment (decision 11)** |

Practice membership still matters, but only as `ASSISTANT_TEAMLEAD`'s scope —
read from `user.practice_uuid`, never from `practice_lead` or `teamroles`.

---

## 2. Target state, in words

**TEAMLEAD** — every non-partner pipeline, regardless of which practice they sit
in. Move stages, decide outcomes, edit and close positions, create positions,
schedule interviews, record interview decisions, run AI scheduling, work the
Inbox, triage referrals, pool and unpool, bulk-tag, and edit a candidate's
profile fields. Partner track remains circle-only.

*Not:* candidate email, approving queued email, regenerating the AI brief,
converting a hire, hard delete, reports, or any configuration surface.

**ASSISTANT_TEAMLEAD** (new) — the same capability set, scoped to the practice
they are a member of, minus:

- **final outcomes** — hire, reject, withdraw, return-to-pool;
- **the offer dossier** — no read, no signature status, no comp;
- **candidate creation**;
- **the Inbox** — falls out of decision 8, not a separate rule (see §4).

Notifications: their practice Slack channel. No personal nudges unless they are
a named hiring owner or on a circle — same as everyone else.

**Rights come from roles only.** After decision 11 there is no path by which a
person holds recruitment rights without holding a recruitment role.

---

## 3. The sixteen decisions

| # | Decision |
|---|---|
| 1 | TEAMLEAD scope: **all non-partner**, practice irrelevant. Read and decide collapse to one tier. |
| 2 | New role is **`ASSISTANT_TEAMLEAD`** — a team lead scoped to one practice, not a junior recruiter. |
| 3 | Its practice = the one they are a member of, `user.practice_uuid`. No new table. |
| 4 | Its authority = everything a team lead can do **except final outcomes**. |
| 5 | Its candidate visibility = only candidates with an application on a position in their practice. |
| 6 | Its notifications = **practice channel only**. |
| 7 | **Final outcome = hire, reject, withdraw, return-to-pool — all four.** Hiring is a stage move in the data, so this needs its own guard. |
| 8 | Candidates with no application are **invisible** to an assistant. |
| 9 | **No offer dossier** for an assistant. The one deliberate deviation from "same rights as a team lead". |
| 10 | Assistants do **not** create candidates. |
| 11 | The **practice-lead route is removed** from recruitment entirely. |
| 12 | The **Inbox opens to TEAMLEAD** — widen the queues rather than hide the tab. |
| 13 | The Inbox **actions open with it** — TEAMLEAD may triage a referral and pool/unpool. |
| 14 | Editing a candidate: **profile fields open to TEAMLEAD**, GDPR fields stay closed. *(Already true at the API layer — see §5.)* |
| 15 | **Bulk-tagging opens to TEAMLEAD.** |
| 16 | **Reports stay closed** to TEAMLEAD — they share `recruitment:comp` with salary bands. |

---

## 4. Target matrices

Columns: ADMIN · HR · RECRUITMENT · **AT** (`ASSISTANT_TEAMLEAD`) · **TL**
(`TEAMLEAD`) · Everyone else. Only rows that change, or that constrain the new
role, are listed — every unlisted row keeps its current value.

### Tabs

| Tab | Gate | ADMIN | HR | RECR | AT | TL | Else |
|---|---|:--:|:--:|:--:|:--:|:--:|:--:|
| Overview · Pipeline · Positions · Interviews · Resources | module access | ● | ● | ● | ● | ● | ● |
| Candidates | `recruitment:write` | ● | ● | ● | ◐ practice | ● | ○ |
| Inbox | **needs its own gate** | ● | ● | ● | **○** | **●** | ○ |
| Settings | **needs its own gate** | ● | ● | ● | ○ | ○ | ○ |
| Reports | `recruitment:comp` | ● | ● | ● | ○ | ○ | ○ |

> Inbox and Settings both hang off `recruitment:write` today, so "Inbox yes,
> Settings no" is **inexpressible** until the two nav gates are split. That
> split is a prerequisite for decisions 12 and 13, not optional tidying.

### Pipelines and positions

| Action | ADMIN | HR | RECR | AT | TL | Else |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| See a non-partner position + board | ● | ● | ● | ◐ practice | ● | ◐ involved |
| See a partner-track position | ● | ◐ circle | ◐ circle | ◐ circle | ◐ circle | ◐ circle |
| Move a candidate through stages | ● | ● | ● | ◐ practice | ● | ◐ involved |
| **Final outcomes** — hire, reject, withdraw, return-to-pool | ● | ● | ● | **○** | ● | ◐ involved |
| Skip stages (fast-track) | ● | ● | ● | ◐ practice | ● | ◐ owner |
| Edit or close a position | ● | ● | ● | ◐ practice | ● | ◐ involved |
| Create a position | ● | ● | ● | ◐ practice | ● | ○ |
| Manage the hiring circle | ● | ● | ◐ circle owner | ◐ practice | ◐ circle owner | ○ |
| Assign to a team · move application | ● | ● | ● | ◐ practice | ● | ○ |

### Candidates

| Action | ADMIN | HR | RECR | AT | TL | Else |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| Open a candidate profile | ● | ● | ● | ◐ practice | ● | ◐ restricted |
| Create a candidate | ● | ● | ● | **○** | ● | ○ |
| **Edit profile fields** | ● | ● | ● | ◐ practice | **●** | ○ |
| Edit GDPR fields | — | — | — | ○ | ○ | ○ |
| Notes · documents | ● | ● | ● | ◐ practice | ● | ○ |
| **Pool / unpool** | ● | ● | ● | ◐ practice | **●** | ○ |
| **Bulk-tag** | ● | ● | ● | ◐ practice | **●** | ○ |
| **Triage a colleague referral** | ● | ● | ● | ○ | **●** | ○ |
| Send candidate email · approve queued email | ● | ● | ● | ○ | ○ | ○ |
| Regenerate the AI brief | ● | ● | ● | ○ | ○ | ○ |
| Convert a hire to an employee | ● | ● | ○ | ○ | ○ | ○ |
| Hard delete (`recruitment:admin`) | ● | ○ | ○ | ○ | ○ | ○ |

> "Edit GDPR fields" is `○` for **everyone** through this endpoint — the fields
> are system-maintained. It is listed only so the split is unambiguous.

### Interviews

| Action | ADMIN | HR | RECR | AT | TL | Else |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| My interviews · scorecards · guides | ● | ● | ● | ● | ● | ◐ assigned |
| Schedule or cancel · AI scheduling | ● | ● | ● | ◐ practice | ● | ○ |
| Record the interview decision | ● | ● | ● | ◐ advance only | ● | ○ |
| Meeting-room policy | ● | ○ | ○ | ○ | ○ | ○ |

### Offer dossier — unchanged except the new column

| Action | ADMIN | HR | RECR | AT | TL | Else |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| Read dossier · revisions · signature status | ● | ● | ○ | **○** | ◐ hiring owner | ○ |
| Write · appendices · send for review/signature | ● | ● | ○ | ○ | ○ | ○ |

### Notifications — no change

Channel routing and the 24 `recruitment.slack.*` flags already cover the target.
Adding an assistant to their practice Slack channel is the entire change, and it
is a Slack action, not a code or config one.

---

## 5. Change inventory

### 5.1 Configuration — no deploy, same day

| What | Where |
|---|---|
| Create `ASSISTANT_TEAMLEAD` | `role_definition` via the access-management console (`RoleManagementTab`). Roles are plain strings; `RoleType.java` is `@Deprecated(forRemoval)` and **not** authoritative. |
| Grant its permissions | `role_permission`: `recruitment:read`, `recruitment:write`, `recruitment:manage`, `recruitment:intake` — all `ALL` scope. **Not** `recruitment:comp`, `recruitment:gdpr`, `recruitment:admin`. |
| Assign it to people | `UserRolesTab` |

### 5.2 Frontend — `trustworks-intranet-v2`

**Nav gate split** — `src/app/(protected)/recruitment/components/RecruitmentNav.tsx`.
Inbox and Settings currently both use `recruitment:write`. Give each its own
permission so the target table is expressible. Whichever keys are chosen must be
added to `role_permission` for the roles that should hold them.

**BFF role arrays.** 109 arrays across 104 files in `src/app/api/recruitment/`;
75 name `RECRUITMENT`. Add `'ASSISTANT_TEAMLEAD'` wherever the assistant
qualifies, and `'TEAMLEAD'` to these specific routes for decisions 12–15:

| Route | Today | Target | Decision |
|---|---|---|---|
| `candidates/triage-queue/route.ts` | `ADMIN,HR,RECRUITMENT` | + `TEAMLEAD` | 12 |
| `referrals/pending/route.ts` | `ADMIN,HR,RECRUITMENT` | + `TEAMLEAD` | 12 |
| `referrals/[uuid]/triage/route.ts` | `ADMIN,HR,RECRUITMENT` | + `TEAMLEAD` | 13 |
| `candidates/[uuid]/pool/route.ts` | `ADMIN,HR,RECRUITMENT` | + `TEAMLEAD` | 13 |
| `candidates/[uuid]/unpool/route.ts` | `ADMIN,HR,RECRUITMENT` | + `TEAMLEAD` | 13 |
| `candidates/[uuid]/route.ts` (`WRITE_ROLES`) | `ADMIN,HR,RECRUITMENT` | + `TEAMLEAD` | 14 |
| `candidates/tags/bulk/route.ts` | `ADMIN,HR,RECRUITMENT` | + `TEAMLEAD` | 15 |
| `reports/route.ts` | `ADMIN,HR,RECRUITMENT` | **unchanged** | 16 |

**After any role-array edit run `npm run access:gen`** —
`src/access/access-manifest.json` is a committed generated file and
`npm run access:check` fails CI on drift.

**Recommended first, and it shrinks everything above:** replace the hand-written
role-name arrays with three shared tier constants (recruiter tier / hiring tier /
module tier). It turns this change from a 75-file sweep into a handful, and makes
the next role a one-line edit. Without it, every future role repeats this cost.

### 5.3 Backend — `intranetservices`

All in `src/main/java/dk/trustworks/intranet/recruitmentservice/security/RecruitmentVisibility.java`
unless stated.

| Change | Detail |
|---|---|
| **Widen TEAMLEAD (decision 1)** | `canDecideOnApplication` currently short-circuits on `RECRUITER_TIER_ROLES` then falls through to involvement. Make `POSITION_READ_ROLES` (which already contains `TEAMLEAD`) sufficient for the non-partner branch. `canMutatePosition` delegates, so it follows for free. This is a **deletion**, not an addition. |
| **Remove the practice route (decision 11)** | Drop `runsPracticeOf` from `canDecideOnApplication`, and drop `currentlyLedPractices` / `practicesOfCurrentlyLedTeams` from `ownPractices` wherever it feeds recruitment. Also remove the practice hop from `isRecruiterOrHiringOwner` and `isHiringOwnerForCandidate`, and from the involvement tier in `filterPositions` / `decidablePositionUuids`. |
| **Add `ASSISTANT_TEAMLEAD`** | New tier constant. Add to `POSITION_READ_ROLES` and `PROFILE_READ_ROLES` **scoped**, not wholesale — the assistant does not read company-wide. Do **not** add to `RECRUITER_TIER_ROLES` (that tier means module-wide queues) or `HIRED_FILE_ROLES`. |
| **Practice scoping (decisions 3, 5)** | New predicate resolving `user.practice_uuid`, applied to positions **and** to candidates. Candidate scope = "has an application on a position in my practice"; a candidate with no application resolves to no practice and is therefore invisible (decision 8). |
| **Final-outcome guard (decision 7)** | Must check **four** things, not three. `RecruitmentApplicationTerminal` is only `REJECTED`, `WITHDRAWN`, `RETURNED_TO_POOL` — **hiring is a stage move**, so a terminal-only check silently lets an assistant hire. |
| **Empty-practice guard** | An assistant whose `user.practice_uuid` is null must be refused at assignment time with a clear message — not left with a silently empty screen. |
| **Circle** | `canManageCircle` hardcodes `"HR"` alongside `ROLE_ADMIN`. Leave as-is unless the assistant should manage circles; the target table says they may, within their practice. |

**No change needed for decision 14.** `CandidateRequest` already omits
`lawfulBasis`, `art14*`, `retentionDeadline` and `poolStatus` — its own docstring
records this as deliberate. The GDPR fields are not writable through
`PUT /candidates/{uuid}` by anyone. Decision 14 is satisfied by the BFF role-array
edit alone.

**No change needed for notifications.** No role appears in that system.

---

## 6. Work order

1. **Nav gate split** (frontend only). Small, fixes a live defect for 20 people,
   and unblocks decisions 12–13. Ship alone.
2. **Tier constants in the BFF** (frontend only). Optional but strongly
   recommended before step 4.
3. **Backend**: widen TEAMLEAD, remove the practice route, add the assistant
   tier, practice scoping, final-outcome guard.
4. **Frontend**: BFF role arrays + `npm run access:gen`.
5. **Config**: create the role, grant permissions, assign people, add them to
   practice Slack channels.

**Backend before frontend.** The frontend treats missing `viewerCanDecide` /
`viewerCanMutate` as `false`, so a frontend-first deploy renders the module
read-only for everyone including HR.

---

## 7. Verification

- Backend fast tier: `./mvnw test -DexcludedGroups=io.quarkus.test.junit.QuarkusTest`
  — this is exactly what the CI deploy gate runs.
- `@QuarkusTest` classes that pin the **old** rules and will need reversing:
  `RecruitmentVisibilityIntegrationTest`, `RecruitmentLandingApiTest`,
  `RecruitmentVisibilityHiredFileTest`. None are in the CI gate — they only run
  against a local DB, so they must be run deliberately.
- Frontend: `npm run type-check`, `npm run lint`, `npm run test:run`,
  `npm run access:check`.
- Manual: sign in as a TEAMLEAD and confirm the Inbox and Settings tabs behave
  as the target table says — no visible control that returns 403.

---

## 8. Traps

- **`@RolesAllowed` gates the API client, not the person.** The BFF's system
  token carries `admin:*` and `AdminScopeAugmentor` expands it to every key, so
  an annotation alone admits every employee. Per-user authority must be checked
  explicitly in the resource via `RecruitmentVisibility`.
- **The BFF role array is the usual culprit** when a role "can't do X" but
  should. Check it before the backend.
- **A wider read gate than write gate, with the UI rendering the write control,
  is always a 403 behind a visible button.** That is the defect class this whole
  change exists to remove — do not reintroduce it.
- **Gating `isDraggable` alone does not make a board read-only.** `BoardCard`
  renders its kebab menu from the *presence* of action handlers, so `cardActions`
  must be withheld wholesale.
- **`POST /recruitment/positions` has no per-person gate at all** — `hiringTrack`
  comes straight off the request, so anyone the BFF admits can open a
  partner-track position. Pre-existing; fix it while in here or record it as
  accepted.
- Slack interactivity reaches the backend **without passing through the BFF**, so
  BFF role arrays are not a complete gate for Slack-driven actions.

---

## 9. Explicitly out of scope

Notification routing and thresholds · the offer-dossier write path · the GDPR
lane (`DPO` + `ADMIN`) · `recruitment:comp` and the reports gate · partner-track
rules · the public `/apply` funnel · anything outside `/recruitment`.
