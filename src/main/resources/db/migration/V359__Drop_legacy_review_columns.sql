-- intranetservices/src/main/resources/db/migration/V359__Drop_legacy_review_columns.sql
-- Phase 3 Release B. RUN ONLY after Release A (stop reading/writing these columns) has
-- fully baked on production — see the two-release pattern note. The reject/send-back reason
-- previously in hr_comment is preserved in expense_decision_log.reason_text.

-- The review-state index (V346) covers review_state and must be dropped before its column.
ALTER TABLE expenses DROP INDEX idx_expenses_review_state;

-- Online DDL per project convention (.claude/rules/database.md) and the V357 precedent on
-- this same table. INPLACE/LOCK=NONE is non-blocking and, unlike ALGORITHM=INSTANT (which
-- errors outright if the row format disallows it), falls back to COPY rather than failing
-- Flyway startup — safe here since by Release B no running image reads these columns.
ALTER TABLE expenses
  DROP COLUMN review_state,
  DROP COLUMN hr_decision,
  DROP COLUMN hr_decision_by,
  DROP COLUMN hr_decision_at,
  DROP COLUMN hr_comment,
  ALGORITHM=INPLACE, LOCK=NONE;
