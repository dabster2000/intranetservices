CREATE TABLE recruitment_graph_subscription (
    uuid                VARCHAR(36)  PRIMARY KEY,
    subscription_id     VARCHAR(160) NOT NULL,
    resource            VARCHAR(255) NOT NULL,
    expires_at          DATETIME     NOT NULL,
    client_state_hmac   VARCHAR(64)  NOT NULL,
    created_at          DATETIME     NOT NULL,
    UNIQUE KEY uq_recruitment_graph_subscription_id (subscription_id),
    KEY idx_recruitment_graph_subscription_expiry (expires_at)
);
