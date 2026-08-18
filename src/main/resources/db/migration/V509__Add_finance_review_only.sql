-- W3 (expense revamp): mark inbox rows whose ONLY problem is the classifier
-- fallback — the AI cleared the receipt, but the chosen expense route requires
-- Finance review (account 9998 / "Other – not sure"). These rows feed the
-- one-click "Assign account" micro-queue instead of the judgment inbox.
--
-- Set by ExpenseCreatedConsumer at routing time from here on.
-- NULL = unknown (row routed before this column existed and not covered by the
-- evidence backfill below).
ALTER TABLE expenses ADD COLUMN finance_review_only TINYINT(1) NULL;

-- Evidence backfill: rows currently sitting with Accounting as POLICY that the
-- AI approved are exactly the classifier-fallback cohort — a rule block or a
-- justification hand-off always has ai_validation_approved = 0.
UPDATE expenses SET finance_review_only = 1
WHERE state = 'NEEDS_ATTENTION'
  AND attention_owner = 'ACCOUNTING'
  AND attention_kind = 'POLICY'
  AND ai_validation_approved = 1;
