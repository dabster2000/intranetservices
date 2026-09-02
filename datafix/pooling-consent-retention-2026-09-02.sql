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
-- ===========================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- PRE-FLIGHT. Read this before running anything below. Expect 11 rows
-- (measured 2026-09-02). Each one is a candidate whose data is currently
-- scheduled for anonymization BEFORE the consent they granted runs out.
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
-- If the count has moved far from 11, stop and re-measure: the GDPR sweep
-- anonymizes on retention_deadline, so a row that has since dropped out of
-- this set may have dropped out by being deleted. Check first:
--
--   SELECT COUNT(*) FROM recruitment_events
--   WHERE event_type = 'CANDIDATE_ANONYMIZED' AND created_at >= '2026-09-02';
-- ---------------------------------------------------------------------------

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- SECTION A — lift each lagging deadline to the consent it fell behind.
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
-- retention_deadline.
--
-- They are not damaged: a NULL deadline is skipped by the sweep, so nothing
-- is scheduled to delete them early. They were the 37 rows one pooling away
-- from the bug, and the code fix is what protects them -- pooling any of them
-- now records the consent expiry instead of overwriting it.
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
