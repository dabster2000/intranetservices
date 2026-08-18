-- W2 (expense revamp): AI closes the justification loop.
-- After an employee submits a justification, one cheap AI pass judges whether it
-- plausibly addresses the fired rule. Accept -> auto-approve (soft-flagged for the
-- W5 spot-check); refer -> stays with the controller, annotated with the AI's
-- reservation. Guardrails always refer to a human and fail closed.

-- Rules that must ALWAYS reach a human regardless of the AI's verdict
-- (fraud-shaped rules; editable per rule in the Policy Library).
ALTER TABLE ai_rule_catalog ADD COLUMN always_human TINYINT(1) NOT NULL DEFAULT 0;
UPDATE ai_rule_catalog SET always_human = 1
WHERE rule_id IN ('R_ENTERTAINMENT_VENUE', 'R_LEAVE_CONFLICT', 'R_HOME_PROXIMITY',
                  'R_IT_EQUIPMENT_LIMIT', 'R_SOFTWARE_LICENSE');

-- Guardrail parameters (editable in Settings -> AI validation -> Engineering).
INSERT INTO ai_validation_parameter (parameter_key, parameter_value, value_type, description, updated_at, updated_by) VALUES
  ('justification_always_human_dkk', '1000', 'DECIMAL',
   'Expenses at or above this amount (DKK) always go to a human, even when the AI accepts the justification.', NOW(3), 'V510'),
  ('justification_repeat_fires_threshold', '3', 'INTEGER',
   'Same employee + same rule fired at least this many times inside the window -> always human.', NOW(3), 'V510'),
  ('justification_repeat_window_days', '90', 'INTEGER',
   'Look-back window (days) for the repeat-fires guardrail.', NOW(3), 'V510'),
  ('justification_min_confidence', '0.70', 'DECIMAL',
   'Below this AI confidence a justification verdict refers to a human instead of auto-approving.', NOW(3), 'V510');

-- The review prompt (editable in the console; {{...}} chips resolve from ai_validation_parameter).
INSERT INTO ai_prompt_template (template_key, body, current_version, updated_at, updated_by) VALUES
  ('JUSTIFICATION_REVIEW',
   'You review employee justifications for expense-policy exceptions at a Danish IT consultancy.\nAn AI validation rule fired on the expense; the employee has now explained the business reason.\nJudge ONLY whether the written justification plausibly addresses the fired rule for this specific\nexpense. Accept genuine, specific business reasons (who/what/why). Refer to a human when the\njustification is vague, generic, unrelated to the rule, contradicts the receipt facts, or smells\nof repeated boilerplate. You never see the receipt image - judge the text against the facts given.\nReturn JSON only.',
   1, NOW(3), 'V510');
