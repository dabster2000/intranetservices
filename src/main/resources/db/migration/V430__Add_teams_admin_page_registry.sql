-- ====================================================================
-- V430: Teams admin settings-tab registration.
--
-- Registers the 'settings-teams' tab hosting team create/edit and the
-- team → practice association. It is the sibling of the 'settings-practices'
-- tab registered by V418 (display_order 130) and renders next to it, hence
-- display_order 140 and a distinct icon — 'Users' is already taken twice.
--
-- Registration only: the team tables long predate this repository's
-- migrations, and there is deliberately no delete/archive column to add
-- (teamroles has no FK to team, so rows would silently dangle).
--
-- Conventions (per project rules):
--   * Idempotent: page_registry INSERT mirrors V418 (ON DUPLICATE KEY UPDATE).
--   * Numbering: originally authored as V431 with V430 reserved for the
--     practice final-cleanup; renumbered 2026-07-21 to close the gap. The
--     cleanup became V431 (relax display_code) + V432 (drop).
-- ====================================================================

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-teams', 'Teams', 1, '/settings?tab=teams', 'ADMIN', 140, 'SETTINGS', 'UsersRound', 0, NULL)
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
