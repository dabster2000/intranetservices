-- ============================================================================
-- V253: Create practice_settings table for configurable per-practice settings
-- ============================================================================
-- Purpose: Introduces a generic key/value settings table scoped per practice.
--          Initial use case: configurable IT budget per practice, replacing
--          the hardcoded 25,000 DKK value.
--
--          Uses a (practice, setting_key) unique constraint so each practice
--          can have at most one value per setting. The setting_value column
--          stores all values as VARCHAR; callers parse to the appropriate type.
--
-- Practices seeded: SA, BA, PM, DEV, CYB, JK, UD
--          (matches PrimarySkillType enum in the Java codebase)
--
-- Initial settings:
--   it_budget = 25000  (DKK, integer stored as string)
--
-- Timezone: updated_at uses server-local CURRENT_TIMESTAMP (MariaDB default).
--           Application layer interprets as UTC.
--
-- Rollback: DROP TABLE practice_settings;
--
-- Impact:
--   - New Quarkus entity: PracticeSetting (to be created)
--   - New Quarkus service: PracticeSettingService (to be created)
--   - New Quarkus resource: PracticeSettingResource (to be created)
--   - Frontend: admin page at /admin/practice-settings
--   - Frontend: IT budget lookup changes from hardcoded to per-practice
--
-- Author: Claude Code
-- Date: 2026-03-19
-- ============================================================================

CREATE TABLE practice_settings (
    id INT AUTO_INCREMENT PRIMARY KEY,

    -- Practice identifier (matches PrimarySkillType enum: SA, BA, PM, DEV, CYB, JK, UD)
    practice VARCHAR(10) NOT NULL,

    -- Setting name (e.g., 'it_budget')
    setting_key VARCHAR(100) NOT NULL,

    -- Setting value stored as string; callers parse to appropriate type
    setting_value VARCHAR(255) NOT NULL,

    -- Audit: when was this setting last changed
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Audit: UUID of the user who last changed this setting (nullable for seed data)
    updated_by VARCHAR(36),

    -- Each practice can have at most one value per setting key
    UNIQUE KEY uk_practice_setting (practice, setting_key),

    -- Index for lookups by setting key across all practices
    INDEX idx_practice_settings_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- Seed default IT budget for all 7 practices (25,000 DKK each)
-- ============================================================================

INSERT INTO practice_settings (practice, setting_key, setting_value) VALUES
    ('SA',  'it_budget', '25000'),
    ('BA',  'it_budget', '25000'),
    ('PM',  'it_budget', '25000'),
    ('DEV', 'it_budget', '25000'),
    ('CYB', 'it_budget', '25000'),
    ('JK',  'it_budget', '25000'),
    ('UD',  'it_budget', '25000');
