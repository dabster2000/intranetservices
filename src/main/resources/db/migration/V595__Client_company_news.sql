-- Source-attributed company news for persisted ACTIVE account plans.
-- UTC timestamps; provider secrets and full article bodies are never stored here.
-- No FK onto legacy client, matching V585/V586. Each JSON value is a frozen
-- list of at most six cards; it is read/written as a whole, never queried by member.
CREATE TABLE IF NOT EXISTS client_news_state (
    client_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    last_attempt_at DATETIME(6) NULL,
    last_successful_check_at DATETIME(6) NULL,
    checked_through DATETIME(6) NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NULL,
    attempt_day DATE NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    items_json LONGTEXT NOT NULL DEFAULT '[]',
    failure_code VARCHAR(40) NULL,
    CONSTRAINT chk_client_news_items CHECK (JSON_VALID(items_json)),
    INDEX idx_client_news_lease (lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Atomic, shared request accounting counts retries too, not merely client runs.
CREATE TABLE IF NOT EXISTS client_news_request_budget (
    budget_day DATE NOT NULL PRIMARY KEY,
    requests_used INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
