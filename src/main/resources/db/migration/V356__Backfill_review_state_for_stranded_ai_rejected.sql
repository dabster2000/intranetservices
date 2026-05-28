-- V356__Backfill_review_state_for_stranded_ai_rejected.sql
--
-- Pre-Phase-4 (pre-V346, 2026-05-18) AI-rejected expenses that were overridden
-- by an accountant via the legacy PUT /expenses/{uuid}/status endpoint kept
-- ai_validation_approved = 0 but advanced beyond status=CREATED. Phase 4 added
-- the review_state column NULLable with no backfill, leaving those rows invisible
-- to the four new Accounting review queues (PENDING_HR, AWAITING_EMPLOYEE,
-- HR_SENT_BACK, STUCK) — exactly the visibility gap reported by Marta Katborg.
--
-- This migration backfills review_state = 'PENDING_HR' for the stranded *unpaid*
-- rows so they surface in "Pending review" and an accountant can either approve
-- (clears review_state without downgrading status — see ExpenseReviewDecisionResource)
-- or reject. Paid rows are intentionally skipped: they are already reimbursed and
-- pulling them into a review queue would add noise without recourse.
--
-- Predicate breakdown (matches production count of 169 on 2026-05-28):
--   ai_validation_approved = 0       — AI rejected at submission
--   review_state IS NULL              — never routed by Phase 4
--   status NOT IN ('CREATED','DELETED') — already past AI gate (or removed)
--   paid_out IS NULL                  — still owed to employee
UPDATE expenses
SET review_state = 'PENDING_HR'
WHERE ai_validation_approved = 0
  AND review_state IS NULL
  AND status NOT IN ('CREATED', 'DELETED')
  AND paid_out IS NULL;
