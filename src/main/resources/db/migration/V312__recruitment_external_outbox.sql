CREATE TABLE recruitment_external_outbox (
    uuid             VARCHAR(36)  PRIMARY KEY,
    kind             VARCHAR(48)  NOT NULL,
    payload_json     LONGTEXT     NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_retry_at    DATETIME     NOT NULL,
    last_error       TEXT         NULL,
    last_attempt_at  DATETIME     NULL,
    idempotency_key  VARCHAR(160) NOT NULL,
    related_uuid     VARCHAR(36)  NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    UNIQUE KEY uq_recruitment_external_outbox_idempotency (idempotency_key),
    KEY idx_recruitment_external_outbox_drain (status, next_retry_at),
    KEY idx_recruitment_external_outbox_related (related_uuid, kind)
);
