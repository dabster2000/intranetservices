-- ============================================================================
-- V361: Seed configurable timesheet submission authority roles
-- ============================================================================
-- Purpose: Stores which user roles may CHANGE timesheet submission state —
--          i.e. unlock a SUBMITTED month and submit a month on behalf of
--          another user. Admins edit this in Settings → Access Management →
--          Timesheet Submission.
--
--          The frontend reads this via app_settings (category 'timesheet') and
--          falls back to 'ADMIN' when the row is absent/empty, so behaviour is
--          identical to the previous hardcoded hasRole('ADMIN') gate until an
--          admin changes it.
--
--          Value is a comma-separated list of UPPERCASE role names matching the
--          frontend role vocabulary (see mapBackendRoles), e.g. 'ADMIN,HR'.
--
-- INSERT IGNORE: relies on the uq_app_settings_key UNIQUE constraint so a later
--          admin edit is never reset to the default on redeploy.
--
-- Rollback: DELETE FROM app_settings WHERE setting_key = 'timesheet.submission.authorityRoles';
--
-- Author: Claude Code
-- Date: 2026-06-02
-- ============================================================================

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('timesheet.submission.authorityRoles', 'ADMIN', 'timesheet');
