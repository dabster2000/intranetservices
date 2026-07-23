-- ===================================================================
-- V440: Recruitment ATS expansion — Phase 9: AI intake assist
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P9,
--          AI companion spec 2026-07-22)
-- Domain:  recruitmentservice (feature toggles + two suggestible
--          candidate columns + settings-tab registry row)
--
-- Purpose:
--   1. Seed the six recruitment.ai.* companion feature toggles (all
--      opt-in, default 'false'). Three are live in P9 (intake chips,
--      candidate brief, referral triage assist); three are seeded
--      ahead of their phases (email composer P16, the two digests
--      P24) so the settings tab can render the full roster with
--      "coming later" badges.
--   2. Add the two candidate columns the AI intake suggestions target
--      but that had no storage until now (spec deviation §12.3):
--      languages (JSON string array via StringListConverter, like
--      specializations — TEXT column, converter-managed) and
--      current_employer (free text, PII-classified on events).
--   3. Register the "Recruitment AI" settings tab in page_registry.
--
-- Design notes:
--   * Toggle category is 'recruitment' — the same category as the core
--     flags, so every recruitment page's existing settings fetch covers
--     them; the settings tab filters by key prefix 'recruitment.ai.'.
--   * NO admin bypass semantics live in these rows — the toggles gate
--     reactor side effects; resource-level guards keep the standard
--     404 + admin:* convention.
--   * display_order 150 — directly after the last SETTINGS row
--     (settings-teams, 140; verified live), same section/roles idiom.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is ADD COLUMN IF NOT EXISTS, toggle seeds are INSERT IGNORE
--   (never overwrite admin edits), the page_registry seed is
--   INSERT ... ON DUPLICATE KEY UPDATE (registry convention,
--   V430/V434/V438/V439).
--
-- Collation: utf8mb4_general_ci (schema default since V315 repair);
--   UUIDs stay VARCHAR(36) like every sibling column.
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: inert without the P9 images. Full removal (only if the
--   programme is abandoned):
--     DELETE FROM app_settings WHERE setting_key LIKE 'recruitment.ai.%';
--     ALTER TABLE recruitment_candidates
--         DROP COLUMN IF EXISTS languages,
--         DROP COLUMN IF EXISTS current_employer;
--     DELETE FROM page_registry WHERE page_key = 'settings-recruitment-ai';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Companion feature toggles (all default OFF — opt-in).
-- -------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.ai.intake.enabled',                    'false', 'recruitment'),
    ('recruitment.ai.brief.enabled',                     'false', 'recruitment'),
    ('recruitment.ai.referral-triage.enabled',           'false', 'recruitment'),
    ('recruitment.ai.email-composer.enabled',            'false', 'recruitment'),
    ('recruitment.ai.digest.weekly-funnel.enabled',      'false', 'recruitment'),
    ('recruitment.ai.digest.rejection-patterns.enabled', 'false', 'recruitment');

-- -------------------------------------------------------------------
-- 2. Suggestible candidate columns (spec deviation §12.3 — the AI
--    suggests values for fields that need storage).
--    languages: JSON string array managed by StringListConverter
--    (same mechanism as specializations); TEXT keeps it converter-
--    owned without a JSON CHECK constraint.
-- -------------------------------------------------------------------
ALTER TABLE recruitment_candidates
    ADD COLUMN IF NOT EXISTS languages TEXT NULL
        COMMENT 'JSON string array — spoken/written languages (AI-suggestible, P9)',
    ADD COLUMN IF NOT EXISTS current_employer VARCHAR(200) NULL
        COMMENT 'Current employer (free text, PII on events; AI-suggestible, P9)';

-- -------------------------------------------------------------------
-- 3. Settings-tab registration for the Recruitment AI toggles.
--    (P13 renames the label to "Recruitment AI & Slack".)
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-recruitment-ai', 'Recruitment AI', 1, '/settings', 'ADMIN', 150, 'SETTINGS', 'Sparkles', 0, NULL)
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
