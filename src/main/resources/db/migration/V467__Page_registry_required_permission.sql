-- V467: page_registry.required_permission (authorization-model-unification Phase 6, task 6.7)
--
-- Additive column + backfill from the owner-signed mapping committed as
-- trustworks-intranet-v2 docs/access/page-registry-audit.md (2026-08-06).
-- `required_roles` is retained and dual-read until Phase 14 — a canary task from the
-- previous version still reads it, and NULL required_permission deliberately means
-- "fall back to required_roles" (universal USER pages and the dark TEMP ATS rows).
--
-- The phase file numbered this V462; Phase 4 took V462–V466 (findings 2026-08-06).
--
-- Non-negotiable details (F-12, F-13, plan principle P6):
--   * Explicit COLLATE utf8mb4_general_ci on the column — page_registry is DECLARED
--     utf8mb4_unicode_ci in some environments but RUNS as general_ci in production, so
--     relying on the table default makes the FK create in one environment and fail in
--     the other. VARCHAR(64) matches permission.permission_key exactly.
--   * IF NOT EXISTS everywhere — repair-at-start re-runs this file after a rollback.
--   * Backfill UPDATEs are guarded on `required_permission IS NULL` so a re-run never
--     clobbers a value later edited through the Phase 7 admin console.
--   * The two new-row INSERTs use ON DUPLICATE KEY UPDATE no-ops. Known residual: a row
--     hard-deleted via the admin UI between a deploy and a repair re-run would be
--     re-inserted; accepted for page_registry (no tombstone column) and recorded in
--     findings.

ALTER TABLE page_registry
  ADD COLUMN IF NOT EXISTS required_permission VARCHAR(64) COLLATE utf8mb4_general_ci NULL
    AFTER required_roles;

ALTER TABLE page_registry
  ADD CONSTRAINT fk_page_registry_permission
    FOREIGN KEY IF NOT EXISTS (required_permission) REFERENCES permission(permission_key)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------
-- List (b): stale rows whose routes no longer exist (owner-approved deletion).
-- ---------------------------------------------------------------------------
DELETE FROM page_registry WHERE page_key IN ('accounting-accounts', 'invoice-recovery');

-- ---------------------------------------------------------------------------
-- /settings duplicate-route fix (owner decision: entry for all, tabs gated).
-- Three rows shared react_route='/settings'; the display_order winner was
-- settings-employee-documents (ADMIN), denying non-admins the whole settings
-- page. The two non-canonical rows become sidebar/tab rows; the canonical
-- `settings` row becomes authenticated-only.
-- ---------------------------------------------------------------------------
UPDATE page_registry SET react_route = '/settings?tab=employee-documents'
  WHERE page_key = 'settings-employee-documents' AND react_route = '/settings';
UPDATE page_registry SET react_route = '/settings?tab=recruitment-ai'
  WHERE page_key = 'settings-recruitment-ai' AND react_route = '/settings';
UPDATE page_registry SET required_roles = 'USER'
  WHERE page_key = 'settings' AND required_roles = 'ADMIN';

-- ---------------------------------------------------------------------------
-- List (a): routes with no registry row (fail-open, F-17). New rows, hidden
-- from the sidebar; audiences owner-approved 2026-08-06.
-- ---------------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission, display_order, section, icon_name)
  VALUES
    ('admin-access-management', 'Access Management (Admin)', 0, '/admin/access-management', 'ADMIN',          'admin:read', 9101, NULL, NULL),
    ('admin-api-clients',       'API Clients (Admin)',       0, '/admin/api-clients',       'ADMIN',          'admin:read', 9102, NULL, NULL),
    ('dashboard-new',           'Dashboard (Preview)',       0, '/dashboard_new',           'USER',           NULL,         9103, NULL, NULL),
    ('management-team',         'Teams & Practices',         0, '/management/team',         'ADMIN,TEAMLEAD', 'teams:read', 9104, NULL, NULL)
  ON DUPLICATE KEY UPDATE page_registry.page_key = page_registry.page_key;

-- ---------------------------------------------------------------------------
-- Backfill (docs/access/page-registry-audit.md §9). Guarded on IS NULL.
-- Rows not named here stay NULL deliberately: universal USER pages, the dark
-- TEMP ATS rows, career-galaxy, the recruitment root row, external rows, and
-- the USER-audience settings tabs (general, bugreport, timesheet).
-- ---------------------------------------------------------------------------
UPDATE page_registry SET required_permission = 'accounting:read'
  WHERE page_key IN ('internal-invoice-controlling', 'invoice-controlling-public', 'invoices')
    AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'bugreports:admin'
  WHERE page_key = 'admin-bug-reports' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'devices:read'
  WHERE page_key = 'device-management' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'knowledge:write'
  WHERE page_key = 'faq-admin' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'news:write'
  WHERE page_key = 'news-admin' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'questionnaires:write'
  WHERE page_key = 'questionnaires' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'signing:write'
  WHERE page_key = 'template-management' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'partnerbonus:read'
  WHERE page_key IN ('partner-bonus-admin', 'sales-approved', 'your-part')
    AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'partnerbonus:write'
  WHERE page_key = 'sales-approval' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'crm:write'
  WHERE page_key IN ('clients', 'sales-leads', 'account-manager-dashboard')
    AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'consultant:read'
  WHERE page_key = 'team' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'revenue:read'
  WHERE page_key IN ('cxo-dashboard', 'tw-expense-dist') AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'expenses:review'
  WHERE page_key = 'expense-management' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'admin:read'
  WHERE page_key = 'framework-agreements' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'invoices:write'
  WHERE page_key = 'invoicing' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'teams:read'
  WHERE page_key = 'team-dasboard' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'salaries:read'
  WHERE page_key = 'salary-payment' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'capacity:read'
  WHERE page_key = 'staffing' AND required_permission IS NULL;
UPDATE page_registry SET required_permission = 'admin:read'
  WHERE page_key IN (
    'settings-access-management', 'settings-ai-validation', 'settings-api-clients',
    'settings-auto-fix', 'settings-economics', 'settings-it-budget', 'settings-practices',
    'settings-recruitment-dossier', 'settings-team-dashboard', 'settings-teamlead-bonus',
    'settings-teams', 'settings-employee-documents')
    AND required_permission IS NULL;

-- ---------------------------------------------------------------------------
-- Task 6.9 flag, seeded OFF (fail-open = today's behaviour). When true, the
-- frontend RouteAccessGuard denies routes with no registry row instead of
-- admitting them. Runtime-flippable from Settings without a redeploy; the
-- BFF reads it per request and fails safe to false. NOTE: the nightly
-- staging sync re-seeds app_settings, reverting this to OFF on staging —
-- the safe direction (findings 2026-08-06).
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('authorization.route-guard.fail-closed', 'false', 'authorization');
