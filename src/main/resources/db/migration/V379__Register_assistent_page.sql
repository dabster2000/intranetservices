-- ====================================================================
-- V379: Register the /assistant page in page_registry (top-level nav).
-- Visible to all users (required_roles='USER'); lucide icon 'Bot'.
-- Idempotent (INSERT ... ON DUPLICATE KEY UPDATE).
-- ====================================================================

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('assistant', 'Assistent', 1, '/assistant', 'USER', 25, NULL, 'Bot', 0, NULL)
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
