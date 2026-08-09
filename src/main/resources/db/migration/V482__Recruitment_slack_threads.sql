-- V482: Candidate DISCUSSION Slack threads.
--
-- One root message per candidate in the recruitment Slack channel; note
-- notifications land as replies in that thread (author + candidate +
-- deep link, never the note body). This table remembers the thread so
-- repeat notes keep the channel tidy. Confidential candidates (partner
-- track / sponsored) never get a row — no channel posts for them.
--
-- NOT recruitment_slack_threads: that table already exists (P22) and
-- holds the per-APPLICATION living root cards maintained by
-- SlackCardReactor. Discussions are per CANDIDATE and independent of the
-- card lifecycle, so they get their own table.

CREATE TABLE recruitment_discussion_threads (
    uuid           VARCHAR(36) NOT NULL,
    candidate_uuid VARCHAR(36) NOT NULL,
    channel_id     VARCHAR(30) NOT NULL,
    root_ts        VARCHAR(30) NOT NULL
        COMMENT 'Slack message ts of the per-candidate root message',

    -- Audit columns (house Auditable pattern, V421)
    created_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL COMMENT 'users.uuid from X-Requested-By (SYSTEM for notifier writes)',
    modified_by VARCHAR(36) NULL COMMENT 'users.uuid from X-Requested-By',

    PRIMARY KEY (uuid),
    UNIQUE KEY uq_rdt_candidate_channel (candidate_uuid, channel_id),
    CONSTRAINT fk_rdt_candidate
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates (uuid) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Per-candidate Slack discussion threads (root message bookkeeping)';
