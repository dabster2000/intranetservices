-- ===================================================================
-- V526: Recruitment access model — TEAMLEAD widening + assistant prep
-- ===================================================================
-- Feature: docs/access/recruitment-access-model-target.md §6 steps 3–4
--          (decisions 1, 11, 12/13; the ASSISTANT_TEAMLEAD role itself is
--          created in the admin console — §5.1 — NOT here).
-- Domain:  recruitmentservice + platform authorization
--
-- ⚠ DEPLOY ORDER: this migration rides the SAME backend deploy as the
--   RecruitmentVisibility rework (decision-1 widening, final-outcome
--   guard, inbox tier). Deploy the frontend (nav-gate split + BFF role
--   arrays + per-action board flags) IMMEDIATELY AFTER this backend —
--   between the two deploys a TEAMLEAD has the Inbox grant while the old
--   frontend still keys some surfaces off recruitment:manage; statement 2
--   below makes that window read as "hidden", never as a 403.
--
-- WHAT THIS DOES, AND WHY
--
-- 1. Grant recruitment:triage to TEAMLEAD (decisions 12/13). V525 minted
--    the key and granted ADMIN/HR/RECRUITMENT, deliberately deferring
--    TEAMLEAD until the Inbox BFF routes and backend queue gates opened to
--    them — which is this deploy (isInboxTier). With the grant, the Inbox
--    tab renders for team leads and every control on it works.
--
-- 2. REVOKE recruitment:manage FROM TEAMLEAD. ⟵ read this one carefully.
--    recruitment:manage was created at go-live (V486) as the recruiter
--    tier proper — "the key that keeps team leads OUT of the candidate
--    database's management surface" (its own catalogue comment, V514's
--    WHY-NOT-REUSE) — yet prod granted it to TEAMLEAD anyway, discovered
--    2026-08-23. That grant is why team leads see candidate-email,
--    AI-regenerate, record-check and dossier-start controls that all 403:
--    every one of those UI gates reads can('recruitment:manage') and
--    trusted it to mean "recruiter". No BFF route requires the permission
--    (all recruitment routes gate on ROLE arrays or other keys), and no
--    page_registry row requires it, so the ONLY effect of this revocation
--    is that recruiter-only controls stop rendering for the 20 team
--    leads — controls whose requests were refused anyway. Everything a
--    team lead is meant to have arrives through role arrays + the widened
--    backend rules, not through this key. The frontend deployed with this
--    change re-keys the controls team leads DO keep (bulk-tag, tag edit,
--    profile edit, AI-brief card) onto recruitment:write / recruitment:intake.
--    Rollback = re-grant (revoked_at tombstone, nothing deleted).
--
-- 3. page_registry: recruitment-triage's roles fallback gains TEAMLEAD
--    (its required_permission is already recruitment:triage from V525 —
--    the fallback only fires when /api/me/permissions errors), and the
--    four pages an ASSISTANT_TEAMLEAD may enter gain the role in their
--    role lists (their required_permission is NULL, so the roles path is
--    LIVE for them, not a fallback — without this every assistant is
--    bounced to /dashboard by RouteAccessGuard). required_roles is a
--    plain varchar with no FK, so naming the role before the console
--    creates it is safe — it matches nobody until assignments exist.
--
-- WHAT THIS DELIBERATELY DOES NOT DO
--   * No role_definition row for ASSISTANT_TEAMLEAD — the console creates
--    it (§5.1, RoleManagementTab), same as RECRUITMENT was (2026-08-10).
--   * No role_permission rows for ASSISTANT_TEAMLEAD — granted in the
--     console after creation. NOTE the corrected grant list:
--     recruitment:read + recruitment:write ONLY. NOT recruitment:manage
--     (recruiter tier proper — would re-create for assistants the exact
--     dead-control defect statement 2 removes for team leads) and NOT
--     recruitment:intake (decision 10: assistants do not create
--     candidates; the backend enforces this on the role as well, belt and
--     braces, in RecruitmentVisibility.canCreateCandidate).
--   * No touch of TEAMLEAD's recruitment:read/write/intake — unchanged.
--
-- STAGING CAVEAT — same shape as V514/V525: `permission`/`role_permission`
--   survive the nightly prod→staging refresh (V500 exclusion list);
--   `page_registry` does NOT, so statement group 3 is re-overwritten with
--   prod state nightly on staging until this migration has run in PROD.
--   Harmless: the fallback lists then simply lag. Fix forward only.
--
-- Idempotency: INSERT ... ON DUPLICATE KEY UPDATE / guarded UPDATEs; safe
--   to re-run by hand in any environment.
--
-- Author: Claude Code
-- Date:   2026-08-23
-- Rollback:
--   UPDATE role_permission SET revoked_at = NOW(), modified_by = 'V526-rollback'
--    WHERE role = 'TEAMLEAD' AND permission_key = 'recruitment:triage';
--   UPDATE role_permission SET revoked_at = NULL, updated_at = NOW(), modified_by = 'V526-rollback'
--    WHERE role = 'TEAMLEAD' AND permission_key = 'recruitment:manage';
--   UPDATE page_registry SET required_roles = 'ADMIN,HR,RECRUITMENT', modified_by = 'V526-rollback'
--    WHERE page_key = 'recruitment-triage';
--   UPDATE page_registry SET required_roles = 'ADMIN,HR,RECRUITMENT,TEAMLEAD', modified_by = 'V526-rollback'
--    WHERE page_key IN ('recruitment', 'recruitment-pipeline', 'recruitment-positions', 'recruitment-candidates');
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. TEAMLEAD works the Inbox (decisions 12/13)
-- -------------------------------------------------------------------
INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES
    ('TEAMLEAD', 'recruitment:triage', 'ALL', NOW(), 'V526')
ON DUPLICATE KEY UPDATE
    data_scope  = VALUES(data_scope),
    revoked_at  = NULL,
    updated_at  = NOW(),
    modified_by = 'V526';

-- -------------------------------------------------------------------
-- 2. recruitment:manage returns to meaning "recruiter tier"
--    (tombstone, never a delete — the rollback re-grants)
-- -------------------------------------------------------------------
UPDATE role_permission
   SET revoked_at  = NOW(),
       modified_by = 'V526'
 WHERE role = 'TEAMLEAD'
   AND permission_key = 'recruitment:manage'
   AND revoked_at IS NULL;

-- -------------------------------------------------------------------
-- 3. Page registry — TEAMLEAD fallback on the Inbox; the assistant's
--    four pages (roles path is LIVE here: required_permission is NULL)
-- -------------------------------------------------------------------
UPDATE page_registry
   SET required_roles = 'ADMIN,HR,RECRUITMENT,TEAMLEAD',
       modified_by    = 'V526'
 WHERE page_key = 'recruitment-triage';

UPDATE page_registry
   SET required_roles = 'ADMIN,HR,RECRUITMENT,TEAMLEAD,ASSISTANT_TEAMLEAD',
       modified_by    = 'V526'
 WHERE page_key IN ('recruitment', 'recruitment-pipeline',
                    'recruitment-positions', 'recruitment-candidates');
