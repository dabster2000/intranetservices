-- ===========================================================================
-- Talent-pool retention — lift the deadlines that fell behind their consent
-- Author: pooling retention clock / consent expiry fix, 2026-09-02
-- Target: twservices4 (PRODUCTION), and staging for the rehearsal.
--
-- Run as a DB admin. `debugging-user` is SELECT-only on prod by design, so
-- this script was written and reviewed but NOT executed.
--
-- WHAT WENT WRONG. CandidateService.retentionDeadlineOnPooling compared the
-- fresh six-month pooling window against the candidate's retention_deadline
-- column alone. It never read recruitment_consents.expires_at, so a candidate
-- holding a GRANTED 12-month talent-pool consent next to a NULL deadline
-- looked unconstrained, and pooling stamped now + 6 months over a promise
-- that ran months longer. Reproduced on staging with candidate
-- 863c0d00-1d51-4893-b964-df19e7d4bd19: consent to 2027-08-02, pooling wrote
-- 2027-03-02.
--
-- The code fix (max of the fresh window, the existing deadline, and the
-- latest non-WITHDRAWN consent expiry) stops it happening again. This script
-- repairs the rows where it already happened.
--
-- SAFE TO RUN BEFORE OR AFTER THE DEPLOY. It only ever moves a deadline
-- LATER, and only to a date the candidate themselves consented to. It cannot
-- shorten a retention window and it cannot delete anything.
--
-- HOW MANY ROWS IT ACTUALLY REPAIRS (re-measured against prod 2026-09-02,
-- after this script was first written):
--
--   twservices4 (PROD)     0 rows. Not 11 -- see below. The script is a
--                          no-op there today and is kept as the standing
--                          repair, since the rule stays correct whenever
--                          divergence does appear.
--   twservices4-staging    1 row -- candidate 863c0d00, the deliberate
--                          repro. Running it there is a real rehearsal AND
--                          cleans up the artifact.
--
-- Why prod is 0, structurally: every candidate holding BOTH a consent expiry
-- and a retention deadline (16 of them) has the two EXACTLY EQUAL, because
-- grant() writes consent.expires_at and candidate.retention_deadline from the
-- same `now` in one transaction. Divergence can only be CREATED by a path
-- that rewrites one without reading the other -- which is the pooling path
-- this release fixes, and which has fired only 5 times in prod, all before
-- the clock shipped. So the exposure is entirely ahead of us, not behind:
-- 37 candidates with a GRANTED consent and a NULL deadline, ALL 37 of whom
-- would have been shortened by pooling, by an average of 177 days.
-- ===========================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- PRE-FLIGHT. Read this before running anything below. Expect 0 rows on
-- prod and 1 on staging (re-measured 2026-09-02). Each row is a candidate
-- whose retention deadline falls BEFORE the consent they granted. Run it as
-- a standing audit: a non-zero count on prod means a path is writing a
-- deadline without reading the consent, and is worth chasing to its source
-- before running the repair.
--
--   SELECT c.uuid,
--          c.status,
--          c.retention_deadline,
--          k.consent_expiry,
--          TIMESTAMPDIFF(DAY, c.retention_deadline, k.consent_expiry) AS days_lost
--   FROM recruitment_candidates c
--   JOIN (SELECT candidate_uuid, MAX(expires_at) AS consent_expiry
--         FROM recruitment_consents
--         WHERE status <> 'WITHDRAWN' AND expires_at IS NOT NULL
--         GROUP BY candidate_uuid) k ON k.candidate_uuid = c.uuid
--   WHERE c.status <> 'ANONYMIZED'
--     AND c.retention_deadline IS NOT NULL
--     AND k.consent_expiry > c.retention_deadline
--   ORDER BY days_lost DESC;
--
-- A row dropping OUT of this set is not automatically good news: the GDPR
-- sweep anonymizes on retention_deadline, so it may have dropped out by
-- being deleted rather than repaired. (Sub-sweep 3 refuses to anonymize a
-- candidate holding a live GRANTED consent, so this should not happen --
-- verify rather than assume.) Check the anonymization log:
--
--   SELECT COUNT(*) FROM recruitment_events
--   WHERE event_type = 'CANDIDATE_ANONYMIZED' AND occurred_at >= '2026-09-02';
--   -- (column is occurred_at, not created_at; total ever = 1, on 2026-08-11)
-- ---------------------------------------------------------------------------

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- SECTION A — lift each lagging deadline to the consent it fell behind.
-- Repairs 0 rows on prod today and 1 on staging; safe either way.
--
-- The subquery is deliberately the same rule the code now applies
-- (RecruitmentConsentService.latestStandingConsentExpiry): the latest
-- expires_at among the candidate's non-WITHDRAWN consents. WITHDRAWN is
-- excluded because withdrawal is the candidate taking the promise back --
-- extending retention on the strength of a consent they revoked would be the
-- same class of mistake in the opposite direction.
--
-- Rows are matched by rule, not by a hard-coded uuid list, so this stays
-- correct if the set shifted between measuring and running. The WHERE clause
-- guarantees it can only ever move a deadline later.
--
-- retention_deadline is DATETIME(3); updated_at carries ON UPDATE
-- CURRENT_TIMESTAMP, so it re-stamps itself.
-- ---------------------------------------------------------------------------
UPDATE recruitment_candidates c
JOIN (SELECT candidate_uuid, MAX(expires_at) AS consent_expiry
      FROM recruitment_consents
      WHERE status <> 'WITHDRAWN' AND expires_at IS NOT NULL
      GROUP BY candidate_uuid) k ON k.candidate_uuid = c.uuid
SET c.retention_deadline = k.consent_expiry
WHERE c.status <> 'ANONYMIZED'
  AND c.retention_deadline IS NOT NULL
  AND k.consent_expiry > c.retention_deadline;

-- ---------------------------------------------------------------------------
-- NOT IN SCOPE HERE — the 37 candidates with a GRANTED consent and a NULL
-- retention_deadline. This is the REAL exposure, and it is entirely ahead of
-- us: re-measured 2026-09-02, all 37 would have had their retention
-- shortened by being pooled, by an average of 177 days (max 183). Their
-- consents run to 2027-08-11 .. 2027-09-02.
--
-- They are not damaged: a NULL deadline is skipped by the sweep, so nothing
-- is scheduled to delete them early. The code fix is what protects them --
-- pooling any of them now records the consent expiry instead of overwriting
-- it.
--
-- Giving them a deadline is a separate, deliberate decision, because it ARMS
-- the sweep for candidates it currently skips: writing consent_expiry into
-- the column means they are anonymized when the consent lapses. That is
-- arguably what the consent says, and it is the "candidate bank that only
-- grows" problem the pooling clock was added to close -- but it is a policy
-- call for the DPO, not a repair, and it is the one direction this script
-- could do harm in. Left for a human to decide:
--
--   UPDATE recruitment_candidates c
--   JOIN (SELECT candidate_uuid, MAX(expires_at) AS consent_expiry
--         FROM recruitment_consents
--         WHERE status = 'GRANTED' AND expires_at IS NOT NULL
--         GROUP BY candidate_uuid) k ON k.candidate_uuid = c.uuid
--   SET c.retention_deadline = k.consent_expiry
--   WHERE c.status <> 'ANONYMIZED' AND c.retention_deadline IS NULL;
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Verify before committing. Expect ZERO rows: after section A no live
-- candidate should have a consent that outlives their retention deadline.
-- (On prod this reads zero before the UPDATE as well -- that is expected.)
-- ---------------------------------------------------------------------------
SELECT c.uuid, c.status, c.retention_deadline, k.consent_expiry
FROM recruitment_candidates c
JOIN (SELECT candidate_uuid, MAX(expires_at) AS consent_expiry
      FROM recruitment_consents
      WHERE status <> 'WITHDRAWN' AND expires_at IS NOT NULL
      GROUP BY candidate_uuid) k ON k.candidate_uuid = c.uuid
WHERE c.status <> 'ANONYMIZED'
  AND c.retention_deadline IS NOT NULL
  AND k.consent_expiry > c.retention_deadline;

-- COMMIT;    -- uncomment once the SELECT above returns no rows
-- ROLLBACK;  -- otherwise
