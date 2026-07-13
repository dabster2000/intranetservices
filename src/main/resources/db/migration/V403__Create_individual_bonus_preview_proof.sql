-- One-time opaque Preview proofs and lost-response-safe CREATE idempotency.
-- Raw proof tokens are never stored.

CREATE TABLE IF NOT EXISTS individual_bonus_preview_proof (
    token_hash       CHAR(64)     NOT NULL,
    payload_hash     CHAR(64)     NOT NULL,
    action           VARCHAR(32)  NOT NULL,
    actor_uuid       VARCHAR(64)  NOT NULL,
    user_uuid        CHAR(36)     NOT NULL,
    rule_uuid        CHAR(36)     NULL,
    rule_revision    BIGINT       NULL,
    target_type      VARCHAR(32)  NOT NULL,
    target_uuid      CHAR(36)     NULL,
    target_version   BIGINT       NULL,
    idempotency_key  CHAR(36)     NULL,
    issued_at        DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    consumed_at      DATETIME(6)  NULL,
    CONSTRAINT pk_individual_bonus_preview_proof PRIMARY KEY (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_individual_bonus_preview_proof_expiry
    ON individual_bonus_preview_proof (expires_at);

CREATE TABLE IF NOT EXISTS individual_bonus_create_idempotency (
    idempotency_key  CHAR(36)     NOT NULL,
    actor_uuid       VARCHAR(64)  NOT NULL,
    user_uuid        CHAR(36)     NOT NULL,
    payload_hash     CHAR(64)     NOT NULL,
    state            VARCHAR(20)  NOT NULL,
    result_rule_uuid CHAR(36)     NULL,
    created_at       DATETIME(6)  NOT NULL,
    completed_at     DATETIME(6)  NULL,
    CONSTRAINT pk_individual_bonus_create_idempotency PRIMARY KEY (idempotency_key)
);
