-- V308__recruitment_page_registry_routes.sql
-- Register top-level recruitment routes for RouteAccessGuard.
-- RouteAccessGuard checks required_roles (org roles), not recruitment API scopes.
INSERT INTO page_registry (
    page_key, page_label, is_visible, react_route, required_roles,
    display_order, section, icon_name, is_external, external_url
) VALUES
  ('recruitment-overview',    'Recruitment Overview', true, '/recruitment/overview',    'HR,TEAMLEAD,PARTNER,ADMIN', 310, 'HR', 'BriefcaseBusiness', false, NULL),
  ('recruitment-roles',       'Open Roles',           true, '/recruitment/roles',       'HR,TEAMLEAD,PARTNER,ADMIN', 311, 'HR', 'UsersRound',         false, NULL),
  ('recruitment-candidates',  'Candidates',           true, '/recruitment/candidates',  'HR,TEAMLEAD,PARTNER,ADMIN', 312, 'HR', 'UserRoundSearch',   false, NULL),
  ('recruitment-talent-pool', 'Talent Pool',          true, '/recruitment/talent-pool', 'HR,TEAMLEAD,PARTNER,ADMIN', 313, 'HR', 'Archive',           false, NULL);
