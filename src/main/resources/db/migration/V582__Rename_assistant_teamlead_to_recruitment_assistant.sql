-- ===================================================================
-- V582: ASSISTANT_TEAMLEAD -> RECRUITMENT_ASSISTANT
-- ===================================================================
-- Feature: docs/superpowers/specs/2026-09-08-recruitment-assistant-position-scoping.md §8 (D12)
-- Domain:  platform authorization
--
-- ⚠ DEPLOY ORDER: this migration rides the SAME deploy as the backend
--   rename of the ROLE_ASSISTANT_TEAMLEAD constant AND the frontend
--   rename in roleTiers.ts. The role name is shared state across all
--   three: a frontend still sending ASSISTANT_TEAMLEAD against a renamed
--   backend, or the reverse, locks the holder out of the module entirely.
--   Backend and frontend ship together.
--
-- WHY THE ORDER BELOW IS NOT NEGOTIABLE
--   Two FKs point at role_definition.name:
--     fk_roles_role_definition        roles.role           -> role_definition.name
--     fk_role_permission_role         role_permission.role -> role_definition.name
--   So the new definition row must EXIST before anything is repointed at
--   it, and the old one may only be dropped once nothing references it.
--   All of it in ONE migration so no window exists where the holder
--   carries a role name nothing recognises.
--
--   page_registry.required_roles is a plain CSV varchar with no FK, so it
--   is a string replace and its ordering does not matter — but it must
--   happen, or RouteAccessGuard bounces every assistant to /dashboard and
--   the admin Pages tab renders a raw name instead of a label.
--
-- WHY THE LABEL CHANGES TOO
--   The console has read "Assistant Teamlead" since 2026-08-23 while the
--   spec called for "Recruitment assistant"; the rename was deferred and
--   then a user was assigned anyway. Both halves land here.
--
-- STAGING CAVEAT — same shape as V514/V525/V526: `role_definition`,
--   `roles` and `role_permission` survive the nightly prod->staging
--   refresh (V500 exclusion list); `page_registry` does NOT, so statement
--   group 4 is re-overwritten with prod state nightly on staging until
--   this migration has run in PROD. Harmless: the role lists then lag.
--   Fix forward only.
--
-- Idempotency: every statement is guarded on the old/new name, so a
--   re-run after success is a no-op.
--
-- Author: Claude Code
-- Date:   2026-09-08
-- Rollback: the reverse rename, same order —
--   INSERT IGNORE INTO role_definition (name, display_label, is_system, created_at, updated_at)
--        SELECT 'ASSISTANT_TEAMLEAD', 'Assistant Teamlead', is_system, created_at, NOW()
--          FROM role_definition WHERE name = 'RECRUITMENT_ASSISTANT';
--   UPDATE roles           SET role = 'ASSISTANT_TEAMLEAD' WHERE role = 'RECRUITMENT_ASSISTANT';
--   UPDATE role_permission SET role = 'ASSISTANT_TEAMLEAD' WHERE role = 'RECRUITMENT_ASSISTANT';
--   UPDATE page_registry SET required_roles = REPLACE(required_roles,'RECRUITMENT_ASSISTANT','ASSISTANT_TEAMLEAD')
--    WHERE required_roles LIKE '%RECRUITMENT_ASSISTANT%';
--   DELETE FROM role_definition WHERE name = 'RECRUITMENT_ASSISTANT';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. The new definition row must exist before anything points at it.
--    Carries over is_system and the original created_at so the console's
--    "created" column does not reset to today.
-- -------------------------------------------------------------------
INSERT IGNORE INTO role_definition (name, display_label, is_system, created_at, updated_at)
SELECT 'RECRUITMENT_ASSISTANT', 'Recruitment assistant', is_system, created_at, NOW()
  FROM role_definition
 WHERE name = 'ASSISTANT_TEAMLEAD';

-- -------------------------------------------------------------------
-- 2. Repoint the assignments (1 row in prod: Thomas Gadeberg)
-- -------------------------------------------------------------------
UPDATE roles
   SET role        = 'RECRUITMENT_ASSISTANT',
       updated_at  = NOW(),
       modified_by = 'V582'
 WHERE role = 'ASSISTANT_TEAMLEAD';

-- -------------------------------------------------------------------
-- 3. Repoint the grants (2 rows: recruitment:read, recruitment:write)
-- -------------------------------------------------------------------
UPDATE role_permission
   SET role        = 'RECRUITMENT_ASSISTANT',
       updated_at  = NOW(),
       modified_by = 'V582'
 WHERE role = 'ASSISTANT_TEAMLEAD';

-- -------------------------------------------------------------------
-- 4. Page registry — CSV string replace (4 rows: recruitment,
--    recruitment-pipeline, recruitment-positions, recruitment-candidates)
-- -------------------------------------------------------------------
UPDATE page_registry
   SET required_roles = REPLACE(required_roles, 'ASSISTANT_TEAMLEAD', 'RECRUITMENT_ASSISTANT'),
       modified_by    = 'V582'
 WHERE required_roles LIKE '%ASSISTANT_TEAMLEAD%';

-- -------------------------------------------------------------------
-- 5. Nothing references the old name any more — drop it.
-- -------------------------------------------------------------------
DELETE FROM role_definition
 WHERE name = 'ASSISTANT_TEAMLEAD'
   AND NOT EXISTS (SELECT 1 FROM (SELECT 1) d WHERE EXISTS
        (SELECT 1 FROM roles WHERE role = 'ASSISTANT_TEAMLEAD'))
   AND NOT EXISTS (SELECT 1 FROM (SELECT 1) d WHERE EXISTS
        (SELECT 1 FROM role_permission WHERE role = 'ASSISTANT_TEAMLEAD'));
