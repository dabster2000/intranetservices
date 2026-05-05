-- ====================================================================
-- V319: Relocate three admin pages into the /settings page tabs.
--
-- Removes 'economics-settings', 'api-clients', and 'access-management'
-- from page_registry. Their content is now embedded as admin-only tabs
-- inside the Settings page (/settings) in the frontend. The original
-- routes (/settings/economics, /admin/api-clients, /admin/access-management)
-- remain reachable by direct URL but no longer appear in the side menu.
--
-- Idempotent (DELETE WHERE), so re-runs are safe.
-- ====================================================================

DELETE FROM page_registry WHERE page_key IN (
    'economics-settings',
    'api-clients',
    'access-management'
);
