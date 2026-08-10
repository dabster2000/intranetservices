-- ===================================================================
-- V487: /recruitment/refer is an all-employee page — state it explicitly
-- ===================================================================
-- Feature: Recruitment ATS go-live access model (companion to V486)
-- Domain:  platform authorization (page_registry)
--
-- Purpose:
--   Production already has this row right (required_roles='USER',
--   is_visible=1) — referring a candidate is open to every employee, and
--   the BFF routes behind it (POST /api/recruitment/referrals,
--   GET /api/recruitment/referrals/mine) gate on USER accordingly.
--
--   Staging does not: it still carries required_roles='TEMP' and
--   is_visible=0, a leftover from the dark rollout that the nightly
--   prod->staging refresh has never corrected. A tester on staging is
--   therefore bounced off the referral page for reasons that have nothing
--   to do with the go-live change, which is exactly the kind of noise that
--   makes a verification pass untrustworthy.
--
--   V486 retired the TEMP gate on the other three recruitment pages but
--   left this one alone precisely because production looked correct. That
--   was the wrong call: "correct in prod today" is not the same as
--   "deterministic in every environment". This states it.
--
-- Effect: a no-op in production, a fix in staging and in any fresh
--   database. Idempotent — safe to re-run.
--
-- Not folded into V486: that migration has already been applied to
--   staging. Editing it would only get its checksum repaired at boot
--   (repair-at-start), never re-executed, so the fix would never land.
--
-- Author: Claude Code
-- Date:   2026-08-10
-- Rollback: none needed — this is the intended production state.
-- ===================================================================

UPDATE page_registry
   SET required_roles = 'USER',
       is_visible     = 1,
       modified_by    = 'V487'
 WHERE page_key = 'recruitment-refer';
