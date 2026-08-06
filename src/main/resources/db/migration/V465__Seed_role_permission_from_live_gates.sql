-- V465: Seed role_permission from live gate values (authorization-model-unification Phase 4, task 4.6)
--
-- GENERATED from the Phase 3 access manifest (trustworks-intranet-v2
-- src/access/access-manifest.json) joined to the Quarkus endpoint scopes: each
-- role-gated BFF handler was traced to the backend endpoint(s) it calls, and each
-- (role, permission) pair implied was emitted. Where near-identical role sets
-- existed for the same permission the UNION was seeded; all 11 widenings were
-- approved by the owner on 2026-08-06. Full provenance:
-- docs/access/role-permission-derivation.json (committed with this migration).
--
-- DORMANT: nothing reads this table until Phase 5.
--
-- Every statement is INSERT..SELECT guarded on role_definition and permission:
--  * role_definition is UI-mutable (six roles incl. CXO/MANAGER/CRM_VIEWER were
--    deleted via the admin UI in production) — a hardcoded INSERT of a deleted
--    role would FK-fail and crash boot on a repair-at-start re-run (F-13).
--  * ON DUPLICATE KEY UPDATE is a no-op so a re-run never resurrects a pair
--    that was later revoked and hard-removed, and never touches revoked_at.

INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'admin:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'bonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'bonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'bonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'bonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'bonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'bonus:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'bonus:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'bonus:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'companies:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'companies:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'contracts:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'contracts:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'contracts:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'SALES' AND p.permission_key = 'contracts:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'crm:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'dashboard:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'dashboard:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'dashboard:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'documents:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'documents:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'documents:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'documents:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'expenses:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'expenses:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'invoices:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'invoices:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'invoices:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'SALES' AND p.permission_key = 'invoices:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'knowledge:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'partnerbonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'partnerbonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'partnerbonus:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'practices:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'practices:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'questionnaires:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'recruitment:admin'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'recruitment:gdpr'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'DPO' AND p.permission_key = 'recruitment:gdpr'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'recruitment:interview'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'recruitment:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'recruitment:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'recruitment:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TEAMLEAD' AND p.permission_key = 'recruitment:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'recruitment:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'recruitment:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'recruitment:refer'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'recruitment:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'recruitment:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'recruitment:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TEAMLEAD' AND p.permission_key = 'recruitment:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'recruitment:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'salaries:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'salaries:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'salaries:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'salaries:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'system:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'system:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'teams:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'teams:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'teams:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'timeregistration:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'timeregistration:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'users:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'DPO' AND p.permission_key = 'users:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'users:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'users:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'SALES' AND p.permission_key = 'users:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'users:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'users:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'users:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'users:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'userstatus:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'userstatus:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
