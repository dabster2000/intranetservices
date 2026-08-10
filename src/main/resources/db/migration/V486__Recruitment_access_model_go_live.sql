-- ===================================================================
-- V486: Recruitment access model — go-live
-- ===================================================================
-- Feature: Recruitment ATS go-live access model
--          (spec docs/superpowers/specs/2026-08-10-recruitment-access-model-go-live.md)
-- Domain:  recruitmentservice + platform authorization (roles/permissions/pages)
--
-- Purpose:
--   Put the recruiter (RECRUITMENT) and the team leads on the correct
--   footing so the module can go live, and retire two roles that were
--   carrying recruitment access by accident.
--
--   1. RECRUITMENT had a role_definition row and two holders but ZERO
--      role_permission rows — it granted nothing. It is registered here
--      as the recruiter role: recruitment:read/write/comp + documents:read.
--      NOT recruitment:gdpr (DPO's) and NOT users:write / signing:write —
--      creating the employee and sending the contract stay with ADMIN/HR
--      (decision D1/D2).
--
--   2. TECHPARTNER and PARTNER are removed from recruitment entirely
--      (decisions D7/D9). Neither was ever a deliberate recruitment
--      audience: TECHPARTNER arrived via the dossier flow and PARTNER via
--      the partner hiring track, which is circle-gated anyway. Access now
--      comes only from ADMIN, HR, RECRUITMENT or TEAMLEAD. Their other
--      permissions are untouched.
--
--   3. page_registry required_roles is corrected on every recruitment row.
--      NOTE these rows are *route access*, not menu visibility — is_visible
--      stays 0 on the sub-pages (decision D13: reachable by direct link,
--      Slack link and the landing page, but absent from the sidebar).
--      Getting this wrong in the other direction is what locked the
--      recruiter out: RouteAccessGuard redirects to /dashboard when
--      required_roles does not match, whatever the BFF allows.
--
--   4. Three pages were gated on the leftover TEMP role (2 holders, one of
--      them unrelated to recruitment). They move to their real audiences.
--
--   5. Three routes had NO page_registry row at all and were therefore
--      admitted by the fail-open route guard
--      (authorization.route-guard.fail-closed = false). They are
--      registered explicitly, plus the new restricted candidate brief.
--
-- Design notes:
--   * The registry row is UI routing + sidebar visibility, NOT the security
--     boundary — the BFF enforces requireRoles per route and the backend
--     enforces scopes + RecruitmentVisibility (circle hard filter,
--     involvement tiers, hired-file narrowing).
--   * TEAMLEAD reads widely and decides narrowly: the wide read is granted
--     here (recruitment:read was already present) and in
--     RecruitmentVisibility.POSITION_READ_ROLES; the narrow write is
--     enforced per position by canDecideOnApplication, not by a permission.
--   * recruitment:interview / recruitment:refer stay on USER — the
--     restricted candidate brief and the referral flow are open to every
--     employee and narrowed per-user by the backend.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts — seeds
--   are INSERT ... ON DUPLICATE KEY UPDATE / conditional UPDATE.
--
-- Staging caveat: the nightly prod->staging refresh wipes page_registry and
--   roles. After any staging reset this migration must be re-applied (or
--   the rows re-seeded) or the recruiter is locked out again.
--
-- Author: Claude Code
-- Date:   2026-08-10
-- Rollback:
--   DELETE FROM role_permission WHERE role = 'RECRUITMENT';
--   UPDATE role_permission SET revoked_at = NULL
--    WHERE role IN ('TECHPARTNER','PARTNER')
--      AND permission_key LIKE 'recruitment:%';
--   (page_registry required_roles would need restoring from the values
--    listed in the comments below.)
-- ===================================================================

-- -------------------------------------------------------------------
-- 0. New permission: recruitment:manage — "may run the candidate database"
--
--    recruitment:write could never express the recruiter tier: every team
--    lead holds it (it is what lets them move a candidate on a position
--    they own), so the UI gating recruiter-only buttons on it showed those
--    buttons to team leads, who then got 403 from the BFF and the backend's
--    isRecruiterTier check. Now that team leads can actually reach the
--    candidate grid, those dead buttons would be everywhere.
--
--    recruitment:manage covers: create candidates, bulk-tag, pool/unpool,
--    decline/withdraw, e-mail candidates, e-mail templates and settings,
--    the resource library and record-check outcomes.
--
--    V464 (the generated catalogue seed) has been regenerated to include
--    this key so the seed-drift gate passes, but V464 has already run in
--    every environment — repair-at-start realigns its checksum without
--    re-running it. The row therefore has to be inserted here, and before
--    the grants below: role_permission.permission_key is an FK onto it.
-- -------------------------------------------------------------------
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('recruitment:manage', 'Recruitment — manage candidates', NULL, 'Recruitment', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES
    ('ADMIN',       'recruitment:manage', 'ALL', NOW(), 'V486'),
    ('HR',          'recruitment:manage', 'ALL', NOW(), 'V486')
ON DUPLICATE KEY UPDATE
    revoked_at  = NULL,
    updated_at  = NOW(),
    modified_by = 'V486';

-- -------------------------------------------------------------------
-- 1. RECRUITMENT becomes a real role in the permission catalogue
--
--    The role_definition row is seeded first and deliberately, even though
--    production already has it (created by hand 2026-08-10 11:24):
--    role_permission.role is an FK onto role_definition.name, and any
--    environment whose data predates that hand-insert — staging after a
--    nightly refresh, a fresh local DB, CI — would fail the FK and take
--    the whole migration (and the deploy gate) down with it.
-- -------------------------------------------------------------------
INSERT INTO role_definition (name, display_label, is_system, created_at, updated_at)
VALUES ('RECRUITMENT', 'Recruiters', 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE display_label = VALUES(display_label);

INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES
    ('RECRUITMENT', 'recruitment:read',   'ALL', NOW(), 'V486'),
    ('RECRUITMENT', 'recruitment:write',  'ALL', NOW(), 'V486'),
    ('RECRUITMENT', 'recruitment:comp',   'ALL', NOW(), 'V486'),
    ('RECRUITMENT', 'recruitment:manage', 'ALL', NOW(), 'V486'),
    ('RECRUITMENT', 'documents:read',     'ALL', NOW(), 'V486')
ON DUPLICATE KEY UPDATE
    data_scope  = VALUES(data_scope),
    revoked_at  = NULL,
    updated_at  = NOW(),
    modified_by = 'V486';

-- -------------------------------------------------------------------
-- 2. TECHPARTNER and PARTNER lose recruitment access
--    (revoked, not deleted — the audit trail of the grant is kept)
-- -------------------------------------------------------------------
UPDATE role_permission
   SET revoked_at  = NOW(),
       updated_at  = NOW(),
       modified_by = 'V486'
 WHERE role IN ('TECHPARTNER', 'PARTNER')
   AND permission_key LIKE 'recruitment:%'
   AND revoked_at IS NULL;

-- -------------------------------------------------------------------
-- 3. page_registry — recruitment route audiences
--    (was: recruitment ADMIN,HR,TECHPARTNER,RECRUITMENT;
--          candidates/pipeline/positions/reports/interviews RECRUITMENT)
-- -------------------------------------------------------------------
UPDATE page_registry SET required_roles = 'ADMIN,HR,RECRUITMENT,TEAMLEAD', modified_by = 'V486'
 WHERE page_key IN ('recruitment', 'recruitment-candidates',
                    'recruitment-pipeline', 'recruitment-positions');

-- Reports are org-wide funnel metrics, not a per-position surface.
UPDATE page_registry SET required_roles = 'ADMIN,HR,RECRUITMENT', modified_by = 'V486'
 WHERE page_key = 'recruitment-reports';

-- "My interviews" shows only the caller's own assignments; every employee
-- may hold one, and it is the entry point to the restricted brief.
UPDATE page_registry SET required_roles = 'USER', modified_by = 'V486'
 WHERE page_key = 'recruitment-interviews';

-- -------------------------------------------------------------------
-- 4. Retire the TEMP gate on the three recruitment admin pages
--    (was: TEMP on all three — a leftover with 2 unrelated holders)
-- -------------------------------------------------------------------
UPDATE page_registry SET required_roles = 'ADMIN,RECRUITMENT', modified_by = 'V486'
 WHERE page_key = 'recruitment-settings';

UPDATE page_registry SET required_roles = 'DPO,ADMIN', modified_by = 'V486'
 WHERE page_key = 'recruitment-gdpr';

UPDATE page_registry SET required_roles = 'ADMIN', modified_by = 'V486'
 WHERE page_key = 'settings-recruitment-ai';

-- -------------------------------------------------------------------
-- 5. Routes that had no registry row (fail-open) + the new brief page
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission,
     display_order, section, icon_name, is_external, external_url)
VALUES
    -- The restricted candidate brief: any employee may hold the URL, and
    -- the backend answers 404 unless they are an assigned interviewer or a
    -- circle member on an ACTIVE candidate. Hidden from the menu — it is
    -- reached from "My interviews" and from Slack/e-mail links.
    ('recruitment-candidate-brief', 'Candidate brief', 0, '/recruitment/brief',
     'USER', NULL, 830, 'RECRUITMENT', 'UserSearch', 0, NULL),
    -- Referral + unsolicited intake triage: a recruiter queue.
    ('recruitment-triage', 'Triage', 0, '/recruitment/triage',
     'ADMIN,HR,RECRUITMENT', NULL, 845, 'RECRUITMENT', 'Inbox', 0, NULL),
    -- The interview resource library: reading it is open to every employee
    -- (the BFF list route already is); managing it is ADMIN/HR at the BFF.
    ('recruitment-resources', 'Interview resources', 0, '/recruitment/resources',
     'USER', NULL, 865, 'RECRUITMENT', 'BookOpen', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label     = VALUES(page_label),
    react_route    = VALUES(react_route),
    required_roles = VALUES(required_roles),
    display_order  = VALUES(display_order),
    section        = VALUES(section),
    icon_name      = VALUES(icon_name),
    modified_by    = 'V486';
