-- ===================================================================
-- V439: Recruitment ATS expansion — Phase 7/8: viewing surfaces
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P7/§P8, spec §6.1)
-- Domain:  recruitmentservice (read surfaces — no new tables)
--
-- Purpose:
--   Register the two read-only viewing pages in the sidebar/route
--   registry. The backend board endpoint (P7) reuses the P2–P6 schema
--   unchanged (idx_ra_position_stage from V436 covers the board query),
--   so this migration is page_registry rows only.
--
-- Rows:
--   /recruitment/pipeline   — kanban board per position. Same audience
--     as /recruitment/positions (V434 row 871): the board is that
--     page's drill-down. Roles ADMIN,HR,TEAMLEAD,PARTNER,CXO.
--   /recruitment/candidates — database grid + candidate profiles.
--     Recruiter tier + the dossier-era audience: ADMIN,HR,CXO,
--     TECHPARTNER (matches the existing /recruitment BFF route roles).
--
-- Design notes:
--   * The registry row is UI routing + sidebar visibility, NOT the
--     security boundary — the BFF enforces requireRoles per route and
--     the backend enforces scopes + RecruitmentVisibility (circle hard
--     filter, practice-lead read, involvement tiers).
--   * display_order 872/873 — directly after the existing recruitment
--     rows (870 recruitment, 871 positions), section ADMIN like them.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   seeds are INSERT ... ON DUPLICATE KEY UPDATE (page_registry
--   convention, V430/V434/V438).
--
-- Author: Claude Code
-- Date:   2026-07-22
-- Rollback: inert without the P7/P8 images. Full removal (only if the
--   programme is abandoned):
--     DELETE FROM page_registry WHERE page_key IN
--       ('recruitment-pipeline', 'recruitment-candidates');
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Sidebar / route registration for /recruitment/pipeline.
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-pipeline', 'Pipeline', 1, '/recruitment/pipeline', 'ADMIN,HR,TEAMLEAD,PARTNER,CXO', 872, 'ADMIN', 'Kanban', 0, NULL)
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

-- -------------------------------------------------------------------
-- 2. Sidebar / route registration for /recruitment/candidates.
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('recruitment-candidates', 'Candidates', 1, '/recruitment/candidates', 'ADMIN,HR,CXO,TECHPARTNER', 873, 'ADMIN', 'UserSearch', 0, NULL)
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
