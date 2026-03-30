-- ============================================================================
-- V265: Create app_settings table for application-wide key/value settings
-- ============================================================================
-- Purpose: Introduces a generic key/value settings table for application-wide
--          configuration that does not belong to a specific practice or user.
--
--          Initial use case: team dashboard tab visibility. Each tab can be
--          toggled on/off by an admin, controlling which tabs appear for all
--          users on the team dashboard.
--
--          Uses a UNIQUE constraint on setting_key so each setting exists at
--          most once. The setting_value column stores all values as TEXT;
--          callers parse to the appropriate type (boolean, number, JSON, etc.).
--
--          The category column groups related settings for easier querying
--          and admin UI rendering.
--
-- Initial settings (category = 'team_dashboard'):
--   team_dashboard.tab.overview.visible     = true
--   team_dashboard.tab.utilization.visible   = true
--   team_dashboard.tab.staffing.visible      = true
--   team_dashboard.tab.financial.visible     = true
--   team_dashboard.tab.people.visible        = true
--   team_dashboard.tab.bonus.visible         = true
--
-- Timezone: updated_at uses server-local CURRENT_TIMESTAMP (MariaDB default).
--           Application layer interprets as UTC.
--
-- Rollback: DROP TABLE app_settings;
--
-- Impact:
--   - New Quarkus entity: AppSetting (to be created)
--   - New Quarkus service: AppSettingService (to be created)
--   - New Quarkus resource: AppSettingResource (to be created)
--   - Frontend: team dashboard reads tab visibility from API
--   - Frontend: admin settings page to toggle tab visibility
--
-- Author: Claude Code
-- Date: 2026-03-29
-- ============================================================================

CREATE TABLE app_settings (
    id INT AUTO_INCREMENT PRIMARY KEY,

    -- Unique setting identifier (e.g., 'team_dashboard.tab.overview.visible')
    setting_key VARCHAR(200) NOT NULL,

    -- Setting value stored as text; callers parse to appropriate type
    setting_value TEXT NOT NULL,

    -- Grouping category for admin UI and batch queries (e.g., 'team_dashboard')
    category VARCHAR(100) NOT NULL,

    -- Audit: when was this setting last changed
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Audit: UUID of the user who last changed this setting (nullable for seed data)
    updated_by VARCHAR(36),

    -- Each setting key must be unique across the entire table
    UNIQUE KEY uq_app_settings_key (setting_key),

    -- Index for lookups by category
    INDEX idx_app_settings_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- Seed default team dashboard tab visibility settings (all visible by default)
-- ============================================================================

INSERT INTO app_settings (setting_key, setting_value, category) VALUES
    ('team_dashboard.tab.overview.visible',     'true', 'team_dashboard'),
    ('team_dashboard.tab.utilization.visible',   'true', 'team_dashboard'),
    ('team_dashboard.tab.staffing.visible',      'true', 'team_dashboard'),
    ('team_dashboard.tab.financial.visible',     'true', 'team_dashboard'),
    ('team_dashboard.tab.people.visible',        'true', 'team_dashboard'),
    ('team_dashboard.tab.bonus.visible',         'true', 'team_dashboard');
