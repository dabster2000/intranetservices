-- Append-only reconciliation revisions and their single per-month head row.

CREATE TABLE IF NOT EXISTS individual_bonus_reconciliation_head (
    rule_uuid               CHAR(36)    NOT NULL,
    earning_month           DATE        NOT NULL,
    latest_revision         INT         NOT NULL DEFAULT 0,
    open_adjustment_uuid    CHAR(36)    NULL,
    version                 BIGINT      NOT NULL DEFAULT 0,
    updated_at              DATETIME(6) NOT NULL,
    CONSTRAINT pk_individual_bonus_reconciliation_head
        PRIMARY KEY (rule_uuid, earning_month)
);

CREATE TABLE IF NOT EXISTS individual_bonus_adjustment (
    uuid                         CHAR(36)      NOT NULL,
    rule_uuid                    CHAR(36)      NOT NULL,
    user_uuid                    CHAR(36)      NOT NULL,
    company_uuid                 CHAR(36)      NULL,
    earning_month                DATE          NOT NULL,
    original_payout_uuid         CHAR(36)      NULL,
    original_source_reference    VARCHAR(255)  NULL,
    revision                     INT           NOT NULL,
    issue_type                   VARCHAR(40)   NOT NULL,
    state                        VARCHAR(40)   NOT NULL,
    direction                    VARCHAR(16)   NULL,
    old_amount                   DECIMAL(15,2) NULL,
    new_amount                   DECIMAL(15,2) NULL,
    delta_amount                 DECIMAL(15,2) NULL,
    pension                      TINYINT(1)    NULL,
    old_snapshot                 LONGTEXT      NULL,
    new_snapshot                 LONGTEXT      NOT NULL,
    new_calculation_fingerprint  CHAR(64)      NOT NULL,
    reconciliation_key           CHAR(64)      NOT NULL,
    pay_month                    DATE          NULL,
    settlement_month             DATE          NULL,
    open_payroll_attested        TINYINT(1)    NULL,
    open_payroll_attested_at     DATETIME(6)   NULL,
    open_payroll_attested_by     VARCHAR(64)   NULL,
    adjustment_source_reference  VARCHAR(100)  NULL,
    salary_lump_sum_uuid         CHAR(36)      NULL,
    external_settlement_ref      VARCHAR(255)  NULL,
    settled_delta_amount         DECIMAL(15,2) NULL,
    settlement_note              VARCHAR(1000) NULL,
    version                      BIGINT        NOT NULL DEFAULT 0,
    detected_at                  DATETIME(6)   NOT NULL,
    detected_by                  VARCHAR(64)   NOT NULL,
    previewed_at                 DATETIME(6)   NULL,
    previewed_by                 VARCHAR(64)   NULL,
    confirmed_at                 DATETIME(6)   NULL,
    confirmed_by                 VARCHAR(64)   NULL,
    settled_at                   DATETIME(6)   NULL,
    settled_by                   VARCHAR(64)   NULL,
    last_attempt_at              DATETIME(6)   NOT NULL,
    attempt_count                INT           NOT NULL DEFAULT 1,
    created_at                   DATETIME(6)   NOT NULL,
    updated_at                   DATETIME(6)   NOT NULL,
    CONSTRAINT pk_individual_bonus_adjustment PRIMARY KEY (uuid),
    CONSTRAINT uk_individual_bonus_adjustment_reconciliation UNIQUE (reconciliation_key),
    CONSTRAINT uk_individual_bonus_adjustment_source UNIQUE (adjustment_source_reference),
    CONSTRAINT uk_individual_bonus_adjustment_revision UNIQUE (rule_uuid, earning_month, revision)
);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_adjustment_state
    ON individual_bonus_adjustment (state, detected_at);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_adjustment_user_earning
    ON individual_bonus_adjustment (user_uuid, earning_month);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_adjustment_rule_revision
    ON individual_bonus_adjustment (rule_uuid, earning_month, revision);
