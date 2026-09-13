-- ============================================================================
-- V596: TrustLink — the connections the firm already has into an account
-- ============================================================================
-- Feature: CRM spec §3.5/§3.7 (the relationship graph, "instead of a contact
--          database"), §4.3 tab 1 Overview → "Who knows them", tab 4 Relationships.
-- Domain:  aggregates/crm/trustlink + aggregates/crm/account
--
-- WHAT THIS IS
--   "Who knows them" has had two sources since V584/V588: KNOWS edges from a
--   colleague's signal capture, and MET edges from calendar metadata. Both only
--   ever see a relationship AFTER somebody has already talked to the client,
--   which is precisely the moment the card stops being useful. TrustLink —
--   the firm's own LinkedIn-connection tool at trustlink-blazor.azurewebsites.net
--   — knows the relationships that exist BEFORE any of that: 47,436 people, of
--   whom ~1,600 sit at a company we already have as a client. This migration
--   mirrors the tier-5 (strongest) subset of that graph locally so the account
--   page can say "Mia has known their CFO since 2013" without a synchronous call
--   to a third-party service on every page render.
--
-- WHY A MIRROR AND NOT A LIVE CALL
--   TrustLink has no authentication today and no availability commitment. A page
--   that reads it synchronously would be as slow and as available as it is, and
--   would leak which accounts we look at to a service nobody operates. A nightly
--   job writes here; the account page reads only this schema.
--
-- WHY AN ALIAS TABLE EXISTS AT ALL (the whole reason for table 1)
--   TrustLink's company names are exact, case-sensitive and FRAGMENTED. "Novo
--   Nordisk" carries 203 tier-5 connections; "Novo Nordisk A/S" carries 2. The
--   Intra client is called "NOVO NORDISK A/S". Any one-name mapping — however
--   clever the normaliser — throws away 203 relationships. So a client maps to
--   MANY TrustLink names: the nightly matcher seeds what it can find as AUTO,
--   and a person may add or suppress names by hand as MANUAL. The seeder never
--   touches a MANUAL row in either direction, which is what makes a MANUAL row
--   with enabled = 0 a durable "no, not that company" rather than something the
--   next run silently re-adds.
--
-- WHY NOTHING IS EVER DELETED
--   Decision 2026-09-13: upsert, never delete. TrustLink's search is a filtered
--   query, not a snapshot — a batch that fails, a company name that gets renamed
--   upstream, or a tier that is recomputed would all look like "these people are
--   gone" to a delete-what-was-not-returned reconciler, and would silently erase
--   a relationship the firm actually has. Instead every row carries first_seen_at
--   and last_seen_at; staleness is a READ-time judgement on last_seen_at, and the
--   job's only write verbs are INSERT and UPDATE.
--
-- WHY match_method IS STORED
--   Matching a TrustLink trustworker name to an Intra user is a four-rung ladder
--   (e-mail, normalised full name, first+last token, token-prefix) and the last
--   two rungs are heuristics: "Ditte Hjorth" → "Ditte Marie Hjorth" is right,
--   and a rule that produces that also has to be stopped from producing "Marie
--   Dorthea" → "Marie Daugaard". Every rung requires a UNIQUE hit, so ambiguity
--   yields no match rather than a guess — but a wrong match is still possible,
--   and undiagnosable unless the row says HOW it was made. Hence match_method,
--   and hence trustlink_trustworker_map, where a human verdict beats all four
--   rungs and a row mapping to NULL means "deliberately unmapped, stop trying".
--
-- WHY AN UNMATCHED NAME IS STILL A ROW
--   trustlink_connection_trustworker.user_uuid is NULLABLE on purpose. If a name
--   does not resolve to an Intra user, the external person is still real and so
--   is the fact that somebody at Trustworks knows them. Dropping the edge would
--   throw away exactly the signal the feature exists for; the reader renders the
--   raw trustworker_name instead.
--
-- THIRD-PARTY PERSONAL DATA — THE POSTURE
--   trustlink_connection holds the NAME, JOB TITLE and LINKEDIN URL of a person
--   at a client organisation, and trustlink_connection_trustworker holds who at
--   Trustworks knows them and since when. That is the same category of data as
--   account_meeting_attendee (V588) and account_signal (V584): third-party
--   personal data nobody in this building asked consent for. It is readable
--   behind accounts:read — no new scope, decided 2026-09-13 — and both tables
--   join the sp_sync_prod_to_staging exclusion list below so prod names never
--   land in an environment with wider access.
--
--   trustlink_company_alias, trustlink_trustworker_map and trustlink_sync_state
--   are deliberately NOT excluded. They are configuration and run bookkeeping —
--   a client-to-company-name mapping, a name override, a counter — and staging
--   is materially easier to work on with them present.
--
-- RETENTION — THE OPEN GAP, EXTENDED AGAIN
--   Spec §3.4/§3.7 promise 24 months after the account's last activity, after
--   which person data is anonymised. The purge job is still NOT built (V588,
--   V590, V593 and V594 each record the same gap). This migration INCREASES the
--   third-party personal data at rest — by the largest single step so far, since
--   one sync can mirror well over a thousand named external people — and when
--   the purge is finally built it must sweep trustlink_connection too. The FK
--   below means deleting a connection takes its edges with it.
--
-- NO FOREIGN KEYS onto client / user, matching V585-V594: client_uuid and
--   user_uuid are plain identifiers, so a client the CRM deletes cannot block a
--   sync and a user row cannot be pinned by a mirror of somebody else's data.
--   Inside the feature trustlink_connection_trustworker → trustlink_connection
--   IS a real FK with ON DELETE CASCADE, so an edge can never outlive the person
--   it points at.
--
-- RESERVED-WORD CHECK (MariaDB 10.x, and this is written down because of the
--   V534 `lines` incident): none of position, source, tier, match_method,
--   connected_on, person_id, full_name, company_name, company_id, linkedin_url,
--   is_customer, coffee_count, comment_count, first_seen_at, last_seen_at,
--   enabled, failure_code, last_run_at, last_success_at or
--   unmatched_trustworker_names is a MariaDB reserved word. position and source are the two near-misses and were checked
--   individually: POSITION is a non-reserved function keyword (only ambiguous
--   when immediately followed by "(", which no query here does) and SOURCE is a
--   mysql CLI client command, not a server keyword, so neither needs quoting.
--   MATCH is reserved; match_method is not.
--
-- COLLATION: utf8mb4_general_ci, as V585-V595 (client_uuid / user_uuid join
--   general_ci tables; a unicode_ci mix fails at runtime with ERROR 1267).
--
-- Idempotency: CREATE TABLE IF NOT EXISTS and INSERT ... ON DUPLICATE KEY UPDATE.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   DROP TABLE trustlink_connection_trustworker, trustlink_connection,
--              trustlink_company_alias, trustlink_trustworker_map,
--              trustlink_sync_state;
--   Re-emit sp_sync_prod_to_staging from V594 to drop the two exclusions.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Which TrustLink company names an account is. Many, not one — see header.
--    An AUTO row is the matcher's finding and is the seeder's to maintain; a
--    MANUAL row is a person's decision and the seeder never touches it, which
--    is what lets enabled = 0 suppress a company AUTO would otherwise re-add.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trustlink_company_alias
(
    uuid         CHAR(36)     NOT NULL,
    client_uuid  CHAR(36)     NOT NULL,
    company_name VARCHAR(255) NOT NULL COMMENT 'Exact TrustLink company name; their search is case-sensitive, so the case stored here is the case queried',
    source       VARCHAR(10)  NOT NULL COMMENT 'AUTO (seeded by the nightly matcher) | MANUAL (a person decided; the seeder never touches it)',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'A MANUAL row with 0 suppresses a company the seeder would otherwise add',
    created_at   DATETIME     NOT NULL,
    created_by   CHAR(36)     NULL COMMENT 'Null for AUTO rows: nobody decided them',
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_trustlink_alias_client_company (client_uuid, company_name),
    KEY idx_trustlink_alias_company (company_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Client → TrustLink company names, many per client. Exists because TrustLink fragments companies: "Novo Nordisk" has 203 tier-5 connections, "Novo Nordisk A/S" has 2.';

-- ----------------------------------------------------------------------------
-- 2. One external person as TrustLink sees them, mirrored per account.
--    A returned companyName can be aliased by two different clients, so the
--    same person legitimately appears once per client — hence the unique key is
--    (client_uuid, person_id) and not person_id alone.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trustlink_connection
(
    uuid          CHAR(36)     NOT NULL,
    client_uuid   CHAR(36)     NOT NULL,
    person_id     VARCHAR(255) NOT NULL COMMENT 'TrustLink personId. NOT always a 32-char hex hash: some are human-readable slugs, and the longest in the live corpus is 72 chars (nikolaj_andersson_kihl_forsvarsministeriets_materiel-_og_indkoebsstyrelse). VARCHAR(64) truncated it, which failed the whole 100-company batch under STRICT mode and cost ~400 connections a night.',
    full_name     VARCHAR(255) NOT NULL,
    position      VARCHAR(500) NULL COMMENT 'Job title as TrustLink has it; long ones are real ("Director, LATAM Health Policy & Business Development")',
    tier          INT          NOT NULL COMMENT 'Only 5 is mirrored today; the column keeps the door open without a migration',
    company_name  VARCHAR(255) NOT NULL COMMENT 'The TrustLink name this person was found under, i.e. the alias that produced the row',
    company_id    VARCHAR(64)  NULL,
    linkedin_url  VARCHAR(500) NULL,
    is_customer   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'TrustLink''s own flag, mirrored as-is; not the CRM''s notion of a customer',
    coffee_count  INT          NOT NULL DEFAULT 0,
    comment_count INT          NOT NULL DEFAULT 0,
    first_seen_at DATETIME     NOT NULL COMMENT 'Set on INSERT only',
    last_seen_at  DATETIME     NOT NULL COMMENT 'Set on every sync that returned this person; staleness is read from here, nothing is ever deleted',
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_trustlink_connection_client_person (client_uuid, person_id),
    KEY idx_trustlink_connection_client_seen (client_uuid, last_seen_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Mirror of a tier-5 TrustLink person at a client organisation. Third-party personal data (name, title, LinkedIn); excluded from the staging sync; agreed retention 24 months, purge job NOT built.';

-- ----------------------------------------------------------------------------
-- 3. The edge: somebody at Trustworks knows this person, and since when.
--    user_uuid is NULLABLE on purpose — an unresolved name is still a real
--    relationship and is still shown (see header).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trustlink_connection_trustworker
(
    uuid             CHAR(36)     NOT NULL,
    connection_uuid  CHAR(36)     NOT NULL,
    trustworker_name VARCHAR(255) NOT NULL COMMENT 'Exactly as TrustLink spells it; kept even when it resolves, so a wrong match stays diagnosable',
    user_uuid        CHAR(36)     NULL COMMENT 'Null = the name did not resolve uniquely to an Intra user. The edge is still shown under the raw name.',
    match_method     VARCHAR(16)  NULL COMMENT 'EMAIL | FULLNAME | FIRST_LAST | PREFIX | MANUAL — which rung of the ladder produced user_uuid',
    connected_on     DATE         NULL COMMENT 'TrustLink sends a zoneless local datetime; only the date is meaningful and only the date is kept',
    first_seen_at    DATETIME     NOT NULL,
    last_seen_at     DATETIME     NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_trustlink_conn_tw (connection_uuid, trustworker_name),
    KEY idx_trustlink_conn_tw_user (user_uuid),
    CONSTRAINT fk_trustlink_conn_tw FOREIGN KEY (connection_uuid)
        REFERENCES trustlink_connection (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='CONNECTED edges: the third source of the relationship graph, beside MET (V588) and KNOWS (V584).';

-- ----------------------------------------------------------------------------
-- 4. The human verdict on a trustworker name. Beats all four matcher rungs.
--    user_uuid NULL is not "unknown" — it is "deliberately unmapped, stop
--    trying", which is the only way to silence a name the heuristics keep
--    resolving to the wrong person.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trustlink_trustworker_map
(
    trustworker_name VARCHAR(255) NOT NULL COMMENT 'The TrustLink spelling; the primary key, because the name is what the API gives us',
    user_uuid        CHAR(36)     NULL COMMENT 'Null = never match this name to anybody',
    created_at       DATETIME     NOT NULL,
    created_by       CHAR(36)     NULL,
    PRIMARY KEY (trustworker_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Manual TrustLink name → Intra user override. Configuration, not personal data: copied to staging on purpose.';

-- ----------------------------------------------------------------------------
-- 5. One row, forever, id = 1. Bookkeeping for the nightly run: when it last
--    ran, when it last SUCCEEDED (not the same question), what it moved, and
--    how many trustworker names the ladder could not resolve — that last number
--    is the health metric for the matcher, and the reason to look at
--    trustlink_trustworker_map.
--
--    The names come with the count. "unmatched_trustworkers = 7" is an alarm
--    nobody can act on: the only remedy is a trustlink_trustworker_map row keyed
--    BY the name, so the number on its own can be watched but never resolved.
--    These are TRUSTWORKS COLLEAGUES out of our own staff directory — safe both
--    to store here and to log, unlike the third-party people in
--    trustlink_connection, who are never named outside their own table.
--
--    GET /trustlink/sync-state serves this row behind accounts:read. Without it
--    the whole table was write-only: the job wrote nightly and the only reader
--    was a SQL client against a production database that is read-only by default.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trustlink_sync_state
(
    id                          TINYINT       NOT NULL COMMENT 'Always 1; a singleton row rather than a settings key so the counters are typed',
    last_run_at                 DATETIME      NULL,
    last_success_at             DATETIME      NULL COMMENT 'Deliberately separate from last_run_at: a run that failed every batch still ran',
    companies_queried           INT           NOT NULL DEFAULT 0,
    connections_upserted        INT           NOT NULL DEFAULT 0,
    edges_upserted              INT           NOT NULL DEFAULT 0,
    unmatched_trustworkers      INT           NOT NULL DEFAULT 0,
    unmatched_trustworker_names VARCHAR(2000) NULL COMMENT 'Up to 25 of those names, newline-separated. TRUSTWORKS colleagues from our own directory, not third-party people - the count alone cannot be acted on, the fix is a trustlink_trustworker_map row keyed BY the name',
    failure_code                VARCHAR(40)   NULL COMMENT 'A CODE only, never an upstream body: an error body can echo the request back',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Singleton run bookkeeping for the TrustLink sync. Environment-local, but harmless in staging, so it is NOT on the exclusion list.';

-- Idempotent for a database that already created this table from an earlier shape
-- of this migration: CREATE TABLE IF NOT EXISTS above is a no-op there and would
-- leave the table without the names column, so GET /trustlink/sync-state would
-- answer a count nobody can act on. Harmless on a fresh database.
ALTER TABLE trustlink_sync_state
    ADD COLUMN IF NOT EXISTS unmatched_trustworker_names VARCHAR(2000) NULL
        COMMENT 'Up to 25 of those names, newline-separated. TRUSTWORKS colleagues from our own directory, not third-party people'
        AFTER unmatched_trustworkers;

-- The singleton. ON DUPLICATE KEY UPDATE id = id so a re-run neither fails nor
-- resets counters a live sync has since written.
INSERT INTO trustlink_sync_state (id, companies_queried, connections_upserted, edges_upserted, unmatched_trustworkers)
VALUES (1, 0, 0, 0, 0)
ON DUPLICATE KEY UPDATE id = id;

-- ----------------------------------------------------------------------------
-- 6. Staging sync — the two person-bearing TrustLink tables must never be copied
--
--    VERBATIM re-emission of sp_sync_prod_to_staging as defined in V594 (the
--    newest definition), with two names added to the NOT IN list. MySQL cannot
--    patch a stored procedure, so the whole body has to come along. If you
--    re-emit this proc again, copy from the newest migration that defines it,
--    never from an older one, or you silently revert later exclusions. See the
--    posture note in the header before touching this.
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
