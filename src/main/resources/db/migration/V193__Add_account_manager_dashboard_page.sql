-- ============================================================================
-- V193: Add Account Manager Dashboard page to navigation registry
-- ============================================================================
-- Purpose: Registers the Account Manager Dashboard React page in page_migration
--          so it appears in the CRM section of the navigation menu.
--
-- The page is already implemented in React at /sales/account-manager.
-- It is placed after the existing CRM pages (highest display_order = 207).
--
-- Section:        CRM
-- display_order:  208  (next available after device-management at 207)
-- required_roles: SALES (same as sales-leads page)
-- icon_name:      HeroBriefcase (Heroicons outline, used for manager-type pages)
--
-- Author: Claude Code
-- Date: 2026-02-22
-- ============================================================================

INSERT INTO page_migration (
    page_key,
    page_label,
    is_migrated,
    react_route,
    vaadin_route,
    vaadin_view_class,
    required_roles,
    display_order,
    section,
    icon_name,
    migrated_at
) VALUES (
    'account-manager-dashboard',
    'Account Manager',
    TRUE,
    '/sales/account-manager',
    '',
    NULL,
    'ADMIN',
    208,
    'CRM',
    'HeroBriefcase',
    NOW()
);
