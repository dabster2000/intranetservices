-- ============================================================================
-- V243: Add Sales Pipeline Overview page to navigation registry
-- ============================================================================
-- Purpose: Registers the Sales Pipeline Overview React page in page_migration
--          so it appears in the SALES section of the navigation menu.
--          This is a read-only overview page showing active leads for all
--          employees (USER role). No new tables or schema changes.
--
-- Section:        SALES
-- display_order:  210
-- required_roles: USER
-- icon_name:      Eye (maps to HeroEye in frontend)
--
-- Rollback: DELETE FROM page_migration WHERE page_key = 'sales-overview';
--
-- Impact: No Quarkus entity changes. Frontend reads page_migration via
--         the navigation BFF endpoint.
--
-- Author: Claude Code
-- Date: 2026-03-11
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
    'sales-overview',
    'Sales Pipeline',
    TRUE,
    '/sales-overview',
    '',
    NULL,
    'USER',
    210,
    'SALES',
    'Eye',
    NOW()
);
