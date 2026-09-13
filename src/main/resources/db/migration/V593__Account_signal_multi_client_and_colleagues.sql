-- ============================================================================
-- V593: Account signals — one capture, many accounts, and the colleagues it named
-- ============================================================================
-- Feature: CRM spec §3.4 / §3.7, correcting two gaps found in production on
--          2026-09-13 against the first real multi-account capture.
-- Domain:  aggregates/crm/signal + aggregates/crm/account (the relationship graph)
--
-- WHAT WENT WRONG
--   A colleague typed one line naming TWO clients and one Trustworks
--   colleague:
--
--     "@Rigspolitiet jeg har snakket med Dorte som jeg har mødt i @KOMBIT
--      sammen Tobias Kjølsen. De vil gerne have en introduktion til AI."
--
--   One row was stored, against KOMBIT only, and Tobias appeared nowhere.
--   Neither was a bug in the code as written — both were the schema doing
--   exactly what V584 said. account_signal.client_uuid is a single NOT NULL
--   column, so the second @ mention could only ever REPLACE the first (the
--   capture panel held one picked client and the last pick won, silently);
--   and there was no column, anywhere, for a colleague the line named, so the
--   relationship graph could only ever draw author -> person.
--
--   The account that lost is the one the signal was actually about.
--
-- WHY N ROWS AND NOT A JOIN TABLE
--   A capture that names three accounts becomes three account_signal rows
--   sharing a capture_uuid, rather than one row with a child list of clients.
--   Decided deliberately (2026-09-13):
--
--     - Every read surface, every index and every query in the feature starts
--       from `where client_uuid = ?`. A join table would have to be threaded
--       through listForClient, the activity feed, the owner queue and the
--       relationship graph; N rows change none of them.
--     - status / lead_uuid / decided_by / decided_at are PER ACCOUNT. The
--       owner of KOMBIT parking a signal must not park it for the owner of
--       Rigspolitiet, and AccountSignalService.decide authorizes against
--       client.accountmanager of ONE client. With a join table that check has
--       no single client to run against.
--
--   The cost is a duplicated signal_text per account, which is accepted: the
--   line is one sentence, and capture_uuid is what a reader groups by when it
--   wants to say "this was also filed on Rigspolitiet".
--
-- WHY capture_uuid IS NOT NULL, AND BACKFILLED TO uuid
--   Every row belongs to a capture, including the rows that predate this
--   migration; a NULLable grouping key would mean every reader carries a
--   "or it is null, in which case it is its own group" branch forever. The
--   backfill makes each existing row a capture of one, which is exactly what
--   it is.
--
-- WHY A CHILD TABLE FOR COLLEAGUES AND NOT A COLUMN
--   account_signal_colleague follows client_plan_relation (V586) and
--   account_meeting_attendee (V588): a set is a table in this schema, never a
--   delimited string. AccountRelationshipService joins it to draw one KNOWS
--   edge per named colleague, which a LIKE over a packed column could not do
--   correctly for a uuid that is a prefix of another.
--
--   It is keyed by signal_uuid, not capture_uuid, so the graph's existing
--   `where client_uuid = ?` query joins straight onto it. That duplicates the
--   colleague rows across the N account rows of one capture — consistent with
--   the N-rows decision, which already duplicates the line itself.
--
-- NO FOREIGN KEYS -- except one
--   user_uuid stays a soft reference, matching account_signal.author_uuid
--   (V584:96) and every other table in this schema. signal_uuid DOES get a
--   real FK with ON DELETE CASCADE: the child is meaningless without its
--   parent, both rows are written by the same service in the same
--   transaction, and the 24-month retention purge (still not built) must not
--   have to remember to sweep two tables by hand.
--
-- RESERVED-WORD CHECK (MariaDB 10.x)
--   capture_uuid, signal_uuid, user_uuid, account_signal_colleague — none are
--   reserved (cf. the V534 `lines` incident that took down the staging canary
--   on 2026-08-26).
--
-- COLLATION
--   utf8mb4_general_ci: signal_uuid joins account_signal and user_uuid joins
--   the legacy `user` table; unicode_ci against a general_ci table fails at
--   RUNTIME with ERROR 1267 "Illegal mix of collations" (the V315 incident).
--
-- STAGING SYNC
--   account_signal_colleague joins the sp_sync_prod_to_staging exclusion list
--   in §4. MySQL cannot patch a stored procedure, so the whole body is
--   re-emitted: §4 is a VERBATIM copy of the definition from V590 — the
--   NEWEST one — with a single name added to the NOT IN list. If you re-emit
--   this proc again, copy from the newest migration that defines it, never
--   from an older one, or you silently revert later exclusions.
--
-- NO NEW PERMISSION KEY
--   Capture is still signals:write (V584) and reading is still accounts:read
--   (V585). Naming two accounts instead of one is the same act by the same
--   people; inventing a key for it would be the V586:61 mistake.
--
-- Idempotency: ADD COLUMN IF NOT EXISTS / ADD KEY IF NOT EXISTS / CREATE
--   TABLE IF NOT EXISTS, a state-guarded backfill, and a MODIFY that is
--   naturally idempotent. Safe to re-run by hand in any environment.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   Re-emit sp_sync_prod_to_staging from V590 to drop the
--   account_signal_colleague exclusion. The COLUMN and the TABLE are
--   deliberately left in place, as V584 leaves account_signal: they hold what
--   colleagues already told us, and rolling a feature back is never a reason
--   to destroy that. Nothing reads capture_uuid if the code is rolled back —
--   it is additive.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The grouping key: one capture, N account rows
--
--    Added NULLable, backfilled, then tightened. Adding it NOT NULL in one
--    step would need a DEFAULT, and the only honest default for "which
--    capture is this row part of" is the row's own uuid — which is a
--    backfill, not a default.
-- ----------------------------------------------------------------------------
ALTER TABLE account_signal
    ADD COLUMN IF NOT EXISTS capture_uuid VARCHAR(36) NULL
        COMMENT 'The capture this row belongs to. One capture naming N accounts writes N rows sharing this uuid (V593)'
        AFTER uuid;

UPDATE account_signal
   SET capture_uuid = uuid
 WHERE capture_uuid IS NULL;

ALTER TABLE account_signal
    MODIFY COLUMN capture_uuid VARCHAR(36) NOT NULL
        COMMENT 'The capture this row belongs to. One capture naming N accounts writes N rows sharing this uuid (V593)';

-- Readers group by capture to say "also filed on Rigspolitiet"; the account
-- page still reads by (client_uuid, created_at), which idx_account_signal_client_created
-- (V584:109) already serves.
ALTER TABLE account_signal
    ADD KEY IF NOT EXISTS idx_account_signal_capture (capture_uuid);

-- ----------------------------------------------------------------------------
-- 2. The colleagues a capture named
--
--    "…som jeg har mødt i KOMBIT sammen Tobias Kjølsen" says two Trustworks
--    people know Dorte, not one. Without this table the relationship graph
--    can only ever draw the author, and the second name is thrown away at the
--    extractor — AccountSignalPrompts used to instruct the model to discard
--    colleagues outright.
--
--    NOT the author. author_uuid on the parent row already records who heard
--    it; a row here for the author as well would double every KNOWS edge in
--    AccountRelationshipService. The service filters the author out before it
--    writes, and the unique key makes a duplicate impossible either way.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_signal_colleague
(
    uuid        VARCHAR(36) NOT NULL COMMENT 'Primary key, minted by the service',
    signal_uuid VARCHAR(36) NOT NULL COMMENT 'The account_signal row this belongs to',
    user_uuid   VARCHAR(36) NOT NULL COMMENT 'A Trustworks colleague the line named (soft ref to user.uuid)',
    created_at  DATETIME(6) NOT NULL COMMENT 'Capture time, copied from the parent row',
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_account_signal_colleague (signal_uuid, user_uuid),
    KEY idx_account_signal_colleague_user (user_uuid),
    CONSTRAINT fk_account_signal_colleague_signal FOREIGN KEY (signal_uuid)
        REFERENCES account_signal (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'CRM spec 3.4/3.7 -- the Trustworks colleagues one capture named, besides its author. Internal uuids only; the third-party name it relates to lives on the parent account_signal row and carries that row''s retention.';

-- ----------------------------------------------------------------------------
-- 3. Existing rows keep their meaning
--
--    Nothing to migrate. Every pre-V593 row is a capture of one (backfilled in
--    §1) and named no colleagues (there was nowhere to put one), so an empty
--    account_signal_colleague is the correct and complete state for them.
--
--    The one production row this migration was written for -- the KOMBIT
--    capture of 2026-09-13 -- is NOT repaired here. Filing it on Rigspolitiet
--    too is a data decision about one colleague's sentence, not a schema
--    change, and a migration is the wrong place to invent a signal nobody
--    typed. It is handed over as a separate, reviewable statement.
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- 4. Staging sync -- account_signal_colleague must never be copied
--
--    VERBATIM re-emission of sp_sync_prod_to_staging as defined in V590 (the
--    newest definition), with one name added to the NOT IN list. See the
--    STAGING SYNC note in the header before touching this.
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
