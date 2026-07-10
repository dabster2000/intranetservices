-- =============================================================================
-- V393: individual_bonus_payout — immutable reproducibility snapshot per payout
--
-- GOAL
--   At materialisation, alongside each individual-bonus salary_lump_sum, freeze
--   the effective spec + resolved inputs that produced the amount, so a past
--   payout stays reconstructable even after the driving individual_bonus_rule is
--   later edited or soft-deleted. Bonuses are money — a past payout must be
--   replayable.
--
-- IMPORTANT — spec_json is LONGTEXT, NOT a native JSON column type.
--   Same rationale as individual_bonus_rule.spec (V391): this codebase's global
--   JavaTimeObjectMapperCustomizer makes Hibernate's @JdbcTypeCode(JSON) crash
--   Quarkus boot, so the spec is persisted as plain text.
--
-- IDEMPOTENT WRITE
--   source_reference carries the SAME stable key as the lump sum
--   (individual:{ruleUuid}:{advance|yearly|trueup}:{period}) and is UNIQUE, so the
--   snapshot is written exactly once even if the monthly job double-fires (ECS-
--   Express cutover). Written in the same REQUIRES_NEW transaction as the lump sum.
--
-- IDEMPOTENT / RE-RUNNABLE MIGRATION
--   IF NOT EXISTS on the table and every index so a partially-applied run re-runs
--   cleanly. No FK constraints (the app maintains integrity), matching the
--   individual_bonus_rule / partner_bonus_payouts precedent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS individual_bonus_payout (
    uuid             CHAR(36)      NOT NULL,
    source_reference VARCHAR(255)  NOT NULL,
    rule_uuid        CHAR(36)      NOT NULL,
    user_uuid        CHAR(36)      NOT NULL,
    month            DATE          NOT NULL,
    kind             VARCHAR(32)   NOT NULL,
    amount           DECIMAL(15,2) NOT NULL,
    spec_json        LONGTEXT      NOT NULL,
    basis_amount     DECIMAL(15,2) NULL,
    months_employed  INT           NULL,
    created_at       DATETIME      NOT NULL,
    CONSTRAINT pk_individual_bonus_payout PRIMARY KEY (uuid)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_individual_bonus_payout_source_ref
    ON individual_bonus_payout (source_reference);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_payout_rule
    ON individual_bonus_payout (rule_uuid);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_payout_user
    ON individual_bonus_payout (user_uuid);
