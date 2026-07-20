-- =============================================================================
-- V422: Team-role data hygiene + JK questionnaire straggler (Part 2, Phase 0).
--
-- Prerequisite data pass for the team-derived practice assignment (Phase 2):
-- after this migration, "at most one current MEMBER role per user" is TRUE in
-- the data, and the service-layer invariant shipped in the same release keeps
-- it true. Also removes the last JK reference V419 missed
-- (questionnaire.target_practices).
--
-- Spec: docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--       §4.4 step 0 / §1.6.E. Plan: 2026-07-19-practice-part2-phased-rollout.md
--       Phase 0.
--
-- All row sets are RE-DERIVED at execution time using the canonical temporal
-- predicate (spec §4.2): a role is current as-of D iff
--     startdate <= D AND (enddate IS NULL OR enddate > D)
-- (enddate IS NULL alone does NOT mean current — teamroles holds future-start
-- open rows and scheduled future ends). Re-derivation matters because this
-- file re-runs via repair-at-start and after the nightly prod→staging refresh
-- restores prod data.
--
-- State verified at authoring time (staging twservices4-staging, 2026-07-19):
--   * 11 TERMINATED users with 12 open roles (every termination date lies
--     after the role startdate, so the zero-length edge in step 1 is
--     defensive only). No teamroles row has a NULL startdate.
--   * 2 rows with useruuid IS NULL (both MEMBER on Team Tech it or Leave it).
--   * Exactly 1 dual-current-MEMBER user: deni.Klinac
--     (ab4ebf52-2203-4be6-a621-e0b4af847d88) — ARBA since 2024-02-01 AND
--     Team Cyber Security since 2024-03-01, both open. Their user.practice is
--     CYB and Team Cyber Security carries practice CYB; ARBA has no practice.
--
-- Rollback strategy: step 1's UPDATE is reversible from the audit of this
-- file (set enddate back to NULL for the affected users); steps 2–4 remove
-- rows/values that are wrong data by decision — restore from backup if ever
-- needed.
-- =============================================================================


-- 1) Close TERMINATED users' open team roles at their termination date. -------
--    Termination date = the user's latest userstatus row on or before today,
--    when that latest status is TERMINATED. Applies to every open role of the
--    user regardless of membertype (a terminated user holds no role of any
--    kind). Idempotent: once closed, enddate IS NULL no longer matches.
--
--    Edge case (documented decision, defensive only — no current row hits it):
--    if the termination date is on or before the role's startdate, the role
--    never overlapped an employed period; storing a zero/negative-length
--    interval is noise, so the row is DELETED instead.

DELETE tr
FROM teamroles tr
JOIN (
    SELECT us.useruuid, us.statusdate
    FROM userstatus us
    WHERE us.status = 'TERMINATED'
      AND us.statusdate = (SELECT MAX(us2.statusdate)
                           FROM userstatus us2
                           WHERE us2.useruuid = us.useruuid
                             AND us2.statusdate <= CURRENT_DATE)
) term ON term.useruuid = tr.useruuid
WHERE tr.enddate IS NULL
  AND term.statusdate <= tr.startdate;

UPDATE teamroles tr
JOIN (
    SELECT us.useruuid, us.statusdate
    FROM userstatus us
    WHERE us.status = 'TERMINATED'
      AND us.statusdate = (SELECT MAX(us2.statusdate)
                           FROM userstatus us2
                           WHERE us2.useruuid = us.useruuid
                             AND us2.statusdate <= CURRENT_DATE)
) term ON term.useruuid = tr.useruuid
SET tr.enddate = term.statusdate
WHERE tr.enddate IS NULL
  AND term.statusdate > tr.startdate;


-- 2) Delete the orphan rows with no user. -------------------------------------

DELETE FROM teamroles WHERE useruuid IS NULL;


-- 3) Resolve the dual-current-MEMBER user. ------------------------------------
--    Decision (Hans, 2026-07-19): the Team Cyber Security membership stands;
--    the ARBA row closes at 2024-03-01, the day Cyber Security began (half-open
--    [startdate, enddate) semantics preserve the one-month ARBA stint as
--    history). Keyed by user+team rather than row uuid so the statement is
--    portable across environments and idempotent.

UPDATE teamroles
SET enddate = '2024-03-01'
WHERE useruuid = 'ab4ebf52-2203-4be6-a621-e0b4af847d88'          -- deni.Klinac
  AND teamuuid = 'bd7c30c5-f6ce-4384-b891-2eee6bd61663'          -- ARBA
  AND membertype = 'MEMBER'
  AND enddate IS NULL;


-- 4) Strip the retired JK code from questionnaire targeting (V419 gap). -------
--    target_practices is a JSON array of code strings (known row: kyc-2026-q2
--    with ["SA","BA","PM","DEV","CYB","JK"]). Handled generically: the array
--    is rebuilt without any 'JK' element regardless of position or count,
--    preserving the order of the remaining elements. Guarded so only valid
--    JSON arrays actually containing "JK" are touched.

UPDATE questionnaire q
SET q.target_practices = COALESCE(
        (SELECT CONCAT('[', GROUP_CONCAT(JSON_QUOTE(jt.code) ORDER BY jt.ord SEPARATOR ','), ']')
         FROM JSON_TABLE(q.target_practices, '$[*]'
                         COLUMNS (ord FOR ORDINALITY, code VARCHAR(20) PATH '$')) jt
         WHERE jt.code <> 'JK'),
        '[]')
WHERE q.target_practices IS NOT NULL
  AND JSON_VALID(q.target_practices)
  AND JSON_SEARCH(q.target_practices, 'one', 'JK') IS NOT NULL;


-- =============================================================================
-- NOT fixed here, by design: 8 ACTIVE users hold no current MEMBER role under
-- the canonical predicate (verified 2026-07-19). They are legitimate business
-- edges, not dirty data — they become Phase 2's manual-assignment cases
-- (spec §4.2: direct assignment survives only for team-less users):
--   anna.mette.forbord            ff117b85-51a2-4955-8612-c612ba011b91  (SA)
--   joao.almeida                  1dfa6243-0ff1-4eb5-9d98-ecf1e05c11b2  (UD)
--   kenn.milo                     54bd01ee-c099-4d7f-a47a-5097e8acdd50  (UD)
--   mathias.pedersen@external.dk  fc5e41f8-c295-4fc0-a4c6-ee7762bbbe5d  (UD)
--   michael.christensen           cc59e760-bfbd-4c86-8306-21a4405e8a4f  (UD)
--   mkkel.thyboe.jakobsen         e9ab8606-e347-4626-a2d4-c99f95982730  (UD)
--   morten.empeno                 7076deb9-31bc-4d0a-a62b-8de3bda3a183  (UD)
--   torben.dalgaard               5881d160-bc59-4060-9abc-b8731aa6ece4  (UD)
-- (A further 11 non-TERMINATED team-less users are PREBOARDING or PAID_LEAVE —
-- outside the "active" definition used by the spec's count of 8.)
--
-- Also left alone: historical (already-closed) overlapping MEMBER intervals
-- for 6 other users — the service invariant only rejects writes that would
-- create NEW overlaps; closed history is not rewritten.
-- =============================================================================
