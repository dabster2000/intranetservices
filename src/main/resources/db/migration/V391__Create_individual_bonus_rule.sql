-- =============================================================================
-- V391: individual_bonus_rule — declarative, per-employee, time-boxed bonus rules
--
-- GOAL
--   Store an individual bonus "spec" (basis, tier table, pro-rating, payout
--   schedule, replaces-marker) per employee with an effective-date window. The
--   spec is evaluated at read time by IndividualBonusScheduleService (projection)
--   and materialised just-in-time into salary_lump_sum by the monthly payout job.
--
-- IMPORTANT — spec is stored as LONGTEXT, NOT a native JSON column type.
--   This codebase's global JavaTimeObjectMapperCustomizer makes Hibernate's
--   @JdbcTypeCode(SqlTypes.JSON) crash Quarkus boot, so the spec is persisted as
--   plain text and (de)serialised with a dedicated Jackson ObjectMapper in
--   IndividualBonusSpecMapper.
--
-- AUDIT COLUMNS
--   Follows the established Auditable / AuditEntityListener convention used by
--   salary_lump_sum: created_at / updated_at (NOT NULL, set by the listener) and
--   created_by / modified_by (the header user UUID). NB the modification-user
--   column is `modified_by` (the codebase convention), not `updated_by`.
--
-- IDEMPOTENT / RE-RUNNABLE
--   IF NOT EXISTS on the table and both indexes so a partially-applied or
--   interrupted run (MariaDB auto-commits each DDL) re-runs cleanly. No FK
--   constraints (the app maintains integrity) to avoid charset/collation coupling
--   and canary ALTER hazards, matching the partner_bonus_payouts precedent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS individual_bonus_rule (
    uuid            CHAR(36)     NOT NULL,
    user_uuid       CHAR(36)     NOT NULL,
    name            VARCHAR(255) NOT NULL,
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NULL,
    spec            LONGTEXT     NOT NULL,
    replaces        VARCHAR(50)  NULL,
    active          TINYINT(1)   NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL,
    created_by      VARCHAR(36)  NULL,
    updated_at      DATETIME     NOT NULL,
    modified_by     VARCHAR(36)  NULL,
    CONSTRAINT pk_individual_bonus_rule PRIMARY KEY (uuid)
);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_rule_user
    ON individual_bonus_rule (user_uuid);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_rule_replaces
    ON individual_bonus_rule (replaces);
