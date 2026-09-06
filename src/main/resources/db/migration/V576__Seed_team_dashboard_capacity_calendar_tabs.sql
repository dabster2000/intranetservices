-- V576: Team Dashboard tab visibility for the two WP6 tabs — JK Team 2.0 §4.6.0
--
-- The global admin visibility keys (V265) stay and apply on top of the team-kind
-- switch. The new tabs get keys of the same shape: `capacity` replaces Utilization
-- for an HOURLY team, `calendar` is shown to both kinds. Idempotent: an existing
-- row keeps whatever value the admin has set.

INSERT INTO app_settings (setting_key, setting_value, category) VALUES
    ('team_dashboard.tab.capacity.visible', 'true', 'team_dashboard'),
    ('team_dashboard.tab.calendar.visible', 'true', 'team_dashboard')
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
