-- W5 (expense revamp): the spot-check replaces the pre-payout gate.
-- A deterministic weekly sample of auto-cleared items (soft-flagged approvals and
-- AI-accepted justifications) is surfaced for retrospective review.
INSERT INTO ai_validation_parameter (parameter_key, parameter_value, value_type, description, updated_at, updated_by) VALUES
  ('spot_check_sample_pct', '10', 'INTEGER',
   'Percentage of auto-cleared (soft-flagged) items sampled into the weekly spot-check view. Deterministic per expense (uuid hash), so the set is stable within a week.', NOW(3), 'V511'),
  ('spot_check_window_days', '7', 'INTEGER',
   'Look-back window (days) for the spot-check sample: items auto-cleared within this window are eligible.', NOW(3), 'V511');
