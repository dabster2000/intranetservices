-- V464: Seed the permission catalogue (authorization-model-unification Phase 4, task 4.5)
--
-- GENERATED FILE — do not edit by hand.
-- Generated from dk.trustworks.intranet.security.Permissions by PermissionSeedSql;
-- PermissionSeedMigrationTest (fast tier, deploy-gating) fails if this file and the
-- catalogue diverge. Regenerate with:
--   ./mvnw -q compile && java -cp target/classes dk.trustworks.intranet.security.PermissionSeedSql
--
-- Idempotent and non-resurrecting (F-13): ON DUPLICATE KEY UPDATE refreshes display
-- metadata only and never touches state, revoked_at, origin or enforce_acting_user.

INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('accounting:read', 'Accounting — read', NULL, 'Accounting', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('accounting:write', 'Accounting — write', NULL, 'Accounting', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('admin:*', 'Admin — wildcard (all permissions)', NULL, 'Admin', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('admin:read', 'Admin — read', NULL, 'Admin', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('admin:write', 'Admin — write', NULL, 'Admin', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('agreements:read', 'Agreements — read', NULL, 'Agreements', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('agreements:write', 'Agreements — write', NULL, 'Agreements', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('availability:read', 'Availability — read', NULL, 'Revenue & utilization', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('bonus:read', 'Bonus — read', NULL, 'Bonuses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('bonus:write', 'Bonus — write', NULL, 'Bonuses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('budgets:read', 'Budgets — read', NULL, 'Budgets', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('budgets:write', 'Budgets — write', NULL, 'Budgets', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('bugreports:admin', 'Bug reports — admin', NULL, 'Bug reports', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('bugreports:read', 'Bug reports — read', NULL, 'Bug reports', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('bugreports:write', 'Bug reports — write', NULL, 'Bug reports', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('capacity:read', 'Capacity — read', NULL, 'Capacity', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('careerlevel:read', 'Career level — read', NULL, 'Career level', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('careerlevel:write', 'Career level — write', NULL, 'Career level', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('companies:read', 'Companies — read', NULL, 'Companies', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('competence:approve', 'Competence — approve', NULL, 'Competence', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('competence:read', 'Competence — read', NULL, 'Competence', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('competence:write', 'Competence — write', NULL, 'Competence', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('conference:read', 'Conference — read', NULL, 'Conference', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('conference:write', 'Conference — write', NULL, 'Conference', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('consultant:read', 'Consultant — read', NULL, 'Consultant', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('consultant:write', 'Consultant — write', NULL, 'Consultant', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('contracts:read', 'Contracts — read', NULL, 'Contracts', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('contracts:write', 'Contracts — write', NULL, 'Contracts', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('crm:read', 'CRM — read', NULL, 'CRM', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('crm:write', 'CRM — write', NULL, 'CRM', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('dashboard:read', 'Dashboard — read', NULL, 'Dashboard', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('dashboard:write', 'Dashboard — write', NULL, 'Dashboard', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('devices:read', 'Devices — read', NULL, 'Devices', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('devices:write', 'Devices — write', NULL, 'Devices', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('documents:gdpr', 'Documents — GDPR', NULL, 'Documents', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('documents:read', 'Documents — read', NULL, 'Documents', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('documents:write', 'Documents — write', NULL, 'Documents', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('dststatistics:read', 'DST statistics — read', NULL, 'DST statistics', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('dststatistics:write', 'DST statistics — write', NULL, 'DST statistics', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('expenses:read', 'Expenses — read', NULL, 'Expenses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('expenses:review', 'Expenses — review', NULL, 'Expenses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('expenses:write', 'Expenses — write', NULL, 'Expenses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('guest:read', 'Guest — read', NULL, 'Guest', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('guest:write', 'Guest — write', NULL, 'Guest', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('invoices:read', 'Invoices — read', NULL, 'Invoicing', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('invoices:write', 'Invoices — write', NULL, 'Invoicing', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('knowledge:read', 'Knowledge — read', NULL, 'Knowledge', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('knowledge:write', 'Knowledge — write', NULL, 'Knowledge', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('news:read', 'News — read', NULL, 'News', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('news:write', 'News — write', NULL, 'News', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('notifications:write', 'Notifications — write', NULL, 'Notifications', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('partnerbonus:read', 'Partner bonus — read', NULL, 'Bonuses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('partnerbonus:write', 'Partner bonus — write', NULL, 'Bonuses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('practices:read', 'Practices — read', NULL, 'Practices', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('practices:write', 'Practices — write', NULL, 'Practices', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('public:read', 'Public API — read', NULL, 'Public', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('questionnaires:read', 'Questionnaires — read', NULL, 'Questionnaires', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('questionnaires:write', 'Questionnaires — write', NULL, 'Questionnaires', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:admin', 'Recruitment — admin', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:comp', 'Recruitment — compensation', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:gdpr', 'Recruitment — GDPR', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:intake', 'Recruitment — candidate intake', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:interview', 'Recruitment — interview', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:manage', 'Recruitment — manage candidates', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:read', 'Recruitment — read', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:refer', 'Recruitment — refer', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:settings', 'Recruitment — settings', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:triage', 'Recruitment — inbox triage', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('recruitment:write', 'Recruitment — write', NULL, 'Recruitment', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('revenue:read', 'Revenue — read', NULL, 'Revenue & utilization', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('salaries:read', 'Salaries — read', NULL, 'Users & HR', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('salaries:write', 'Salaries — write', NULL, 'Users & HR', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('signing:read', 'Signing — read', NULL, 'Signing', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('signing:write', 'Signing — write', NULL, 'Signing', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('system:read', 'System — read', NULL, 'System', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('system:write', 'System — write', NULL, 'System', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('taskboard:read', 'Taskboard — read', NULL, 'Taskboard', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('taskboard:write', 'Taskboard — write', NULL, 'Taskboard', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('teamleadbonus:read', 'Team lead bonus — read', NULL, 'Bonuses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('teamleadbonus:write', 'Team lead bonus — write', NULL, 'Bonuses', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('teams:read', 'Teams — read', NULL, 'Teams', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('teams:write', 'Teams — write', NULL, 'Teams', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('timeregistration:admin', 'Time registration — admin', NULL, 'Time registration', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('timeregistration:read', 'Time registration — read', NULL, 'Time registration', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('timeregistration:write', 'Time registration — write', NULL, 'Time registration', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('transportation:read', 'Transportation — read', NULL, 'Transportation', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('transportation:write', 'Transportation — write', NULL, 'Transportation', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('users:read', 'Users — read', NULL, 'Users & HR', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('users:write', 'Users — write', NULL, 'Users & HR', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('userstatus:read', 'User status — read', NULL, 'Users & HR', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('userstatus:write', 'User status — write', NULL, 'Users & HR', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('utilization:read', 'Utilization — read', NULL, 'Revenue & utilization', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('vacation:read', 'Vacation — read', NULL, 'Vacation', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
  VALUES ('vacation:write', 'Vacation — write', NULL, 'Vacation', 'CODE', 'ACTIVE')
  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);
