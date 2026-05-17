-- intranetservices/src/main/resources/db/migration/V346__Add_expense_review_workflow.sql
ALTER TABLE expenses
  ADD COLUMN review_state          VARCHAR(32) NULL
    COMMENT 'NEEDS_FIX | NEEDS_JUSTIFICATION | PENDING_HR | HR_SENT_BACK; NULL = not in review workflow',
  ADD COLUMN ai_rule_id            VARCHAR(64) NULL
    COMMENT 'Primary AI rule that drove the rejection (e.g. R_MEAL_COST_PER_PERSON). Highest-priority REJECT rule when multiple fire.',
  ADD COLUMN ai_rule_ids_json      JSON        NULL
    COMMENT 'All REJECT rules that fired in the latest validation, ordered by priority. JSON array of rule IDs.',
  ADD COLUMN employee_justification TEXT       NULL
    COMMENT 'Tax-grounded justification from the employee when escalating a JUDGMENT-class rejection to HR.',
  ADD COLUMN hr_decision           VARCHAR(16) NULL
    COMMENT 'APPROVED | SENT_BACK | REJECTED — denormalized latest HR decision for cheap list rendering.',
  ADD COLUMN hr_decision_by        VARCHAR(36) NULL,
  ADD COLUMN hr_decision_at        DATETIME    NULL,
  ADD COLUMN hr_comment            TEXT        NULL
    COMMENT 'HR-to-employee message accompanying a SENT_BACK or REJECTED decision.',
  ADD COLUMN ai_validation_count   INT         NOT NULL DEFAULT 0
    COMMENT 'Number of times AI has been run on this expense. Prevents infinite re-validation loops.',
  ADD COLUMN version               INT         NOT NULL DEFAULT 0
    COMMENT 'Hibernate @Version optimistic-lock column for concurrent state transitions.',
  ADD INDEX idx_expenses_review_state (review_state, status, datemodified);
