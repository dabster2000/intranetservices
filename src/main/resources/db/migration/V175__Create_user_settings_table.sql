-- ============================================================================
-- V175: Create user_settings table for storing user-specific preferences
--
-- Creates a key-value table to store user settings and preferences.
-- Settings are stored per user with unique key constraints to prevent
-- duplicate settings. Values can be JSON or plain text.
-- ============================================================================

CREATE TABLE user_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Auto-increment primary key',
    user_uuid VARCHAR(36) NOT NULL COMMENT 'User UUID (references users table)',
    setting_key VARCHAR(100) NOT NULL COMMENT 'Setting key identifier',
    setting_value TEXT NULL COMMENT 'Setting value (JSON or plain text)',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    UNIQUE KEY uk_user_settings (user_uuid, setting_key),
    INDEX idx_user_settings_user (user_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='User-specific settings stored as key-value pairs';
