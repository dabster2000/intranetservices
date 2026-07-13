-- Expand-only storage for versioned monthly individual-bonus calculations.
-- Existing rule JSON and payout rows are deliberately left untouched.

ALTER TABLE individual_bonus_rule
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE individual_bonus_payout
    ADD COLUMN IF NOT EXISTS earning_month DATE NULL,
    ADD COLUMN IF NOT EXISTS pay_month DATE NULL,
    ADD COLUMN IF NOT EXISTS company_uuid CHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS materialization_status VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS snapshot_version SMALLINT NULL,
    ADD COLUMN IF NOT EXISTS calculation_snapshot LONGTEXT NULL,
    ADD COLUMN IF NOT EXISTS calculation_fingerprint CHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS actor_uuid VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS salary_lump_sum_uuid CHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS facts_as_of DATETIME(6) NULL;

CREATE INDEX IF NOT EXISTS idx_individual_bonus_payout_rule_earning
    ON individual_bonus_payout (rule_uuid, earning_month);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_payout_user_earning
    ON individual_bonus_payout (user_uuid, earning_month);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_payout_company_pay_status
    ON individual_bonus_payout (company_uuid, pay_month, materialization_status);
