-- ====================================================================
-- V374: Register the settings-auto-fix tab in page_registry.
--
-- Adds 1 entry under section='SETTINGS' for the Auto-Fix Worker admin tab
-- (display_order=110, required_roles='ADMIN', icon_name='Wrench'). The
-- frontend maps page_key 'settings-auto-fix' → AutoFixSettingsTab and the
-- 'Wrench' icon → lucide Wrench.
--
-- Idempotent (INSERT ... ON DUPLICATE KEY UPDATE).
-- ====================================================================

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-auto-fix', 'Auto-Fix', 1, '/settings?tab=auto-fix', 'ADMIN', 110, 'SETTINGS', 'Wrench', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label     = VALUES(page_label),
    is_visible     = VALUES(is_visible),
    react_route    = VALUES(react_route),
    required_roles = VALUES(required_roles),
    display_order  = VALUES(display_order),
    section        = VALUES(section),
    icon_name      = VALUES(icon_name),
    is_external    = VALUES(is_external),
    external_url   = VALUES(external_url);
