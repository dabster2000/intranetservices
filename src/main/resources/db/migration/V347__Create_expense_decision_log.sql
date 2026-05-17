-- intranetservices/src/main/resources/db/migration/V347__Create_expense_decision_log.sql
CREATE TABLE expense_decision_log (
  uuid              VARCHAR(36)  NOT NULL PRIMARY KEY,
  expense_uuid      VARCHAR(36)  NOT NULL,
  occurred_at       DATETIME(3)  NOT NULL,
  actor_uuid        VARCHAR(36)  NULL    COMMENT 'NULL for AI / SYSTEM actions',
  actor_role        VARCHAR(16)  NOT NULL COMMENT 'EMPLOYEE | HR | AI | SYSTEM',
  action            VARCHAR(40)  NOT NULL
    COMMENT 'AI_VALIDATED_APPROVED | AI_VALIDATED_REJECTED | EMPLOYEE_FIX_SUBMITTED | EMPLOYEE_JUSTIFICATION_SUBMITTED | HR_APPROVED | HR_SENT_BACK | HR_REJECTED | LEGACY_OVERRIDE',
  from_status       VARCHAR(32) NULL,
  to_status         VARCHAR(32) NULL,
  from_review_state VARCHAR(32) NULL,
  to_review_state   VARCHAR(32) NULL,
  ai_rule_id        VARCHAR(64) NULL,
  reason_text       TEXT        NULL,
  CONSTRAINT fk_edl_expense FOREIGN KEY (expense_uuid) REFERENCES expenses(uuid) ON DELETE CASCADE,
  INDEX idx_edl_expense (expense_uuid, occurred_at),
  INDEX idx_edl_actor   (actor_uuid,  occurred_at)
);
