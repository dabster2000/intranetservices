-- ====================================================================
-- V349: Register the settings-ai-validation tab in page_registry.
--
-- Adds 1 entry under section='SETTINGS' for the AI Validation admin tab
-- (display_order=100, required_roles='ADMIN', icon_name='Sparkles').
--
-- Idempotent (INSERT ... ON DUPLICATE KEY UPDATE).
-- ====================================================================

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-ai-validation', 'AI Validation', 1, '/settings?tab=ai-validation', 'ADMIN', 100, 'SETTINGS', 'Sparkles', 0, NULL)
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
