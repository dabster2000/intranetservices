-- ===================================================================
-- V532: Recruitment Slack offer-phase channel (2026-08-25)
--
-- Adds the HR-facing offer-phase routing key:
--
--     recruitment.slack.channel.offer
--
-- When an admin fills it with a Slack channel ID, the offer-phase
-- notifications (candidate enters OFFER, contract sent for signature,
-- documents signed, conversion to employee, onboarding uploads
-- complete) MOVE there — away from the practice/default channels and
-- the previously hard-coded HR channel. Seeded BLANK on purpose:
-- blank = the split is off and every routing behaves exactly as it
-- did before this key existed (the V443 default-channel idiom).
--
-- INSERT IGNORE so a re-run — or an environment where an admin has
-- already configured the channel — never clobbers the value.
-- repair-at-start re-runs migrations across checkouts.
--
-- Rollback: blank value = the feature is inert; full revert =
--     DELETE FROM app_settings
--       WHERE setting_key = 'recruitment.slack.channel.offer';
-- ===================================================================

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('recruitment.slack.channel.offer', '', 'recruitment');
