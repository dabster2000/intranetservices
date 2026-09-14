-- ============================================================================
-- V602: Slack source channels — the client news that lives in the general channels
-- ============================================================================
-- Feature: docs/specs/crm-slack-source-channels-2026-09-14.md §5, extending the
--          account-space digest lane (V594) to channels that are about nothing
--          in particular.
-- Domain:  aggregates/crm/slack + aggregates/crm/account
--
-- WHAT THIS IS
--   V594 gave the timeline's SLACK lane a source, but only for an account that
--   HAS a channel of its own: an a_* space is about one client, so a day of it
--   is summarised whole. Most of what a partner would actually want to read is
--   not written there. It is written in #ledelse and its kind — leadership
--   chatter, a holiday photo, a colleague in A&E, and in the middle of it two
--   sentences about a meeting at one client and a contract at another. This
--   migration is the storage for a lane that reads those channels every night
--   and keeps only the sentences that belong on an account: an admin lists the
--   channels (table 1), every (client, channel, day) the model can evidence
--   becomes one row on that account's timeline (tables 2 and 3), every company
--   it names that matches no client floats up as a prospect hint beside "Seen in
--   calendars" (tables 4, 5 and 6), and each run says what it did (table 7).
--
-- THERE IS NO MESSAGE TEXT COLUMN, AND THAT IS STILL THE POINT
--   V588 said it about meeting subjects and V594 about channel days; spec D11
--   says it again here, and it is structural rather than something to remember.
--   A general channel is worse than an a_* one in exactly the way that matters:
--   the day's lines are mostly about colleagues, not clients. What lands here is
--   the model's STRUCTURED reading of the handful of lines that named a client —
--   headline, decisions, next steps, risks, client asks, topics — after the
--   backend has re-validated and capped it, plus the uuids of the colleagues who
--   wrote those lines and a count each. Nothing a colleague wrote about a
--   colleague can reach any column below, because no column below can hold a
--   sentence. The lines themselves exist in memory for one model call and are
--   gone when it returns.
--
-- WHY ITS OWN LANE AND NOT MORE account_slack_digest ROWS
--   A digest row is keyed (client, day) and means "this is what the client's own
--   channel said". A mention is keyed (client, channel, day) because three
--   general channels can each say something about the same client on the same
--   day and none of them is that client's channel. Widening the digest's unique
--   key would have rewritten the account-space lane's idempotency for a feature
--   that is not the account space. The reading SHAPE is shared instead —
--   SlackDigestContent in digest_json — so one FE renderer draws both.
--
-- WHY THE CURSOR LIVES ON THE CHANNEL AND NOT ON THE ROWS
--   The account-space lane can take its cursor from the newest digest row,
--   because a linked channel-day that was read always produces one. Here a day
--   read in full may — correctly — produce no row at all: nobody named a client.
--   A cursor derived from the produced rows would re-read that day for ever, so
--   crm_slack_source_channel.cursor_ts carries it, advanced in the same
--   transaction as the day's rows so a run killed mid-way resumes at the first
--   day it did not finish.
--
-- WHY THE HINTS ARE THREE TABLES AND NOT ONE
--   V601's reasoning carries over unchanged for the first two: the aggregate row
--   is what the panel shows and what somebody decides on, and the sighting
--   ledger is what those aggregates are RECOMPUTED from, never incremented,
--   because the lane re-reads days and a counter would count the same sentence
--   fourteen times. The third table is new. The panel promises "N colleagues
--   have mentioned them", and a distinct-colleague count cannot be derived from
--   a ledger keyed (name, channel, day) that carries only a number; the calendar
--   lane gets that for free because its ledger row IS a mailbox, while a Slack
--   day has many authors. So the authors are rows, and people_count is a
--   count(distinct user_uuid) over them.
--
-- NO FOREIGN KEYS onto client / user, matching V585-V601: client_uuid and
--   user_uuid are plain identifiers, so a client the CRM deletes cannot block a
--   sync. Inside the feature there are two real FKs, both ON DELETE CASCADE and
--   both for V593's reason — the child is meaningless without its parent, both
--   rows are written by the same service in the same transaction, and the
--   retention purge (still not built) must not have to remember to sweep two
--   tables by hand: account_slack_mention_participant -> account_slack_mention,
--   and slack_unmatched_company_sighting_author -> the sighting it belongs to.
--
-- STAGING SYNC
--   account_slack_mention and account_slack_mention_participant join
--   account_slack_digest, account_signal, account_meeting, client_note and the
--   TrustLink tables on the sp_sync_prod_to_staging exclusion list below. The
--   model's reading of what was said about a client, and the record of which
--   colleagues said it, is third-party-adjacent whichever channel it came out
--   of. MySQL cannot patch a stored procedure, so the whole body is re-emitted:
--   this is a VERBATIM copy of the definition from V596 — the NEWEST one, and
--   the one to copy; V597 through V601 define the procedure nowhere — with two
--   names added to the NOT IN list. If you re-emit this proc again, copy from
--   the newest migration that defines it, never from an older one, or you
--   silently revert later exclusions.
--
--   crm_slack_source_channel, slack_unmatched_company, its sighting ledger, the
--   sighting authors and crm_slack_sync_run are deliberately NOT excluded, as
--   V596 argued for the TrustLink alias tables: a channel list, a company name
--   nobody has claimed yet, colleague uuids with counts and a run log are
--   configuration and bookkeeping, and staging is materially easier to work on
--   with them present. The consequence is worth writing down: the nightly sync
--   therefore re-seeds staging's feature flag and channel list to whatever
--   production holds, and staging's Slack token is byte-identical to
--   production's, so a channel listed in prod is read twice a night and its
--   content sent to the model twice.
--
-- RETENTION — THE OPEN GAP, EXTENDED AGAIN
--   Spec §3.4/§3.7 promise 24 months after the account's last activity, after
--   which person data is anonymised. The purge job is still NOT built — V588,
--   V590, V593, V594 and V596 each record the same gap — and this migration adds
--   a FIFTH table family to the sweep it will have to do: account_slack_mention,
--   whose participants the cascade takes along. The hints are the one thing here
--   that does purge on its own: sightings older than 12 months are deleted at
--   the end of every run, exactly as the calendar ledger does (V601), and the
--   author rows follow them through the cascade.
--
-- FEATURE FLAG
--   crm.slack.source-channels.enabled, seeded 'false', beside V594's
--   crm.slack.account-spaces.enabled. Every Slack feature in this repo is opt-in
--   (V444's posture). Flag off ⇒ the job is a no-op and not one channel is read.
--
-- SETTINGS → CRM
--   A page_registry row in section SETTINGS at display_order 180 — the highest
--   existing SETTINGS row is settings-companies at 170 (V544) — ADMIN by role
--   and admin:read by permission, as every other admin tab. required_permission
--   is spelled out INLINE in that INSERT, which is a new idiom and worth one
--   line: V320 and V544 omit the column, V529 passes NULL and V467 back-filled
--   the values by UPDATE, so a reader who knows those files would otherwise take
--   this row for one V467 missed. It is not; the value belongs with the row.
--   The ?tab=crm suffix on the route is decorative — settings/_client.tsx reads
--   no search params — and is kept only because all seventeen existing SETTINGS
--   rows carry one.
--
-- RESERVED-WORD CHECK (MariaDB 10.x, and this is written down because of the
--   V534 `lines` incident): the check was run over every identifier below. None
--   of lane, readings, unmatched, cursor_ts, sighted_on, name_key, display_name,
--   channels, channels_read, days, failures, failure_code, link_error,
--   link_errors, permalink, mention_date, mention_count, mentions_90d,
--   author_count, sighting_uuid or is_private is reserved. TRIGGER **is**
--   reserved, which is why the column saying whether a run was the cron or a
--   person is trigger_kind. status, enabled and first_seen were checked when
--   V596 and V601 took them and are still fine.
--
-- COLLATION: utf8mb4_general_ci, as V585-V601 (client_uuid / user_uuid join
--   general_ci tables; a unicode_ci mix fails at runtime with ERROR 1267).
--
-- Idempotency: CREATE TABLE IF NOT EXISTS and INSERT ... ON DUPLICATE KEY UPDATE.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   DROP TABLE account_slack_mention_participant, account_slack_mention,
--              slack_unmatched_company_sighting_author,
--              slack_unmatched_company_sighting, slack_unmatched_company,
--              crm_slack_source_channel, crm_slack_sync_run;
--   DELETE FROM app_settings WHERE setting_key = 'crm.slack.source-channels.enabled';
--   DELETE FROM page_registry WHERE page_key = 'settings-crm';
--   Re-emit sp_sync_prod_to_staging from V596 to drop the two exclusions.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The channels an admin listed, and how far each one has been read.
--    The cursor lives here and not on the produced rows because a day may —
--    correctly — produce no row at all; see the header.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS crm_slack_source_channel
(
    uuid         CHAR(36)    NOT NULL,
    channel_id   VARCHAR(32) NOT NULL COMMENT 'Slack id, resolved on save via conversations.info',
    channel_name VARCHAR(80) NOT NULL COMMENT 'As Slack spells it, no #; refreshed on every successful read',
    is_private   TINYINT(1)  NOT NULL DEFAULT 0,
    enabled      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '0 pauses the channel and keeps its cursor',
    link_error   VARCHAR(40) NULL COMMENT 'NOT_FOUND | NOT_IN_CHANNEL | ARCHIVED; null when healthy',
    cursor_ts    VARCHAR(32) NULL COMMENT 'Slack ts of the last message of the last complete day read; null until the first run',
    synced_at    DATETIME    NULL,
    created_at   DATETIME    NOT NULL,
    created_by   CHAR(36)    NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_crm_slack_source_channel (channel_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='General Slack channels the CRM reads for client news (spec §5.1)';

-- ----------------------------------------------------------------------------
-- 2. One day of one general channel, as it concerns ONE client.
--    NO MESSAGE TEXT COLUMN EXISTS — see header.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_slack_mention
(
    uuid           CHAR(36)     NOT NULL COMMENT 'Deterministic from (client_uuid, channel_id, mention_date)',
    client_uuid    CHAR(36)     NOT NULL,
    channel_id     VARCHAR(32)  NOT NULL,
    channel_name   VARCHAR(80)  NOT NULL COMMENT 'Denormalised: a rename or a removed channel does not rewrite history',
    mention_date   DATE         NOT NULL,
    message_count  INT          NOT NULL COMMENT 'Distinct cited messages about THIS client, not the channel-day total',
    relevance      VARCHAR(10)  NOT NULL COMMENT 'LOW | HIGH — a stored row is never NONE',
    headline       VARCHAR(200) NOT NULL,
    digest_json    TEXT         NOT NULL COMMENT 'SlackDigestContent, validated and capped by the backend',
    permalink      VARCHAR(500) NULL COMMENT 'Deep link to the first cited message',
    model          VARCHAR(60)  NULL,
    prompt_version VARCHAR(60)  NULL,
    dismissed_by   CHAR(36)     NULL COMMENT '"Not about this client": hidden from the feed and the graph, kept for audit',
    dismissed_at   DATETIME     NULL,
    synced_at      DATETIME     NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_account_slack_mention (client_uuid, channel_id, mention_date),
    KEY idx_account_slack_mention_client (client_uuid, mention_date),
    KEY idx_account_slack_mention_channel (channel_id, mention_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Per-day reading of what a general Slack channel said about one client. NO MESSAGE TEXT COLUMN EXISTS.';

-- ----------------------------------------------------------------------------
-- 3. Which colleagues wrote the cited lines. A set is a table in this schema,
--    never a delimited string (V593), and the graph joins it to draw HEARD.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_slack_mention_participant
(
    uuid          CHAR(36) NOT NULL,
    mention_uuid  CHAR(36) NOT NULL,
    user_uuid     CHAR(36) NOT NULL COMMENT 'Soft reference to user, as every table in this schema',
    message_count INT      NOT NULL COMMENT 'Cited lines this colleague wrote',
    PRIMARY KEY (uuid),
    KEY idx_account_slack_mention_participant (mention_uuid),
    CONSTRAINT fk_account_slack_mention_participant
        FOREIGN KEY (mention_uuid) REFERENCES account_slack_mention (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Trustworks people whose lines a mention row cites';

-- ----------------------------------------------------------------------------
-- 4. A company the model named that matches no client of ours: the row the
--    hints panel shows and the decision somebody took on it. The aggregates are
--    recomputed from the ledger in table 5 on every run, never incremented.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS slack_unmatched_company
(
    name_key           VARCHAR(190)                    NOT NULL COMMENT 'Lower-cased, whitespace-collapsed company name as the model wrote it',
    display_name       VARCHAR(190)                    NOT NULL COMMENT 'As first seen — a company name, never a person',
    mentions_90d       INT                             NOT NULL DEFAULT 0,
    mentions_total     INT                             NOT NULL DEFAULT 0,
    channels_count     INT                             NOT NULL DEFAULT 0,
    people_count       INT                             NOT NULL DEFAULT 0 COMMENT 'Distinct colleagues who wrote about it',
    first_seen         DATE                            NULL,
    last_seen          DATE                            NULL,
    status             ENUM ('NEW','IGNORED','LINKED') NOT NULL DEFAULT 'NEW',
    linked_client_uuid CHAR(36)                        NULL COMMENT 'Set by LINK and by ADD (the created prospect): the row is then an alias the matcher carries',
    decided_by         CHAR(36)                        NULL,
    decided_at         DATETIME                        NULL,
    created_at         DATETIME                        NOT NULL,
    updated_at         DATETIME                        NOT NULL COMMENT 'NOT NULL with no mirror in calendar_unmatched_domain: the recompute has to set it explicitly or the write fails',
    PRIMARY KEY (name_key),
    KEY idx_slack_unmatched_company_status (status, mentions_90d)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Companies heard in Slack that match no client (spec §5.3) — a name and counts, never text';

-- ----------------------------------------------------------------------------
-- 5. The ledger those counts are derived from. One row per (name, channel, day),
--    so a re-read overwrites instead of adding. Purged at 12 months.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS slack_unmatched_company_sighting
(
    uuid          CHAR(36)     NOT NULL COMMENT 'Deterministic from (name_key, channel_id, sighted_on) so a re-read overwrites',
    name_key      VARCHAR(190) NOT NULL,
    channel_id    VARCHAR(32)  NOT NULL,
    sighted_on    DATE         NOT NULL,
    mention_count INT          NOT NULL,
    author_count  INT          NOT NULL,
    permalink     VARCHAR(500) NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_slack_unmatched_company_sighting (name_key, channel_id, sighted_on),
    KEY idx_slack_unmatched_company_sighting_date (sighted_on)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Idempotency ledger for slack_unmatched_company — counts and one link, no text';

-- ----------------------------------------------------------------------------
-- 6. Who wrote the lines a sighting counted. author_count on the ledger row is
--    per (name, channel, day) and cannot answer "how many colleagues have
--    mentioned them" across days and channels; a distinct count over these rows
--    can, and that is what the panel's people_count is. The FK cascades for
--    V593's reason, so the 12-month sighting purge sweeps these with it.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS slack_unmatched_company_sighting_author
(
    uuid          CHAR(36) NOT NULL,
    sighting_uuid CHAR(36) NOT NULL,
    user_uuid     CHAR(36) NOT NULL COMMENT 'The colleague who wrote a cited line; soft reference to user, as every table in this schema',
    PRIMARY KEY (uuid),
    KEY idx_slack_unmatched_company_sighting_author (sighting_uuid),
    CONSTRAINT fk_slack_unmatched_company_sighting_author
        FOREIGN KEY (sighting_uuid) REFERENCES slack_unmatched_company_sighting (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Colleagues behind an unmatched-company sighting — uuids and nothing else';

-- ----------------------------------------------------------------------------
-- 7. What each run did, for both Slack lanes. Until now the 02:25 cron was only
--    observable in CloudWatch; the Settings tab reads these rows instead.
--    Rows older than 90 days are deleted at the end of each run.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS crm_slack_sync_run
(
    uuid          CHAR(36)    NOT NULL,
    lane          VARCHAR(20) NOT NULL COMMENT 'ACCOUNT_SPACES | SOURCE_CHANNELS',
    trigger_kind  VARCHAR(10) NOT NULL COMMENT 'SCHEDULED | MANUAL',
    started_by    CHAR(36)    NULL COMMENT 'MANUAL only',
    started_at    DATETIME    NOT NULL,
    finished_at   DATETIME    NULL,
    status        VARCHAR(10) NOT NULL COMMENT 'RUNNING | DONE | STOPPED | FAILED',
    channels      INT         NOT NULL DEFAULT 0,
    channels_read INT         NOT NULL DEFAULT 0,
    days          INT         NOT NULL DEFAULT 0,
    readings      INT         NOT NULL DEFAULT 0 COMMENT 'Digest rows (account spaces) or mention rows (source channels) written',
    unmatched     INT         NOT NULL DEFAULT 0,
    link_errors   INT         NOT NULL DEFAULT 0,
    failures      INT         NOT NULL DEFAULT 0,
    failure_code  VARCHAR(40) NULL COMMENT 'A CODE only, never an upstream body',
    PRIMARY KEY (uuid),
    KEY idx_crm_slack_sync_run_lane (lane, started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Run bookkeeping for both CRM Slack lanes (spec §5.4)';

-- ----------------------------------------------------------------------------
-- 8. The switch. Off until somebody turns it on — every Slack feature is opt-in.
-- ----------------------------------------------------------------------------
INSERT INTO app_settings (setting_key, setting_value, category)
VALUES ('crm.slack.source-channels.enabled', 'false', 'crm')
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

-- ----------------------------------------------------------------------------
-- 9. Settings → CRM: the home the CRM's Slack settings never had. ADMIN only.
--    required_permission is inline on purpose — see the header before reading
--    this row as one V467 missed. display_order 180 follows settings-companies
--    (170, V544), the highest SETTINGS row until now.
-- ----------------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission,
     display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-crm', 'CRM', 1, '/settings?tab=crm', 'ADMIN', 'admin:read', 180, 'SETTINGS',
     'MessageSquare', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label          = VALUES(page_label),
    is_visible          = VALUES(is_visible),
    react_route         = VALUES(react_route),
    required_roles      = VALUES(required_roles),
    required_permission = VALUES(required_permission),
    display_order       = VALUES(display_order),
    section             = VALUES(section),
    icon_name           = VALUES(icon_name);

-- ----------------------------------------------------------------------------
-- 10. Staging sync — the two mention tables must never be copied
--
--     VERBATIM re-emission of sp_sync_prod_to_staging as defined in V596 (the
--     newest definition; V597-V601 define it nowhere), with two names added to
--     the NOT IN list. MySQL cannot patch a stored procedure, so the whole body
--     has to come along. If you re-emit this proc again, copy from the newest
--     migration that defines it, never from an older one, or you silently revert
--     later exclusions. See the STAGING SYNC note in the header before touching
--     this.
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_sync_prod_to_staging;

DELIMITER $$

CREATE PROCEDURE sp_sync_prod_to_staging()
BEGIN
    DECLARE v_table_name VARCHAR(255);
    DECLARE v_view_name VARCHAR(255);
    DECLARE v_view_def LONGTEXT;
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_bad_expense_rows INT DEFAULT 0;
    DECLARE v_col_list LONGTEXT;

    -- Cursor: all base tables except environment-specific tables
    DECLARE cur_tables CURSOR FOR
        SELECT TABLE_NAME
        FROM INFORMATION_SCHEMA.TABLES
        WHERE TABLE_SCHEMA = 'twservices4'
          AND TABLE_TYPE = 'BASE TABLE'
          AND TABLE_NAME NOT IN (
              'flyway_schema_history',
              'integration_keys',
              'api_clients',
              'api_client_scopes',
              'api_client_audit_log',
              'bug_reports',
              'bug_report_comments',
              'bug_report_notifications',
              'autofix_tasks',
              'autofix_config',
              'autofix_model_catalog',        -- V565
              'individual_bonus_rule',
              'individual_bonus_payout',
              'individual_bonus_preview_proof',
              'individual_bonus_create_idempotency',
              'individual_bonus_reconciliation_head',
              'individual_bonus_adjustment',
              'individual_bonus_audit_event',
              'danlon_assignment_proposal',
              'danlon_number_sequence',
              -- ----------------------------------------------------------------
              -- Recruitment / ATS: candidate PII, GDPR-governed, NOT anonymized
              -- in Phase 2 -> must never be copied from prod to staging.
              -- EXTEND this block for every new recruitment_* / candidate_* table.
              -- ----------------------------------------------------------------
              'candidate_dossiers',
              'candidate_dossier_revisions',
              'candidate_dossier_appendices',
              'recruitment_candidates',
              'recruitment_positions',
              'recruitment_circle_members',
              'recruitment_applications',
              'recruitment_application_answers',
              'recruitment_consents',
              'recruitment_referrals',
              'recruitment_interviews',
              'recruitment_scorecards',
              'recruitment_events',
              'recruitment_reactor_offsets',
              'recruitment_reactor_deliveries',
              'recruitment_reactor_dead_letters', -- V490
              'recruitment_scheduling_request',   -- V498
              'recruitment_proposed_slot',        -- V498
              'recruitment_slot_approval',        -- V498
              'recruitment_calendar_hold',        -- V498
              'recruitment_option_batch',         -- V498
              'recruitment_scheduling_outbox',    -- V498
              'recruitment_availability_evidence',   -- V500
              'recruitment_availability_constraint', -- V500
              'recruitment_signing_completed_cases',
              'recruitment_slack_inbound_dedupe',
              'recruitment_email_templates',
              'recruitment_pending_emails',
              'recruitment_fact_monthly',
              'recruitment_slack_threads',
              'recruitment_slack_channels',
              'recruitment_candidate_deletions',  -- V515
              -- ----------------------------------------------------------------
              -- Employee documents (V452): HR document metadata + GDPR audit
              -- trail. Staging rows would reference prod bucket keys and leak
              -- HR metadata -> staging keeps its own synthetic data only.
              -- EXTEND this block for every new employee_document* /
              -- sharepoint_migration_* table.
              -- ----------------------------------------------------------------
              'employee_documents',
              'employee_document_audit',
              -- V457: Phase-2a migration working tables. Folder/file names of
              -- HR documents + user mappings = personal data; staging runs its
              -- own rehearsal rows which the nightly refresh must not clobber.
              'sharepoint_migration_folders',
              'sharepoint_migration_items',
              -- ----------------------------------------------------------------
              -- V584: Account signals ("Heard something?", CRM spec §3.4).
              -- Free text naming real people at client organisations, their
              -- roles, and how a named colleague knows them — third-party
              -- personal data nobody consented to, exactly the ATS posture.
              -- Staging keeps its own synthetic captures; prod names must
              -- never be copied into an environment with wider access.
              -- ----------------------------------------------------------------
              'account_signal',
              -- ----------------------------------------------------------------
              -- V593: the colleagues a capture named. Only Trustworks user
              -- uuids, so no third-party name sits in this table itself -- but
              -- every row is a child of an account_signal row that IS excluded
              -- above, so copying it alone would leave staging holding orphans
              -- that still say "somebody filed a signal naming these
              -- colleagues". Excluded for the same reason, and in the same
              -- breath, as its parent.
              -- ----------------------------------------------------------------
              'account_signal_colleague',
              -- ----------------------------------------------------------------
              -- V588: Calendar metadata. account_meeting carries the date,
              -- duration and Trustworks attendee of a real meeting;
              -- account_meeting_attendee carries the NAME and E-MAIL ADDRESS of
              -- a person at a client organisation. Same posture and the same
              -- reason as account_signal above: third-party personal data
              -- nobody consented to must never be copied into an environment
              -- with wider access. user_calendar_consent is an employee's own
              -- consent decision and is environment-local -- copying prod
              -- consent into staging would have staging read mailboxes on the
              -- strength of a decision somebody took about production.
              -- ----------------------------------------------------------------
              'account_meeting',
              'account_meeting_attendee',
              'user_calendar_consent',
              -- ----------------------------------------------------------------
              -- V594: Account Slack digests. The model's structured reading of a
              -- day in a client channel -- decisions, risks, client-side people
              -- named -- plus which employees were talking about a named client.
              -- A paraphrase, not message text, but third-party-adjacent all the
              -- same, and the same posture as account_signal and client_note:
              -- never copied into an environment with wider access.
              -- ----------------------------------------------------------------
              'account_slack_digest',
              'account_slack_digest_participant',
              -- ----------------------------------------------------------------
              -- V602: Slack source channels. account_slack_mention is the same
              -- kind of reading as account_slack_digest above -- the model's
              -- structured take on what was said about a named client -- only
              -- picked out of a general channel instead of the client's own,
              -- and account_slack_mention_participant records which colleagues
              -- wrote about which client. Same posture and the same reason as
              -- account_signal and account_slack_digest: third-party-adjacent
              -- data nobody consented to must never be copied into an
              -- environment with wider access. The channel list, the unmatched
              -- company hints with their sighting ledger and its authors, and
              -- the run log are configuration and bookkeeping and ARE copied,
              -- on purpose, exactly as the TrustLink alias tables are.
              -- ----------------------------------------------------------------
              'account_slack_mention',
              'account_slack_mention_participant',
              -- ----------------------------------------------------------------
              -- V590: Client notes (CRM spec §4.3). One hand-typed line on an
              -- account. Same posture and the same reason as account_signal and
              -- account_meeting_attendee above -- third-party personal data
              -- nobody consented to must never be copied into an environment
              -- with wider access -- and worse in one way: a signal's name sits
              -- in a structured person_name column, a note's name is inside free
              -- text with no structure at all, so there is nothing to redact on
              -- the way across. Staging keeps whatever notes staging users type.
              -- ----------------------------------------------------------------
              'client_note',
              -- ----------------------------------------------------------------
              -- V596: TrustLink connections. trustlink_connection mirrors the
              -- NAME, JOB TITLE and LINKEDIN URL of a person at a client
              -- organisation; trustlink_connection_trustworker records which
              -- employee has known them and since when. Same posture and the
              -- same reason as account_signal and account_meeting_attendee
              -- above -- third-party personal data nobody consented to must
              -- never be copied into an environment with wider access -- and
              -- bigger than either: one sync mirrors well over a thousand
              -- named external people in one go. Staging builds its own from
              -- TrustLink when the feature flag is on there. The alias,
              -- override and state tables are configuration, not personal
              -- data, and ARE copied, on purpose.
              -- ----------------------------------------------------------------
              'trustlink_connection',
              'trustlink_connection_trustworker',
              -- ----------------------------------------------------------------
              -- V466: Authorization catalogue (Phase 4). Bindings become
              -- UI-managed in Phase 7 and authz_version/authz_audit are
              -- environment-local counters/trails; the nightly refresh must
              -- not clobber them with prod state.
              -- ----------------------------------------------------------------
              'permission',
              'role_permission',
              'authz_version',
              'authz_audit'
          );

    -- Cursor: all views
    DECLARE cur_views CURSOR FOR
        SELECT TABLE_NAME, VIEW_DEFINITION
        FROM INFORMATION_SCHEMA.VIEWS
        WHERE TABLE_SCHEMA = 'twservices4';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    -- Default GROUP_CONCAT() truncates at 1024 bytes. Wide tables have many
    -- columns; truncated column list = corrupt INSERT statement. Bump it.
    SET SESSION group_concat_max_len = 1048576;

    -- =========================================================================
    -- PHASE 1: Generic table copy (schema-change resilient + generated-column safe)
    -- =========================================================================
    SET @old_fk = @@FOREIGN_KEY_CHECKS;
    SET FOREIGN_KEY_CHECKS = 0;

    OPEN cur_tables;
    table_loop: LOOP
        FETCH cur_tables INTO v_table_name;
        IF v_done THEN
            LEAVE table_loop;
        END IF;

        SET @sql_drop = CONCAT('DROP TABLE IF EXISTS `twservices4-staging`.`', v_table_name, '`');
        PREPARE stmt FROM @sql_drop;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        SET @sql_create = CONCAT('CREATE TABLE `twservices4-staging`.`', v_table_name,
                                 '` LIKE `twservices4`.`', v_table_name, '`');
        PREPARE stmt FROM @sql_create;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        -- Build an explicit column list excluding STORED/VIRTUAL GENERATED
        -- columns. INSERTing into a generated column raises ERROR 1906 under
        -- STRICT_TRANS_TABLES, which is the procedure's own SQL_MODE.
        SELECT GROUP_CONCAT(CONCAT('`', COLUMN_NAME, '`')
                            ORDER BY ORDINAL_POSITION SEPARATOR ', ')
          INTO v_col_list
          FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = 'twservices4'
           AND TABLE_NAME = v_table_name
           AND (GENERATION_EXPRESSION IS NULL OR GENERATION_EXPRESSION = '');

        SET @sql_insert = CONCAT('INSERT INTO `twservices4-staging`.`', v_table_name,
                                 '` (', v_col_list, ') SELECT ', v_col_list,
                                 ' FROM `twservices4`.`', v_table_name, '`');
        PREPARE stmt FROM @sql_insert;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur_tables;

    -- =========================================================================
    -- PHASE 2: Anonymize PII and sensitive data in staging
    -- =========================================================================

    -- ---- user ----
    -- NOTE: Excludes admin user so Azure AD login works in staging
    UPDATE `twservices4-staging`.`user` SET
        firstname     = CONCAT('First', LEFT(MD5(uuid), 6)),
        lastname      = CONCAT('Last', LEFT(MD5(CONCAT(uuid, 'ln')), 6)),
        email         = CONCAT(LEFT(MD5(uuid), 8), '@example.com'),
        phone         = CONCAT('+45 ', LPAD(FLOOR(RAND(CRC32(uuid)) * 90000000 + 10000000), 8, '0')),
        cpr           = CONCAT(LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'cpr'))) * 28 + 1), 2, '0'),
                               LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'cpr2'))) * 12 + 1), 2, '0'),
                               LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'cpr3'))) * 90 + 10), 2, '0'),
                               '-', LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'cpr4'))) * 9000 + 1000), 4, '0')),
        password      = '$2a$10$INVALIDHASH.NOLOGIN.STAGING.000000000000000000000',
        birthday      = DATE_ADD('1980-01-01', INTERVAL FLOOR(RAND(CRC32(CONCAT(uuid,'bday'))) * 7300) DAY),
        username      = CONCAT('user_', LEFT(MD5(uuid), 8)),
        slackusername = NULL,
        azure_oid     = NULL,
        azure_issuer  = NULL,
        pensiondetails = 'Redacted',
        defects       = 'Redacted',
        other         = 'Redacted'
    WHERE uuid != '7948c5e8-162c-4053-b905-0f59a21d7746';

    -- ---- user_bank_info ----
    UPDATE `twservices4-staging`.`user_bank_info` SET
        fullname   = CONCAT('First', LEFT(MD5(useruuid), 6), ' Last', LEFT(MD5(CONCAT(useruuid, 'ln')), 6)),
        regnr      = LPAD(FLOOR(RAND(CRC32(uuid)) * 9000 + 1000), 4, '0'),
        account_nr = LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'acc'))) * 9000000000 + 1000000000), 10, '0'),
        iban       = CONCAT('DK00', LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'iban'))) * 99999999999999), 14, '0')),
        bic_swift  = 'XXXXDKKK';

    -- ---- user_contactinfo ----
    UPDATE `twservices4-staging`.`user_contactinfo` SET
        street        = CONCAT('Fakegade ', FLOOR(RAND(CRC32(uuid)) * 200 + 1)),
        postalcode    = LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'pc'))) * 8000 + 1000), 4, '0'),
        city          = ELT(FLOOR(RAND(CRC32(CONCAT(uuid,'city'))) * 8) + 1,
                            'Copenhagen', 'Aarhus', 'Odense', 'Aalborg',
                            'Esbjerg', 'Randers', 'Kolding', 'Horsens'),
        phone         = CONCAT('+45 ', LPAD(FLOOR(RAND(CRC32(CONCAT(uuid,'ph'))) * 90000000 + 10000000), 8, '0')),
        slackusername = NULL;

    -- ---- user_personal_details ----
    UPDATE `twservices4-staging`.`user_personal_details` SET
        pensiondetails = 'Redacted',
        defects        = NULL,
        other          = NULL;

    -- ---- user_danlon_history ----
    UPDATE `twservices4-staging`.`user_danlon_history` SET
        danlon = CONCAT('DAN', LPAD(FLOOR(RAND(CRC32(uuid)) * 90000 + 10000), 5, '0'));

    -- ---- user_ext_account ----
    UPDATE `twservices4-staging`.`user_ext_account` SET
        economics = LPAD(FLOOR(RAND(CRC32(useruuid)) * 90000 + 10000), 5, '0'),
        username  = CONCAT('ext_', LEFT(MD5(useruuid), 8));

    -- ---- salary ----
    UPDATE `twservices4-staging`.`salary` SET
        salary = FLOOR(RAND(CRC32(CONCAT(uuid,'sal'))) * 30000 + 25000);

    -- ---- salary_supplement ----
    UPDATE `twservices4-staging`.`salary_supplement` SET
        value = FLOOR(RAND(CRC32(CONCAT(uuid,'sup'))) * 4500 + 500);

    -- ---- salary_lump_sum ----
    UPDATE `twservices4-staging`.`salary_lump_sum` SET
        lump_sum = FLOOR(RAND(CRC32(CONCAT(uuid,'lump'))) * 14000 + 1000);

    -- ---- user_pension ----
    UPDATE `twservices4-staging`.`user_pension` SET
        pension_own     = ROUND(RAND(CRC32(CONCAT(uuid,'po'))) * 6 + 2, 1),
        pension_company = ROUND(RAND(CRC32(CONCAT(uuid,'pc'))) * 8 + 4, 1);

    -- ---- conference_participants ----
    UPDATE `twservices4-staging`.`conference_participants` SET
        name    = CONCAT('Participant ', LEFT(MD5(uuid), 6)),
        email   = CONCAT(LEFT(MD5(uuid), 8), '@example.com'),
        company = CONCAT('Company ', LEFT(MD5(CONCAT(uuid, 'co')), 4)),
        titel   = 'Attendee';

    -- ---- guest_registration ----
    UPDATE `twservices4-staging`.`guest_registration` SET
        guest_name    = CONCAT('Guest ', LEFT(MD5(uuid), 6)),
        employee_name = CONCAT('Host ', LEFT(MD5(CONCAT(uuid, 'emp')), 6));

    -- ---- sales_lead ----
    UPDATE `twservices4-staging`.`sales_lead` SET
        contactinformation = CONCAT(LEFT(MD5(uuid), 8), '@example.com');

    -- ---- clientdata: dropped from prod by V293 (2026-04-19). UPDATE removed
    --      in V306. Re-add only if the table is restored to prod.

    -- ---- client ----
    UPDATE `twservices4-staging`.`client` SET
        contactname = CONCAT('Contact ', LEFT(MD5(uuid), 6));

    -- ---- bulk_email_recipient ----
    UPDATE `twservices4-staging`.`bulk_email_recipient` SET
        recipient_email = CONCAT('recipient', id, '@example.com');

    -- ---- mail ----
    -- V457: also scrub the V455 copy/reply columns (recruiter + candidate
    -- addresses; staging must not hold real routing addresses that a
    -- mis-armed staging mailer could target).
    UPDATE `twservices4-staging`.`mail` SET
        mail     = CONCAT(LEFT(MD5(uuid), 8), '@example.com'),
        content  = 'Redacted',
        reply_to = NULL,
        cc       = NULL,
        bcc      = NULL;

    -- ---- passwordchanges ----
    UPDATE `twservices4-staging`.`passwordchanges` SET
        password = '$2a$10$INVALIDHASH.NOLOGIN.STAGING.000000000000000000000';

    -- ---- cv_tool_employee_cv ----
    UPDATE `twservices4-staging`.`cv_tool_employee_cv` SET
        employee_name    = CONCAT('Consultant ', LEFT(MD5(useruuid), 6)),
        employee_title   = 'Consultant',
        employee_profile = 'Redacted',
        cv_data_json     = '{}';

    -- ---- invoiceitems (only BASE items that contain consultant names) ----
    UPDATE `twservices4-staging`.`invoiceitems` SET
        itemname = CONCAT('Consultant ', LEFT(MD5(consultantuuid), 6))
    WHERE origin = 'BASE';

    -- ---- expenses: prevent e-conomics uploads in staging ----
    -- Table is `expenses` (plural). V258 had `expense` (singular) which silently aborted
    -- the whole procedure. See the incident doc.
    -- VALIDATED/PROCESSING -> CREATED (expense-consume reader won't pick them up)
    UPDATE `twservices4-staging`.`expenses` SET
        status = 'CREATED'
    WHERE status IN ('VALIDATED', 'PROCESSING');

    -- UP_FAILED/VOUCHER_CREATED -> UPLOADED (terminal state, no retry)
    UPDATE `twservices4-staging`.`expenses` SET
        status = 'UPLOADED'
    WHERE status IN ('UP_FAILED', 'VOUCHER_CREATED');

    -- Post-condition safeguard: if any upload-eligible status survived the flip,
    -- the UPDATEs above didn't work (likely a new status value was added without
    -- anonymisation being updated). Raise a loud error so the event scheduler logs
    -- it and the RDS-event CloudWatch alarm fires.
    SELECT COUNT(*) INTO v_bad_expense_rows
    FROM `twservices4-staging`.`expenses`
    WHERE status IN ('VALIDATED', 'PROCESSING', 'UP_FAILED', 'VOUCHER_CREATED');
    IF v_bad_expense_rows > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'sp_sync_prod_to_staging: expense status-safeguard failed - upload-eligible rows remain in staging.expenses';
    END IF;

    SET FOREIGN_KEY_CHECKS = @old_fk;

    -- =========================================================================
    -- PHASE 3: Recreate views in staging with corrected schema references
    -- Two passes: first pass creates views whose dependencies (tables) exist;
    -- second pass retries views that depend on other views created in pass 1.
    -- Both passes suppress errors so a single failure doesn't abort the sync.
    -- =========================================================================

    -- Pass 1
    SET v_done = 0;
    OPEN cur_views;
    view_loop: LOOP
        FETCH cur_views INTO v_view_name, v_view_def;
        IF v_done THEN
            LEAVE view_loop;
        END IF;

        -- Replace production schema references with staging schema
        SET v_view_def = REPLACE(v_view_def, '`twservices4`.', '`twservices4-staging`.');
        SET v_view_def = REPLACE(v_view_def, 'twservices4.', '`twservices4-staging`.');

        SET @sql_view = CONCAT('CREATE OR REPLACE VIEW `twservices4-staging`.`', v_view_name,
                               '` AS ', v_view_def);

        BEGIN
            DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;
            PREPARE stmt FROM @sql_view;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END;
    END LOOP;
    CLOSE cur_views;

    -- Pass 2: retry views that failed in pass 1 due to view-on-view dependencies
    SET v_done = 0;
    OPEN cur_views;
    view_retry_loop: LOOP
        FETCH cur_views INTO v_view_name, v_view_def;
        IF v_done THEN
            LEAVE view_retry_loop;
        END IF;

        SET v_view_def = REPLACE(v_view_def, '`twservices4`.', '`twservices4-staging`.');
        SET v_view_def = REPLACE(v_view_def, 'twservices4.', '`twservices4-staging`.');

        SET @sql_view = CONCAT('CREATE OR REPLACE VIEW `twservices4-staging`.`', v_view_name,
                               '` AS ', v_view_def);

        BEGIN
            DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;
            PREPARE stmt FROM @sql_view;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END;
    END LOOP;
    CLOSE cur_views;

END$$

DELIMITER ;
