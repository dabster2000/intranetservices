-- ===================================================================
-- V514: Recruitment ATS — recruitment:intake (team-lead candidate intake)
-- ===================================================================
-- Feature: team leads may create a candidate and attach one to a position
-- Domain:  recruitmentservice + platform authorization (permissions/grants)
--
-- WHAT THE KEY BUYS
--   recruitment:intake is the create-and-attach slice of the candidate
--   database, and NOTHING else. Concretely it covers:
--       * POST /recruitment/candidates            (create a candidate)
--       * POST /recruitment/candidates/dedupe-check
--       * POST /recruitment/candidates/{uuid}/applications (attach)
--   It deliberately does NOT cover: editing a candidate, deleting one,
--   pool/unpool, bulk tagging, candidate e-mail, e-mail templates, the
--   offer/contract dossier, or record-check outcomes. Those stay on
--   recruitment:manage (ADMIN/HR/RECRUITMENT) and recruitment:admin.
--
-- WHY NOT REUSE AN EXISTING KEY
--   recruitment:write is held by every team lead already — it is what lets
--   them move a candidate through the stages on a position they own — so it
--   cannot express "may put a new name into the funnel"; granting intake on
--   it would be a no-op that changes nothing.
--   recruitment:manage is the whole recruiter tier and is far too wide: it
--   is the key the go-live (V486) created precisely to keep team leads OUT
--   of the candidate database's management surface. Widening it here would
--   undo that decision. Hence a third, narrower key.
--
-- WHY data_scope = 'ALL'
--   DbAuthzStore.loadEffectivePermissions filters on data_scope = 'ALL'
--   (the Phase 8 boolean projection): a TEAM- or PRACTICE-scoped grant is
--   invisible to both the BFF's requirePermission() and the UI's can(), so
--   the intake button would never render and the route would 403. The
--   narrowing that matters is per-position and lives in code
--   (RecruitmentVisibility.canDecideOnApplication / canReadPosition), not
--   in the data scope.
--
-- WHY A NEW MIGRATION AND NOT AN EDIT TO V464
--   V464 (the generated catalogue seed) has been regenerated to include
--   this key so the seed-drift gate passes, but V464 has already run in
--   every environment — repair-at-start realigns its checksum WITHOUT
--   re-running it, so the regeneration inserts nothing anywhere. The
--   permission row therefore has to be inserted here, and before the
--   grants below: role_permission.permission_key is an FK onto
--   permission.permission_key (fk_role_permission_permission, V462).
--
-- ROLE_DEFINITION
--   No role_definition seeding is needed: ADMIN, TEAMLEAD and HR are
--   seeded by V245 and RECRUITMENT by V486, both of which are ordered
--   before this file, so role_permission.role (FK onto
--   role_definition.name) always resolves.
--
-- STAGING CAVEAT — read this before assuming a grant is present
--   Two different things happen to authz data on the nightly
--   prod -> staging refresh, and they point in opposite directions:
--     * `permission` and `role_permission` ARE on the exclusion list of
--       sp_sync_prod_to_staging() (see V500), so the rows written by this
--       migration SURVIVE a refresh. Staging keeps its own authz state.
--     * `roles` (the user -> role assignments) and `page_registry` are NOT
--       excluded, so they are overwritten with prod state every night. A
--       team lead who only holds TEAMLEAD on staging loses it at the next
--       refresh, and the grant below then resolves to nobody.
--   If a grant here ever does go missing, re-applying this migration is
--   NOT an available remedy: flyway_schema_history is itself excluded from
--   the refresh, so Flyway still records V514 as applied and will never
--   re-run it. Recovery is a manual re-INSERT of the statements below, or
--   a promotion. Fix forward only.
--
-- Idempotency: INSERT ... ON DUPLICATE KEY UPDATE throughout; safe to
--   re-run by hand in any environment.
--
-- Author: Claude Code
-- Date:   2026-08-18
-- Rollback:
--   UPDATE role_permission SET revoked_at = NOW(), modified_by = 'V514-rollback'
--    WHERE permission_key = 'recruitment:intake';
--   (The permission row itself is left in place — revocation is a
--    tombstone in this schema, never a delete; the FKs are ON DELETE
--    RESTRICT.)
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. The permission row (FK target of role_permission.permission_key)
--
--    Metadata only on conflict, matching the generated seed's contract:
--    never touch state, revoked_at, origin or enforce_acting_user, so a
--    key marked STALE or revoked stays that way across a re-deploy.
-- -------------------------------------------------------------------
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('recruitment:intake', 'Recruitment — candidate intake', NULL, 'Recruitment', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

-- -------------------------------------------------------------------
-- 2. Grants
--
--    TEAMLEAD is the point of the change. ADMIN, HR and RECRUITMENT are
--    granted it as well so the permission alone is a truthful answer to
--    "may this person create a candidate" — the backend's
--    RecruitmentVisibility.canCreateCandidate keeps isRecruiterTier in the
--    OR as a belt-and-braces path, but nothing else should have to know
--    that the recruiter tier is a special case.
-- -------------------------------------------------------------------
INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES
    ('TEAMLEAD',    'recruitment:intake', 'ALL', NOW(), 'V514'),
    ('ADMIN',       'recruitment:intake', 'ALL', NOW(), 'V514'),
    ('HR',          'recruitment:intake', 'ALL', NOW(), 'V514'),
    ('RECRUITMENT', 'recruitment:intake', 'ALL', NOW(), 'V514')
ON DUPLICATE KEY UPDATE
    data_scope  = VALUES(data_scope),
    revoked_at  = NULL,
    updated_at  = NOW(),
    modified_by = 'V514';
