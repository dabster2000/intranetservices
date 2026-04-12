-- V280__Add_questionnaire_config_fields.sql
-- Add configuration fields for generic questionnaire management

ALTER TABLE questionnaire
  ADD COLUMN start_date DATE DEFAULT NULL AFTER deadline,
  ADD COLUMN reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
  ADD COLUMN reminder_cooldown_days INT NOT NULL DEFAULT 3 AFTER reminder_enabled,
  ADD COLUMN target_practices TEXT DEFAULT NULL AFTER reminder_cooldown_days,
  ADD COLUMN target_teams TEXT DEFAULT NULL AFTER target_practices;

-- Update existing KYC questionnaire with current hardcoded values
UPDATE questionnaire SET
  start_date = '2026-04-12',
  reminder_enabled = TRUE,
  reminder_cooldown_days = 3,
  target_practices = '["SA","BA","PM","DEV","CYB","JK"]',
  target_teams = NULL
WHERE uuid = 'kyc-2026-q2';
