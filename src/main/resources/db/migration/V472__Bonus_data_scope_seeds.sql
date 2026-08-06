-- V472: bonus data-scope seed (authorization-model-unification Phase 9.3)
--
-- Owner-decided rules (access-intent §2 "Finance data scope", 2026-08-06):
--   USER                              → bonus:read @ OWN  (this file)
--   PARTNER / HR / ADMIN / TECHPARTNER → bonus:read @ ALL  (existing rows at the
--                                        column default — deliberately untouched:
--                                        partners keep company-wide overviews,
--                                        Decision 8)
--   partnerbonus:* / teamleadbonus:*   → NO changes. The team-lead dashboards
--                                        gate on partnerbonus:read while their
--                                        BFF routes admit HR by role — reach
--                                        enforcement there would 403 HR, so the
--                                        whole sub-surface is deferred to Phase
--                                        10/12 (findings 2026-08-06). Seeding the
--                                        unbound teamleadbonus keys was rejected:
--                                        a grant no endpoint honours misleads the
--                                        admin console.
--
-- USER held bonus:read at the implicit ALL of the V470 default; narrowing to OWN
-- is invisible to boolean consumers (Phase 8 "boolean = ALL" projection — no UI
-- or BFF gate consumes bonus:read) and feeds the Phase 9.3 reach filtering: the
-- basis and eligibility row sets now bind the actor's reach into the WHERE
-- clause, so an employee's dashboard fetch returns their own record instead of
-- the company-wide list the BFF used to discard.
--
-- Non-negotiable details (findings F-12, F-13, V470 header): idempotent under
-- repair-at-start; ON DUPLICATE KEY UPDATE touches data_scope only, never
-- revoked_at (tombstone-safe); role_permission is staging-sync-excluded (V466).

INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'OWN' FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'bonus:read'
  ON DUPLICATE KEY UPDATE data_scope = 'OWN';
