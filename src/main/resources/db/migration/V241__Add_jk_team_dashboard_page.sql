-- ============================================================================
-- V241: Add JK Team Dashboard page to navigation registry
-- ============================================================================
-- Purpose: Registers the JK Team Dashboard React page in page_migration
--          so it appears in the MANAGEMENT section of the navigation menu.
--
-- Section:        MANAGEMENT
-- display_order:  410
-- required_roles: ADMIN
-- icon_name:      GraduationCap (maps to HeroAcademicCap in frontend)
--
-- Author: Claude Code
-- Date: 2026-03-10
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
    'jk-team-dashboard',
    'JK Team Dashboard',
    TRUE,
    '/management/jk-team-dashboard',
    '',
    NULL,
    'ADMIN',
    410,
    'MANAGEMENT',
    'GraduationCap',
    NOW()
);
