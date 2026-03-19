-- ============================================================================
-- V254: Register Practice Settings admin page in navigation registry
-- ============================================================================
-- Purpose: Registers the Practice Settings React page in page_migration
--          so it appears in the ADMIN section of the navigation menu and
--          RouteAccessGuard enforces ADMIN role access.
--
-- Section:        ADMIN
-- display_order:  860 (after Bug Reports Admin at 850)
-- required_roles: ADMIN
-- icon_name:      Settings (maps to HeroIcon Cog/Settings in frontend)
--
-- Rollback: DELETE FROM page_migration WHERE page_key = 'admin-practice-settings';
--
-- Author: Claude Code
-- Date: 2026-03-19
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
    'admin-practice-settings',
    'Practice Settings',
    TRUE,
    '/admin/practice-settings',
    '',
    NULL,
    'ADMIN',
    860,
    'ADMIN',
    'Settings',
    NOW()
);
