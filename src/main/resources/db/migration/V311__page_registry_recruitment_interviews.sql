-- V311__page_registry_recruitment_interviews.sql
-- Register /recruitment/interviews in page_registry so RouteAccessGuard
-- can gate the new Interviews tab the same way it gates the rest of the
-- recruitment app (HR, TEAMLEAD, PARTNER, ADMIN).
--
-- Note: page_registry is the menu/route guard table (renamed from
-- page_migration in V267). It does NOT carry recruitment API scope masks
-- — those live on the JWT and are enforced by @RolesAllowed on the
-- Quarkus resources. Slice 2's V308 used the same shape and column set;
-- V311 mirrors that exactly.
--
-- Idempotent insert: if the page_key already exists (e.g. a re-run on a
-- partially seeded environment), do nothing.

INSERT INTO page_registry (
    page_key, page_label, is_visible, react_route, required_roles,
    display_order, section, icon_name, is_external, external_url
)
SELECT
    'recruitment-interviews', 'Interviews', true, '/recruitment/interviews',
    'HR,TEAMLEAD,PARTNER,ADMIN', 314, 'HR', 'CalendarCheck2', false, NULL
WHERE NOT EXISTS (
    SELECT 1 FROM page_registry WHERE page_key = 'recruitment-interviews'
);
