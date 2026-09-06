-- V577: page_registry row for Junior Talent — JK Team 2.0 WP7 (spec §4.7)
--
-- The one genuinely new page: read-only, for all team leads and partners, under
-- Sales & Pipeline. Same audience as Staffing, so it takes Staffing's required_roles
-- and the same permission (capacity:read); required_permission has been dual-read
-- with required_roles since V467. display_order 265 sits between Staffing (260) and
-- Consultant Allocation (270), where the mockup's sitemap puts it.

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission, display_order, section, icon_name)
SELECT 'junior-talent', 'Junior Talent', 1, '/sales/junior-talent', required_roles, 'capacity:read', 265, 'SALES', 'GraduationCap'
  FROM page_registry WHERE page_key = 'staffing'
ON DUPLICATE KEY UPDATE page_label = VALUES(page_label);
