-- Protected append-only audit trail. metadata_json is allow-list-only data.

CREATE TABLE IF NOT EXISTS individual_bonus_audit_event (
    uuid             CHAR(36)     NOT NULL,
    occurred_at      DATETIME(6)  NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    result           VARCHAR(32)  NOT NULL,
    actor_uuid       VARCHAR(64)  NOT NULL,
    user_uuid        CHAR(36)     NULL,
    rule_uuid        CHAR(36)     NULL,
    adjustment_uuid  CHAR(36)     NULL,
    earning_month    DATE         NULL,
    pay_month        DATE         NULL,
    before_hash      CHAR(64)     NULL,
    after_hash       CHAR(64)     NULL,
    proof_action     VARCHAR(32)  NULL,
    correlation_id   VARCHAR(64)  NULL,
    metadata_json    LONGTEXT     NULL,
    CONSTRAINT pk_individual_bonus_audit_event PRIMARY KEY (uuid)
);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_audit_rule_time
    ON individual_bonus_audit_event (rule_uuid, occurred_at);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_audit_adjustment_time
    ON individual_bonus_audit_event (adjustment_uuid, occurred_at);
