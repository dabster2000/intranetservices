-- ============================================================================
-- V252: Register Bug Reports pages in navigation registry
-- ============================================================================
-- Purpose: Registers the Bug Reports user page and Admin Bug Reports page
--          in page_migration so RouteAccessGuard enforces role-based access.
--          - /bug-reports: accessible to all authenticated users (USER role)
--          - /admin/bug-reports: restricted to ADMIN role only
--          Prefix matching in RouteAccessGuard means these registrations
--          also cover sub-routes like /bug-reports/[uuid] and
--          /admin/bug-reports/[uuid].
--
-- Section:        ADMIN (for admin page), none for user page (hidden from nav)
-- required_roles: USER (bug-reports), ADMIN (admin/bug-reports)
--
-- Rollback: DELETE FROM page_migration WHERE page_key IN ('bug-reports', 'admin-bug-reports');
--
-- Author: Claude Code
-- Date: 2026-03-18
-- ============================================================================

-- User bug reports page (accessible to all authenticated users, hidden from nav)
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
    'bug-reports',
    'Bug Reports',
    TRUE,
    '/bug-reports',
    '',
    NULL,
    'USER',
    999,
    NULL,
    'Bug',
    NOW()
);

-- Admin bug reports page (restricted to ADMIN role)
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
    'admin-bug-reports',
    'Bug Reports Admin',
    TRUE,
    '/admin/bug-reports',
    '',
    NULL,
    'ADMIN',
    850,
    'ADMIN',
    'Bug',
    NOW()
);
