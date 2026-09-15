-- Calendar reconciliation uses explicit mailbox generations instead of fractional wall clocks.
-- State starts at recovery_version=0 so each consenting mailbox gets one complete full read
-- after deployment, also replacing old single-account event projection UUIDs.
CREATE TABLE calendar_sync_state (
    user_uuid CHAR(36) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    recovery_version INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(6) NULL,
    last_completed_at DATETIME(6) NULL,
    last_successful_at DATETIME(6) NULL,
    last_full_successful_at DATETIME(6) NULL,
    outcome VARCHAR(24) NOT NULL DEFAULT 'NEVER_SYNCED',
    failure_code VARCHAR(64) NULL,
    meetings_kept INT NOT NULL DEFAULT 0,
    attendees_kept INT NOT NULL DEFAULT 0,
    stale_removed INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- V557 (prod 2026-09-02): a DDL migration that waits on a MariaDB metadata lock hangs the
-- Quarkus boot silently, queues ahead of every new read on the table, and gets the task
-- killed as unhealthy. Fail fast instead. The collation change below forces ALGORITHM=COPY,
-- so this rebuild holds the lock for its whole duration rather than yielding like an
-- online index change would; account_meeting is written by the nightly calendar sync.
SET SESSION lock_wait_timeout = 20;

-- Provider-owned identifiers are case-sensitive, including natural-key legacy lookups.
-- The unique key only widens (user_uuid -> user_uuid, client_uuid) and utf8mb4_bin only
-- makes more values distinct, so no existing row can collide under the new constraint.
ALTER TABLE account_meeting
    MODIFY COLUMN graph_event_id VARCHAR(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN ical_uid VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN sync_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN series_master_id VARCHAR(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN recurring TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN inclusion_reason VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    DROP INDEX uq_account_meeting_event,
    ADD UNIQUE KEY uq_account_meeting_event (graph_event_id, user_uuid, client_uuid),
    ADD KEY idx_account_meeting_generation (user_uuid, sync_generation, occurred_at);
