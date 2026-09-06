-- JK Team 2.0 / WP1 — weekly "declare your coming weeks" reminder claim.
--
-- The Monday reminder job runs on every ECS task. UNIQUE (useruuid, week_key)
-- makes exactly one task the sender for a given person and ISO week, the same
-- shape as expense_employee_digest_claim (V541). Rows are a send ledger, not
-- state: nothing reads them back except the claim itself.
CREATE TABLE IF NOT EXISTS declared_availability_reminder_claim (
    uuid        VARCHAR(36)  NOT NULL,
    useruuid    VARCHAR(36)  NOT NULL,
    week_key    VARCHAR(8)   NOT NULL,
    claimed_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_darc_user_week (useruuid, week_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
