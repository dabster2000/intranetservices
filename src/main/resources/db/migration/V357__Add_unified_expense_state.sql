-- intranetservices/src/main/resources/db/migration/V357__Add_unified_expense_state.sql
-- Phase 0 of the expense-flow redesign. Additive only; no existing column changed.
ALTER TABLE expenses
  ADD COLUMN state            VARCHAR(24)  NULL
    COMMENT 'Unified workflow state: SUBMITTED|NEEDS_ATTENTION|APPROVED|POSTING|POSTED|BOOKED|REJECTED|DELETED. Phase 0 derived mirror of status+review_state+ai.',
  ADD COLUMN attention_owner  VARCHAR(16)  NULL
    COMMENT 'EMPLOYEE|ACCOUNTING — whose turn; only when state=NEEDS_ATTENTION.',
  ADD COLUMN attention_kind   VARCHAR(24)  NULL
    COMMENT 'RECEIPT|JUSTIFICATION|POLICY|TECHNICAL|AMOUNT_MISMATCH — only when state=NEEDS_ATTENTION.',
  ADD COLUMN ai_outcome       VARCHAR(16)  NULL
    COMMENT 'APPROVE|SOFT_FLAG|BLOCK — AI outcome tier. Populated from Phase 1.',
  ADD COLUMN ai_confidence    DECIMAL(4,3) NULL
    COMMENT 'AI confidence 0.000-1.000 for the driving finding. Populated from Phase 1.',
  ADD COLUMN soft_flags       JSON         NULL
    COMMENT 'Non-blocking AI findings for optional accounting spot-check. Populated from Phase 1.';

-- Index for the new accounting Inbox queries (state + owner + recency).
CREATE INDEX idx_expenses_state ON expenses (state, attention_owner, datemodified)
  ALGORITHM=INPLACE LOCK=NONE;

-- Backfill all existing rows. CASE logic MIRRORS ExpenseStateDeriver.derive() — keep in sync.
-- 17.4K rows: a single UPDATE is well within limits. Already-posted rows ignore any stale
-- review_state (clears the 169-item "Pending review" fiction queue).
UPDATE expenses SET
  state = CASE
    WHEN status = 'VERIFIED_BOOKED'                                   THEN 'BOOKED'
    WHEN status = 'VERIFIED_UNBOOKED'                                 THEN 'POSTED'
    WHEN status IN ('UPLOADED','VOUCHER_CREATED','PROCESSING')        THEN 'POSTING'
    WHEN status IN ('UP_FAILED','NO_FILE','NO_USER')                  THEN 'NEEDS_ATTENTION'
    WHEN status = 'VALIDATED'                                         THEN 'APPROVED'
    WHEN status = 'DELETED' AND hr_decision = 'REJECTED'              THEN 'REJECTED'
    WHEN status = 'DELETED'                                           THEN 'DELETED'
    WHEN status = 'CREATED' AND review_state = 'NEEDS_FIX'            THEN 'NEEDS_ATTENTION'
    WHEN status = 'CREATED' AND review_state IN ('NEEDS_JUSTIFICATION','HR_SENT_BACK') THEN 'NEEDS_ATTENTION'
    WHEN status = 'CREATED' AND review_state = 'PENDING_HR'           THEN 'NEEDS_ATTENTION'
    WHEN status = 'CREATED' AND ai_validation_approved = 0            THEN 'NEEDS_ATTENTION'
    WHEN status = 'CREATED'                                           THEN 'SUBMITTED'
    ELSE 'SUBMITTED'
  END,
  attention_owner = CASE
    WHEN status IN ('UP_FAILED','NO_FILE','NO_USER')                  THEN 'ACCOUNTING'
    WHEN status = 'CREATED' AND review_state = 'NEEDS_FIX'            THEN 'EMPLOYEE'
    WHEN status = 'CREATED' AND review_state IN ('NEEDS_JUSTIFICATION','HR_SENT_BACK') THEN 'EMPLOYEE'
    WHEN status = 'CREATED' AND review_state = 'PENDING_HR'           THEN 'ACCOUNTING'
    WHEN status = 'CREATED' AND ai_validation_approved = 0           THEN 'ACCOUNTING'
    ELSE NULL
  END,
  attention_kind = CASE
    WHEN status IN ('UP_FAILED','NO_FILE','NO_USER')                  THEN 'TECHNICAL'
    WHEN status = 'CREATED' AND review_state = 'NEEDS_FIX'            THEN 'RECEIPT'
    WHEN status = 'CREATED' AND review_state IN ('NEEDS_JUSTIFICATION','HR_SENT_BACK') THEN 'JUSTIFICATION'
    WHEN status = 'CREATED' AND review_state = 'PENDING_HR'           THEN 'POLICY'
    WHEN status = 'CREATED' AND ai_validation_approved = 0           THEN 'POLICY'
    ELSE NULL
  END;
