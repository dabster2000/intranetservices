-- V468: Seed role_permission pairs for page audiences (authorization-model-unification Phase 6)
--
-- 32 pairs across 16 permissions, ALL owner-approved 2026-08-06 (AskUserQuestion,
-- recorded in trustworks-intranet-v2 docs/access/page-registry-audit.md §10 and findings.md).
-- These permissions gate PAGE ENTRY after the Phase 6 frontend conversion; they had zero
-- seeded holders (or missed one holder) because the Phase 4 seed traced API gates, not page
-- audiences. Every pair exactly preserves today's page audience — no page becomes visible
-- to any role that could not use it before. They also pre-arm Phase 12 backend enforcement.
--
-- Same non-negotiables as V465 (F-13): INSERT..SELECT guarded on role_definition and
-- permission (role_definition is UI-mutable in production; a hardcoded INSERT of a deleted
-- role would FK-fail and crash boot on a repair-at-start re-run), and ON DUPLICATE KEY
-- UPDATE is a no-op so a re-run never resurrects a revoked-and-removed pair.
-- Every role named here was verified present in production role_definition on 2026-08-06.

INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ACCOUNTING' AND p.permission_key = 'accounting:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'accounting:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'accounting:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'admin:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'DEVOPS' AND p.permission_key = 'bugreports:admin'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'bugreports:admin'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'SALES' AND p.permission_key = 'capacity:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'capacity:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'consultant:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TEAMLEAD' AND p.permission_key = 'consultant:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'consultant:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'SALES' AND p.permission_key = 'crm:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'crm:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'crm:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'DPO' AND p.permission_key = 'devices:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'devices:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'expenses:review'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'expenses:review'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'EDITOR' AND p.permission_key = 'knowledge:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'knowledge:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'EDITOR' AND p.permission_key = 'news:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'news:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'partnerbonus:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'questionnaires:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'recruitment:comp'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'recruitment:comp'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'PARTNER' AND p.permission_key = 'recruitment:comp'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'revenue:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TECHPARTNER' AND p.permission_key = 'revenue:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'signing:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'signing:write'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
INSERT INTO role_permission (role, permission_key)
  SELECT rd.name, p.permission_key FROM role_definition rd, permission p
  WHERE rd.name = 'TEAMLEAD' AND p.permission_key = 'teams:read'
  ON DUPLICATE KEY UPDATE role_permission.role = role_permission.role;
