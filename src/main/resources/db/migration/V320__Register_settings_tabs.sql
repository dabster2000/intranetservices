-- ====================================================================
-- V320: Register the /settings page tabs in page_registry.
--
-- Adds 9 entries under section='SETTINGS' so admins can configure
-- required_roles and is_visible for each settings tab through the
-- existing access-management UI.
--
-- The settings page reads page_registry filtered by section='SETTINGS'.
-- The sidebar excludes section='SETTINGS' so they don't appear there.
--
-- Idempotent (INSERT ... ON DUPLICATE KEY UPDATE).
-- ====================================================================

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-timesheet',          'Timesheet',          1, '/settings?tab=timesheet',         'USER',  10, 'SETTINGS', 'Clock',           0, NULL),
    ('settings-bugreport',          'Bug Reporting',      1, '/settings?tab=bugreport',         'USER',  20, 'SETTINGS', 'Bug',             0, NULL),
    ('settings-general',            'General',            1, '/settings?tab=general',           'USER',  30, 'SETTINGS', 'Settings',        0, NULL),
    ('settings-it-budget',          'IT Budget',          1, '/settings?tab=it-budget',         'ADMIN', 40, 'SETTINGS', 'Shield',          0, NULL),
    ('settings-team-dashboard',     'Team Dashboard',     1, '/settings?tab=team-dashboard',    'ADMIN', 50, 'SETTINGS', 'LayoutDashboard', 0, NULL),
    ('settings-recruitment-dossier','Recruitment',        1, '/settings?tab=recruitment-dossier','ADMIN', 60, 'SETTINGS', 'Briefcase',       0, NULL),
    ('settings-economics',          'Economics',          1, '/settings?tab=economics',         'ADMIN', 70, 'SETTINGS', 'Coins',           0, NULL),
    ('settings-api-clients',        'API Clients',        1, '/settings?tab=api-clients',       'ADMIN', 80, 'SETTINGS', 'KeyRound',        0, NULL),
    ('settings-access-management',  'Access Management',  1, '/settings?tab=access-management', 'ADMIN', 90, 'SETTINGS', 'ShieldCheck',     0, NULL)
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
