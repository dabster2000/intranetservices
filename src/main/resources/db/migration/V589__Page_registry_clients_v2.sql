-- V589: page_registry row for /clients_v2 — the Intra CRM rewrite of the clients pages,
-- running alongside the ones it will eventually replace.
--
-- Why a row at all, for a page nobody links to:
--
-- RouteAccessGuard admits any route it finds no row for (step 6, fail-open by default).
-- Without this row every authenticated employee could open /clients_v2 by URL and read
-- account bands, rates, revenue, margins and the account plan — data /clients has gated
-- behind crm:write since V467. The row also survives the day the fail-closed flag
-- (app_settings `authorization.route-guard.fail-closed`) is armed, which would otherwise
-- take the new pages down without anyone touching them.
--
-- is_visible = 0 is what keeps it out of the menus: navigation is built from the visible
-- rows only (usePageRegistry.navItems), so the page is reachable by direct URL and
-- nowhere else. Flipping it to 1 — a PUT on /system/page-registry/clients-v2, no
-- redeploy — is the switch that puts it in the menu when the rewrite is ready.
--
-- required_roles and required_permission are copied from `clients` rather than spelled
-- out, so the two pages cannot drift apart while both exist.

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission, display_order, section, icon_name)
SELECT 'clients-v2', 'Clients (v2)', 0, '/clients_v2', required_roles, required_permission, 211, section, icon_name
  FROM page_registry WHERE page_key = 'clients'
ON DUPLICATE KEY UPDATE
    react_route         = VALUES(react_route),
    required_roles      = VALUES(required_roles),
    required_permission = VALUES(required_permission);
