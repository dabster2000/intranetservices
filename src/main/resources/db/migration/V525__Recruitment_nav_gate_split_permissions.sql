-- ===================================================================
-- V525: Recruitment nav-gate split — recruitment:triage + recruitment:settings
-- ===================================================================
-- Feature: docs/access/recruitment-access-model-target.md §5.2, work order §6
--          step 1 (ships alone, ahead of the wider access-model change).
-- Domain:  recruitmentservice + platform authorization (permissions/grants)
--
-- WHAT THE KEYS BUY
--   recruitment:triage   — seeing and opening the Inbox (/recruitment/triage):
--                          the pending-referral queue and the unsolicited-
--                          applicant queue.
--   recruitment:settings — seeing and opening /recruitment/settings: candidate
--                          email sender + templates, AI tone of voice, record-
--                          check settings (meeting rooms stay admin-gated
--                          inside the page).
--   Both keys gate PAGE/NAV VISIBILITY in the frontend (RecruitmentNav, the
--   two page clients, RouteAccessGuard via page_registry below). The BFF
--   routes behind the pages keep their own role arrays — those remain the
--   enforcement point; these keys exist so the UI never renders a surface
--   whose every request 403s.
--
-- WHY NOT REUSE AN EXISTING KEY
--   Both tabs hung off recruitment:write, which every team lead holds — so
--   all 20 team leads saw an Inbox tab and a Settings tab where every call
--   returns 403 (access-gaps finding 01/02, 2026-08-23), and "Inbox yes,
--   Settings no" (decisions 12/13 vs the recruiter-only Settings surface)
--   was inexpressible. recruitment:manage cannot express it either: team
--   leads hold it too, and the target model later diverges the two surfaces
--   (Inbox opens to TEAMLEAD, Settings does not). Hence one key per surface.
--
-- WHY data_scope = 'ALL'
--   DbAuthzStore.loadEffectivePermissions filters on data_scope = 'ALL'
--   (the Phase 8 boolean projection): any other scope is invisible to both
--   the BFF's requirePermission() and the UI's can(), so the tab would
--   never render. Same reasoning as V514.
--
-- WHY A NEW MIGRATION AND NOT AN EDIT TO V464
--   V464 (the generated catalogue seed) has been regenerated to include both
--   keys so the seed-drift gate passes, but V464 has already run in every
--   environment — repair-at-start realigns its checksum WITHOUT re-running
--   it, so the regeneration inserts nothing anywhere. The permission rows
--   must be inserted here, and before the grants below:
--   role_permission.permission_key is an FK onto permission.permission_key
--   (fk_role_permission_permission, V462).
--
-- GRANTS — TEAMLEAD IS DELIBERATELY ABSENT
--   ADMIN, HR and RECRUITMENT get both keys: that is exactly the set of
--   roles whose Inbox/Settings requests the BFF admits today, so nothing
--   opens and nothing closes for them — the two tabs simply stop rendering
--   for everyone else. TEAMLEAD gets recruitment:triage ONLY when work-order
--   step 4 widens the Inbox BFF routes (triage-queue, referrals/pending,
--   referrals/{uuid}/triage, pool, unpool) to TEAMLEAD — granting it now
--   would rebuild the exact defect this migration removes (a visible Inbox
--   where every button 403s). TEAMLEAD never gets recruitment:settings
--   (target table: Settings = ADMIN/HR/RECRUITMENT only).
--
-- PAGE_REGISTRY
--   The two rows switch RouteAccessGuard from the legacy roles fallback to
--   the permission gate, so page access and nav visibility can never
--   diverge. required_roles stays populated as the dual-read fallback (used
--   only when /api/me/permissions errors) and is aligned to the same
--   audience. Incidentally this heals a live lockout: the
--   recruitment-settings row read 'ADMIN,RECRUITMENT', so the three HR
--   holders without ADMIN/RECRUITMENT (kenneth.toft, marie.myssing,
--   thomas.buchholdt) were bounced off a Settings page whose every section
--   they are entitled to manage.
--
-- STAGING CAVEAT — same shape as V514
--   * `permission` and `role_permission` ARE on the exclusion list of
--     sp_sync_prod_to_staging() (V500), so those rows SURVIVE the nightly
--     refresh once this migration has run on staging.
--   * `page_registry` is NOT excluded: the UPDATEs below are overwritten
--     with prod state every night on staging until this migration has run
--     in PROD. Until then staging falls back to the roles path — same
--     behaviour as today, no harm. Fix forward only; Flyway will never
--     re-run this file (flyway_schema_history is excluded from the sync).
--
-- Idempotency: INSERT ... ON DUPLICATE KEY UPDATE / plain UPDATEs
--   throughout; safe to re-run by hand in any environment.
--
-- Author: Claude Code
-- Date:   2026-08-23
-- Rollback:
--   UPDATE role_permission SET revoked_at = NOW(), modified_by = 'V525-rollback'
--    WHERE permission_key IN ('recruitment:triage', 'recruitment:settings');
--   UPDATE page_registry SET required_permission = NULL, modified_by = 'V525-rollback'
--    WHERE page_key IN ('recruitment-triage', 'recruitment-settings');
--   (The permission rows are left in place — revocation is a tombstone in
--    this schema, never a delete; the FKs are ON DELETE RESTRICT. Note the
--    settings row's pre-V525 required_roles was 'ADMIN,RECRUITMENT'.)
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. The permission rows (FK targets of role_permission.permission_key)
--
--    Metadata only on conflict, matching the generated seed's contract:
--    never touch state, revoked_at, origin or enforce_acting_user, so a
--    key marked STALE or revoked stays that way across a re-deploy.
-- -------------------------------------------------------------------
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES
    ('recruitment:triage',   'Recruitment — inbox triage', NULL, 'Recruitment', 'CODE', 'ACTIVE'),
    ('recruitment:settings', 'Recruitment — settings',     NULL, 'Recruitment', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

-- -------------------------------------------------------------------
-- 2. Grants — the roles the BFF already admits, and nobody else
-- -------------------------------------------------------------------
INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES
    ('ADMIN',       'recruitment:triage',   'ALL', NOW(), 'V525'),
    ('HR',          'recruitment:triage',   'ALL', NOW(), 'V525'),
    ('RECRUITMENT', 'recruitment:triage',   'ALL', NOW(), 'V525'),
    ('ADMIN',       'recruitment:settings', 'ALL', NOW(), 'V525'),
    ('HR',          'recruitment:settings', 'ALL', NOW(), 'V525'),
    ('RECRUITMENT', 'recruitment:settings', 'ALL', NOW(), 'V525')
ON DUPLICATE KEY UPDATE
    data_scope  = VALUES(data_scope),
    revoked_at  = NULL,
    updated_at  = NOW(),
    modified_by = 'V525';

-- -------------------------------------------------------------------
-- 3. Page registry — RouteAccessGuard switches to the permission gate;
--    required_roles stays as the aligned dual-read fallback
-- -------------------------------------------------------------------
UPDATE page_registry
   SET required_permission = 'recruitment:triage',
       required_roles      = 'ADMIN,HR,RECRUITMENT',
       modified_by         = 'V525'
 WHERE page_key = 'recruitment-triage';

UPDATE page_registry
   SET required_permission = 'recruitment:settings',
       required_roles      = 'ADMIN,HR,RECRUITMENT',
       modified_by         = 'V525'
 WHERE page_key = 'recruitment-settings';
