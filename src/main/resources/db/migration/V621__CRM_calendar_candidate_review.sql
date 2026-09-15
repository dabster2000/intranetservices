-- Metadata-only candidates let a person review excluded recurring/delivery evidence.
-- No meeting subject, body or private/confidential event is stored here.
CREATE TABLE account_calendar_candidate (
    uuid CHAR(36) NOT NULL,
    client_uuid CHAR(36) NOT NULL,
    user_uuid CHAR(36) NOT NULL,
    graph_event_id VARCHAR(600) NOT NULL,
    event_key CHAR(36) NOT NULL,
    ical_uid VARCHAR(255) NULL,
    occurred_at DATETIME NOT NULL,
    series_master_id VARCHAR(600) NULL,
    reason VARCHAR(24) NOT NULL,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(255) NULL,
    sync_generation BIGINT NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_calendar_candidate_event (client_uuid, event_key),
    KEY idx_calendar_candidate_client (client_uuid, occurred_at),
    KEY idx_calendar_candidate_mailbox_window (user_uuid, occurred_at, sync_generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Decisions are per exact email within an account, so replay does not undo a review.
CREATE TABLE account_calendar_review (
    uuid CHAR(36) NOT NULL,
    client_uuid CHAR(36) NOT NULL,
    email VARCHAR(320) NOT NULL,
    status VARCHAR(16) NOT NULL,
    person_uuid CHAR(36) NULL,
    reviewed_by CHAR(36) NOT NULL,
    reviewed_at DATETIME NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_calendar_review_email (client_uuid, email),
    KEY idx_calendar_review_person (person_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
