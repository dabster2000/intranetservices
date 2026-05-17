-- intranetservices/src/main/resources/db/migration/V348__Create_ai_config_tables.sql

CREATE TABLE ai_rule_catalog (
  uuid             VARCHAR(36)  NOT NULL PRIMARY KEY,
  rule_id          VARCHAR(64)  NOT NULL UNIQUE,
  display_name     VARCHAR(128) NOT NULL,
  description      TEXT         NOT NULL,
  severity         VARCHAR(32)  NOT NULL,
  resolution_type  VARCHAR(16)  NOT NULL,
  priority         INT          NOT NULL,
  active           BOOLEAN      NOT NULL DEFAULT TRUE,
  updated_at       DATETIME(3)  NOT NULL,
  updated_by       VARCHAR(36)  NOT NULL,
  INDEX idx_ai_rule_priority (priority)
);

CREATE TABLE ai_validation_parameter (
  parameter_key    VARCHAR(64)  NOT NULL PRIMARY KEY,
  parameter_value  VARCHAR(256) NOT NULL,
  value_type       VARCHAR(16)  NOT NULL,
  description      TEXT         NOT NULL,
  updated_at       DATETIME(3)  NOT NULL,
  updated_by       VARCHAR(36)  NOT NULL
);

CREATE TABLE ai_prompt_template (
  template_key     VARCHAR(64)  NOT NULL PRIMARY KEY,
  body             MEDIUMTEXT   NOT NULL,
  current_version  INT          NOT NULL DEFAULT 1,
  updated_at       DATETIME(3)  NOT NULL,
  updated_by       VARCHAR(36)  NOT NULL
);

CREATE TABLE ai_config_history (
  uuid          VARCHAR(36)  NOT NULL PRIMARY KEY,
  entity_kind   VARCHAR(16)  NOT NULL,
  entity_key    VARCHAR(64)  NOT NULL,
  change_action VARCHAR(16)  NOT NULL,
  snapshot_json JSON         NOT NULL,
  changed_at    DATETIME(3)  NOT NULL,
  changed_by    VARCHAR(36)  NOT NULL,
  INDEX idx_ach_entity (entity_kind, entity_key, changed_at)
);

-- Seed rules (descriptions copied from ExpenseAIValidationService)
INSERT INTO ai_rule_catalog (uuid, rule_id, display_name, description, severity, resolution_type, priority, active, updated_at, updated_by) VALUES
  (UUID(), 'R_OVERRIDE_WHITELIST_ADDRESS', 'Whitelist address override',
   'Food/drink on Nyropsgade or Landgreven (Copenhagen) is always approved.',
   'OVERRIDE_APPROVE', 'NONE',     10,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_RECEIPT_READABLE', 'Receipt readable',
   'Receipt image must show a legible date and total.',
   'REJECT', 'AUTO_FIX',           10,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_OFFICE_FOOD_DRINK', 'Office food/drink',
   'Food or drink purchased within 1 km of the Trustworks office (Pustervig 3, 1126 København K) is rejected.',
   'REJECT', 'JUDGMENT',           20,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_MEAL_COST_PER_PERSON', 'Meal cost per person',
   'Food or drink above 125 DKK per person requires a documented business reason.',
   'REJECT', 'JUDGMENT',           30,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_TRANSPORTATION_ELIGIBLE', 'Transportation eligibility',
   'Transportation reimbursements require a same-day client budget and must fall within 08:00-17:00.',
   'REJECT', 'JUDGMENT',           40,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_WEEKEND_FOOD_DRINK', 'Weekend food/drink',
   'Food or drink purchased on Saturday or Sunday is rejected.',
   'REJECT', 'JUDGMENT',           50,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_LEAVE_CONFLICT', 'Leave conflict',
   'Any leave hours > 0 combined with food/drink/transport on the same day is rejected.',
   'REJECT', 'JUDGMENT',           60,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_IT_EQUIPMENT_LIMIT', 'IT equipment limit',
   'IT equipment purchases above 500 DKK require pre-approval.',
   'REJECT', 'JUDGMENT',           70,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_DATE_MISMATCH', 'Date mismatch',
   'Receipt date and expense date must be within 30 calendar days of each other.',
   'REJECT', 'AUTO_FIX',           80,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_ENTERTAINMENT_VENUE', 'Entertainment venue',
   'Bars, nightclubs, and casinos are rejected.',
   'REJECT', 'JUDGMENT',           90,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_MULTI_PERSON_MEAL', 'Multi-person meal',
   'Food/drink described as group dinner or "for N persons" is rejected pending justification.',
   'REJECT', 'JUDGMENT',          100,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_SOFTWARE_LICENSE', 'Software license',
   'Software/SaaS subscriptions must go via IT procurement.',
   'REJECT', 'JUDGMENT',          110,  TRUE, NOW(3), 'SYSTEM_SEED'),
  (UUID(), 'R_HOME_PROXIMITY', 'Home proximity',
   'Purchases within 1 km of the employee''s home address are rejected.',
   'REJECT', 'JUDGMENT',          120,  TRUE, NOW(3), 'SYSTEM_SEED');

-- Seed parameters
INSERT INTO ai_validation_parameter (parameter_key, parameter_value, value_type, description, updated_at, updated_by) VALUES
  ('meal_cost_per_person_dkk',     '125', 'DECIMAL', 'Per-person meal cost limit (DKK) — drives R_MEAL_COST_PER_PERSON.', NOW(3), 'SYSTEM_SEED'),
  ('it_equipment_pre_approval_dkk','500', 'DECIMAL', 'IT equipment pre-approval threshold (DKK) — drives R_IT_EQUIPMENT_LIMIT.', NOW(3), 'SYSTEM_SEED'),
  ('date_mismatch_tolerance_days',  '30', 'INTEGER', 'Allowed days between expensedate and receipt date — drives R_DATE_MISMATCH.', NOW(3), 'SYSTEM_SEED'),
  ('ai_sweep_interval_minutes',     '50', 'INTEGER', 'Interval (minutes) for the AI re-validation sweep.', NOW(3), 'SYSTEM_SEED'),
  ('max_ai_revalidations',           '3', 'INTEGER', 'Maximum number of AI re-runs before forcing the HR justification path.', NOW(3), 'SYSTEM_SEED'),
  ('cooling_period_days',            '2', 'INTEGER', 'Days an expense must age before becoming eligible for e-conomic upload.', NOW(3), 'SYSTEM_SEED'),
  ('hr_approve_reason_presets',
   '["Business meeting with client","After-hours client work","Pre-approved by manager","Reasonable per LL §16 / company policy"]',
   'TEXT', 'Preset HR Approve dropdown reasons (JSON array of strings).', NOW(3), 'SYSTEM_SEED');

-- Seed prompts: bodies will be filled by Task 2.4 after extraction; placeholder for now
INSERT INTO ai_prompt_template (template_key, body, current_version, updated_at, updated_by) VALUES
  ('VISION_EXTRACTION', '__SEED_VISION_PROMPT_REPLACED_AT_BOOT__', 1, NOW(3), 'SYSTEM_SEED'),
  ('POLICY_VALIDATION', '__SEED_POLICY_PROMPT_REPLACED_AT_BOOT__', 1, NOW(3), 'SYSTEM_SEED');
