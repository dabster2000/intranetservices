-- Remove date-based eligibility columns; validation now only checks existence + canSelfAssign
ALTER TABLE invoice_bonus_eligibility
    DROP COLUMN active_from,
    DROP COLUMN active_to;