-- V471: expense data-scope seeds (authorization-model-unification Phase 9.2)
--
-- Owner-decided rules (access-intent §2 "Finance data scope", 2026-08-06):
--   USER  → expenses:read @ OWN   (everyone sees their own expenses)
--   HR    → expenses:read @ ALL   (existing grant, column default — untouched)
--   ADMIN → expenses:read @ ALL   (NEW row — matrix bookkeeping, see below)
--   No TEAMLEAD grant: the owner declined the phase file's TEAM widening.
--
-- USER's grant existed at the implicit ALL of the V470 column default; narrowing
-- it to OWN is invisible to every boolean consumer (the Phase 8 "boolean = ALL"
-- projection: sub-ALL grants never satisfy can()/page audiences), and the reach
-- enforcement it feeds serves each employee their own ledger — exactly what the
-- BFF already pinned. The mobile expense flow resolves its actor to the device
-- owner, whose OWN reach always includes themselves.
--
-- ADMIN previously held no expenses:read row at all, yet reaches every expense
-- through expenses:review surfaces and the admin-only View-as override. Without
-- this row, a reach check keyed expenses:read would resolve an admin to OWN
-- (via their USER role) and 403 the View-as ledger — the Phase 8 /salary-payment
-- widening hazard, inverted. Recorded in findings 2026-08-06.
--
-- Non-negotiable details (findings F-12, F-13, V470 header):
--   * Idempotent — repair-at-start means a rollback re-runs this file.
--   * ON DUPLICATE KEY UPDATE touches data_scope only, never revoked_at: a seed
--     re-run cannot resurrect a console revoke (tombstone-safe).
--   * role_permission is staging-sync-excluded (V466), so staging-ahead-of-prod
--     survives the nightly refresh.

INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'OWN' FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'expenses:read'
  ON DUPLICATE KEY UPDATE data_scope = 'OWN';

INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'ALL' FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'expenses:read'
  ON DUPLICATE KEY UPDATE data_scope = 'ALL';
