-- ============================================================================
-- V608 — the 24-month purge every earlier migration promised
--
-- Spec: docs/specs/intra-crm-relationships-people-2026-09-14.md §7, §5.1
-- Domain: aggregates/crm/retention
--
-- WHAT THIS IS
--   Three things, in this order:
--     1. crm_retention_purge_run  -- one row per run, with per-table counts.
--     2. The two app_settings rows: the arming row seeded 'false', and the
--        blast-radius cap seeded '10'.
--     3. The ONE re-emission of sp_sync_prod_to_staging for this whole cut,
--        adding the five tables V604, V605 and this file create.
--
-- WHY THIS EXISTS AT ALL
--   V588, V590, V593, V594, V596 and V602 each added a table holding third-party
--   personal data, and each wrote the same sentence into its header: retention is
--   24 months after the account last saw activity, and THE PURGE JOB IS NOT
--   BUILT. Six migrations, one promise, no job. Several of them said so in
--   capitals, which is the register of a control nobody implemented rather than
--   a control.
--
--   This cut adds three more such tables (account_person,
--   account_person_identity, account_relation_claim) and closes the register
--   instead of extending it. CrmRetentionPurgeJob sweeps every one of them plus
--   the six that have been waiting.
--
-- IT SHIPS DISARMED. TWO SWITCHES, BOTH REQUIRED.
--   1. dk.trustworks.crm.retention.purge.enabled -- a @ConfigProperty kill
--      switch, default true. It decides whether the JOB STARTS.
--   2. app_settings 'crm.retention.purge.enabled', seeded 'false' below. It
--      decides whether the job DELETES ANYTHING.
--
--   Switch 1 on and switch 2 off is the shipped state: the job wakes at 03:30,
--   works out what is eligible, writes a run row saying so, and destroys
--   nothing. That is deliberate -- it is how the first week's run rows can be
--   read before anybody commits.
--
--   FLIPPING THE app_settings ROW TO 'true' IS THE MOMENT AUTOMATIC DELETION
--   STARTS. This is the posture both existing retention jobs take, in the same
--   capitals, and it exists because the alternative -- a single flag, defaulted
--   on -- deletes on deploy day, against a backlog nobody has looked at, with no
--   undo.
--
-- BLAST RADIUS: A CAP PER RUN, NOT A BIG BANG
--   crm.retention.purge.nightly-account-cap, default 10, oldest-first. Even
--   armed, one night touches ten accounts. The CRM holds years of backlog and
--   the first armed run would otherwise erase all of it at once, irreversibly.
--   Ten a night is the same cap the employee-documents retention sweep uses, for
--   the same reason.
--
--   THE CAP IS SEEDED BELOW, AND THAT IS THE HALF THIS FILE ORIGINALLY MISSED.
--   The cap lives in two places on purpose -- the app_settings row seeded in
--   section 2, and the application.yml key
--   dk.trustworks.crm.retention.purge.nightly-account-cap (i.e. the
--   CRM_RETENTION_PURGE_CAP environment variable). The row wins while it holds
--   a usable value; the yml/env value is what a missing, blank or garbage row
--   falls back to; ten is what a missing yml key falls back to.
--
--   The first cut seeded only the arming row and CrmRetentionParameters read
--   only app_settings, so BOTH stores were silently inert: the env var reached
--   nothing, the row did not exist, and the cap fell through to the compiled
--   ten no matter what any operator set. A blast-radius cap on irreversible
--   work that ignores the knob an operator reaches for is worse than no knob,
--   because it reads as a control. If you ever remove one of the two stores,
--   remove the other's fallback in the same change.
--
-- 24 MONTHS IS A COMPILED CONSTANT, NOT A SETTING
--   The period is policy. It lives in CrmRetentionParameters as a constant, and
--   changing it is a reviewed code change, not an admin toggle -- exactly as
--   RecruitmentGdprParameters records for its own 6- and 12-month constants.
--   Only cadence and the blast-radius cap belong in configuration. There is
--   deliberately no app_settings row for the period.
--
-- WHY THE RUN ROW HAS dry_run, AND WHY IT OPENS BEFORE THE WORK
--   POST /crm/retention/purge?dryRun=true answers synchronously with counts and
--   writes nothing except its own run row, so an admin can see exactly what an
--   armed run would do. Without the flag the endpoint answers 202 and a run
--   uuid: the ALB cuts a request at 60 seconds, and a synchronous destructive
--   run reports a timeout for a run that went perfectly, which an admin cannot
--   tell from one that died.
--
--   status opens as RUNNING before any work and is closed afterwards -- two
--   writes, not one. A process killed mid-run then leaves a row nobody ever
--   closed, which is visible; a single write at the end leaves no trace at all.
--   A run that found nothing still closes its row, with zeros: a row left
--   RUNNING is indistinguishable from one still in flight.
--
-- failure_code IS A CODE, NEVER AN UPSTREAM BODY
--   The discipline every sync-state table here already follows, and it matters
--   more on this table than on any of them: the bodies a retention job could
--   catch are bodies about the very people it is erasing. Counts say how much
--   happened. Nothing in this table says WHO.
--
-- WHAT THIS COSTS, recorded rather than hidden
--   · The job is irreversible by construction. There is no soft delete and no
--     undo; that is what erasure means.
--   · client_note is out of scope and stays out. Its names are inside free text
--     with no structured column, so the only primitive that works is deleting
--     the row, and deleting a whole note to erase a name is a bigger decision
--     than this cut is making. Recorded in spec §12, still open.
--   · The clock is circular and the job has to handle it: five of the ten
--     sources AccountActivityService.lastActivityForAll() unions are themselves
--     tables this job deletes from, so purging moves an account's last-activity
--     date BACKWARDS and makes more rows eligible on the next run. That is
--     handled in the service, not here, but it is the reason the cap above is
--     not optional.
--   · trustlink_connection is purged on STALENESS, not on account inactivity:
--     rows go when TrustLink itself stopped returning the person 24 months ago.
--     An account-based purge of those rows would be re-mirrored the next night.
--
-- RESERVED-WORD CHECK (MariaDB 10.11, written down because of the V534 `lines`
--   incident). Every new column name was checked: uuid, trigger_kind,
--   started_by, started_at, finished_at, status, dry_run, accounts_considered,
--   accounts_purged, meeting_attendees, signals_redacted, slack_mentions,
--   people_deleted, stakeholders_cleared, trustlink_connections, failures,
--   failure_code. None is reserved. Two are worth naming:
--     `trigger_kind`  TRIGGER **is** reserved, which is exactly why the column
--                     is not called `trigger` -- the same trap V602 dodged.
--     `status`        not reserved (V601 and V602 both use it unquoted).
--   `LINES` -- the word that broke V534 -- does not appear.
--
-- COLLATION: utf8mb4_general_ci, as V585-V607. started_by joins general_ci user
--   uuids; a unicode_ci mix fails at runtime with ERROR 1267, not here.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS, INSERT ... ON DUPLICATE KEY UPDATE
--   (which deliberately does NOT reset a value somebody has already flipped),
--   and a procedure that is dropped and recreated.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   DROP TABLE crm_retention_purge_run;
--   DELETE FROM app_settings WHERE setting_key IN (
--       'crm.retention.purge.enabled',
--       'crm.retention.purge.nightly-account-cap');
--   Re-emit sp_sync_prod_to_staging from V603 to drop the five exclusions.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. One row per run. Counts only -- no account, no person, no name.
--
--    The columns are one per table the sweep touches, because "we deleted 400
--    things" is not a number anybody can act on and "we deleted 12 meeting
--    attendees and redacted 3 signals" is.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS crm_retention_purge_run
(
    uuid                  CHAR(36)    NOT NULL,
    trigger_kind          VARCHAR(10) NOT NULL COMMENT 'SCHEDULED | MANUAL',
    started_by            CHAR(36)    NULL COMMENT 'MANUAL only',
    started_at            DATETIME    NOT NULL COMMENT 'Written when the row opens, before any work. The trim reads THIS, not finished_at: a row that was never closed has no finish, and those are precisely the rows that would otherwise accumulate for ever',
    finished_at           DATETIME    NULL COMMENT 'NULL while the run is open. A row left NULL for ever is a process that was killed mid-run -- which is the point of opening the row first',
    status                VARCHAR(10) NOT NULL COMMENT 'RUNNING | DONE | STOPPED | FAILED',
    dry_run               TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1 = counted what it would have done and wrote nothing but this row. The arming switch being off produces the same shape',
    accounts_considered   INT         NOT NULL DEFAULT 0 COMMENT 'Past the 24-month threshold; may exceed accounts_purged because of the per-run cap',
    accounts_purged       INT         NOT NULL DEFAULT 0,
    meeting_attendees     INT         NOT NULL DEFAULT 0 COMMENT 'account_meeting_attendee rows deleted; the account_meeting rows themselves stay, being a dated count with no third party in them',
    signals_redacted      INT         NOT NULL DEFAULT 0 COMMENT 'account_signal rows whose person_name, person_role and relation_text were nulled and whose signal_text was redacted -- four columns, not one',
    slack_mentions        INT         NOT NULL DEFAULT 0 COMMENT 'account_slack_mention rows deleted; participants follow through the cascade',
    people_deleted        INT         NOT NULL DEFAULT 0 COMMENT 'account_person rows deleted; identities and claims follow through their cascades',
    stakeholders_cleared  INT         NOT NULL DEFAULT 0 COMMENT 'client_plan_stakeholder rows whose name and person_uuid were nulled. The seat stays: a plan should keep "we need somebody in procurement"',
    trustlink_connections INT         NOT NULL DEFAULT 0 COMMENT 'Purged on STALENESS, not account inactivity: TrustLink stopped returning the person 24 months ago. Trustworker edges follow through the cascade',
    failures              INT         NOT NULL DEFAULT 0 COMMENT 'Per-item failures are caught, counted and stepped over. One bad row must never abandon the rest of the run',
    failure_code          VARCHAR(40) NULL COMMENT 'A CODE only, never an upstream body',
    PRIMARY KEY (uuid),
    KEY idx_crm_retention_purge_run_started (started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='One row per CRM retention purge run. Counts only -- NO ACCOUNT, NO PERSON AND NO NAME IS EVER WRITTEN HERE, because the bodies a retention job could catch are bodies about the people it is erasing.';

-- ----------------------------------------------------------------------------
-- 2. The two settings rows: the arming switch, seeded OFF, and the blast-radius
--    cap, seeded 10.
--
--    Read the ON DUPLICATE KEY UPDATE carefully: it rewrites setting_key with
--    its own value, which is a no-op. That is the point. A re-run of this
--    migration against a database where somebody has already armed the job must
--    not quietly disarm it, and a re-run against one where they have not must
--    not quietly arm it. The seed sets the value exactly once, on first insert.
--    The cap row uses the identical idiom for the identical reason: a re-run
--    must not reset a cap somebody has deliberately lowered mid-backlog.
--
--    app_settings IS copied to staging, on purpose, and that is safe here for
--    the same reason it is safe for the Slack flag: the tables this job deletes
--    from are all excluded from the sync, so staging's copy of the flag arms a
--    job that finds staging's own (empty) CRM tables and returns.
--
--    WHY THE CAP ROW IS SEEDED AND NOT LEFT TO THE CODE DEFAULT
--    CrmRetentionParameters.nightlyAccountCap() reads THIS row first and only
--    falls back to the yml/env value when it is absent, blank or unparseable.
--    Seeding it is what puts the number in front of whoever opens the settings
--    screen to arm the job -- the cap and the arming switch are read in the same
--    breath by the same person, and a cap that exists only as an environment
--    variable on a task definition is not something that person can see. It is
--    seeded at the SAME value as the yml default, so seeding changes no
--    behaviour on any environment that has not set CRM_RETENTION_PURGE_CAP; an
--    environment that HAS set it and wants that value to win should delete this
--    row, which is a supported state (the code falls through to yml/env).
--
--    HOUSE RULE, for the next job: cadence and blast-radius caps live in
--    app_settings; the retention PERIOD does not and must not (see above).
-- ----------------------------------------------------------------------------
INSERT INTO app_settings (setting_key, setting_value, category)
VALUES ('crm.retention.purge.enabled', 'false', 'crm')
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

INSERT INTO app_settings (setting_key, setting_value, category)
VALUES ('crm.retention.purge.nightly-account-cap', '10', 'crm')
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

-- ----------------------------------------------------------------------------
-- 3. sp_sync_prod_to_staging — VERBATIM re-emission, with five names added.
--
--    THIS IS THE ONLY RE-EMISSION IN THIS CUT. V604, V605, V606 and V607 create
--    and alter tables and touch the procedure not at all; all five of the cut's
--    new tables are excluded here, in one block, because MySQL cannot patch a
--    stored procedure and five files each carrying the whole ~490-line body
--    would be five chances to copy from the wrong definer.
--
--    The body below is lifted VERBATIM from V603__Slack_source_channel_config_-
--    out_of_staging_sync.sql -- the newest migration that defines it, confirmed
--    by grepping for the definition and not by trusting a header:
--
--      grep -l "CREATE PROCEDURE sp_sync_prod_to_staging" *.sql | sort -V | tail -1
--
--    V597's own header calls itself the definer and defines nothing, which is
--    why the grep is the rule and the header is not. Copying from an older
--    definer would silently revert V603's source-channel exclusions, V602's
--    mention exclusions and V596's TrustLink ones. IF YOU RE-EMIT THIS PROC
--    AGAIN, COPY FROM THE NEWEST MIGRATION THAT DEFINES IT, NEVER FROM AN OLDER
--    ONE.
--
--    Nothing in the body differs from V603 except one inserted block in the
--    NOT IN list, placed where V603 put its own: immediately after
--    'crm_slack_sync_run', and before the -- V590: Client notes banner.
--
--    WHY THESE FIVE ARE EXCLUDED
--      account_person and account_person_identity hold the name, the client's
--      shorthand initials, the job title, the LinkedIn URL and the e-mail
--      address of a person at a client organisation -- the same posture and the
--      same reason as account_meeting_attendee and trustlink_connection, and in
--      the same breath, since the registry is built FROM those tables.
--
--      account_relation_claim carries free text a colleague typed about a named
--      person; it is a child of an excluded parent besides, which is V593's
--      argument for account_signal_colleague repeated exactly.
--
--      account_person_build_state and crm_retention_purge_run hold no personal
--      data at all. They are excluded because a run log and a build counter
--      describe what ONE ENVIRONMENT did. Copied, staging's admin screens report
--      production's runs as their own -- and a production purge run still open
--      at sync time would leave staging's own trigger disabled behind a tooltip
--      about a run that is not happening there. Exactly V603's argument for
--      crm_slack_sync_run.
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
              -- V603: the source-channel configuration and its run log.
              --
              -- Not a privacy exclusion -- there is nothing third-party in
              -- either of them. They are excluded because staging's Slack token
              -- IS production's: the two environments read the same real
              -- workspace. Copied, prod's channel list would arm staging to read
              -- the same real channels on the same nights, and the firm would
              -- pay OpenAI twice for one answer that only one environment ever
              -- looks at. V602 reasoned that a channel list is configuration and
              -- configuration is copied, which is the right rule and the wrong
              -- conclusion once the shared token is taken into account.
              --
              -- Leaving the list behind is what makes the flag safe to inherit.
              -- app_settings IS copied, so staging picks up
              -- crm.slack.source-channels.enabled from production the night it
              -- is switched on there; with no channels of its own, staging's job
              -- wakes, finds nothing enabled and returns. Staging can still list
              -- a test channel, and that channel now survives the nightly sync
              -- instead of being replaced by production's.
              --
              -- crm_slack_sync_run goes with it because a run log describes what
              -- THIS environment did. Copied, staging's Settings tab reports
              -- production's runs as its own, and a production run still open at
              -- sync time disables staging's Run now button behind a tooltip
              -- about a run that is not happening here.
              -- ----------------------------------------------------------------
              'crm_slack_source_channel',
              'crm_slack_sync_run',
              -- ----------------------------------------------------------------
              -- V604/V605/V608: the people registry at an account, the
              -- claims colleagues file about them, and the purge run log.
              --
              -- account_person and account_person_identity hold the NAME,
              -- the client's own shorthand initials, the JOB TITLE, the
              -- LINKEDIN URL and the E-MAIL ADDRESS of a person at a client
              -- organisation. account_relation_claim adds a line of free
              -- text a colleague typed about one of them. Same posture and
              -- the same reason as account_signal, account_meeting_attendee
              -- and trustlink_connection above -- third-party personal data
              -- nobody consented to must never be copied into an environment
              -- with wider access -- and in the same breath as those three,
              -- because the registry is derived from them.
              --
              -- account_person_build_state and crm_retention_purge_run carry
              -- no personal data at all. They are excluded because a build
              -- counter and a run log describe what THIS environment did.
              -- Copied, staging's admin screens report production's runs as
              -- their own, and a production purge still open at sync time
              -- leaves staging's own trigger disabled behind a tooltip about
              -- a run that is not happening here. That is the same argument
              -- V603 made for crm_slack_sync_run, one block above.
              -- ----------------------------------------------------------------
              'account_person',
              'account_person_identity',
              'account_person_build_state',
              'account_relation_claim',
              'crm_retention_purge_run',
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
