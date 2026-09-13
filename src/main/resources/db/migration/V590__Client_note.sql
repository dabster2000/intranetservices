-- ============================================================================
-- V590: Client notes — the one line somebody typed
-- ============================================================================
-- Feature: CRM spec §4.3 tab 3 ("one-line manual note, optional",
--          docs/specs/intra-crm-spec-2026-09-12.md:313) / §3.2.
-- Domain:  aggregates/crm/note
--
-- WHAT THIS IS
--   The account page's Timeline tab has shipped since 2026-09-12 with a "Notes" chip
--   (AccountTimelineTab.tsx:61,74) filtering source NOTE. AccountActivityDTO names NOTE
--   in the contract and accountTypes.ts types it, but AccountActivityService.forClient
--   unioned seven producers and none of them was NOTE. The chip has always filtered to
--   nothing. This is the table behind it.
--
-- THE ONE HAND-TYPED SOURCE, AND WHY THAT IS A CHANGE
--   Spec §3.2 calls the activity feed derived and read-only, and
--   AccountActivityService's class javadoc said "Nothing in it is typed by hand". A note
--   breaks that sentence on purpose: it is the escape hatch for what no system saw. Both
--   javadocs are corrected in this change rather than left to contradict the code.
--
-- WHY note_text AND NOT note (RESERVED-WORD CHECK)
--   `note` is not reserved and already ships unquoted as a column in client_band_history
--   (V585:121), but `text` is a type name and the house rule for free-text columns is
--   <thing>_text: signal_text (V584:97, because SIGNAL *is* reserved), sentence_text
--   (V586), relation_text (V584:99). `LINES` is the reserved word that took down the
--   staging canary on 2026-08-26 (V585:45-47). `client_note` as a table name is safe and
--   unused anywhere in db/migration or src/main/java.
--
-- WHY THE COLUMN IS 500 AND THE SERVICE REFUSES AT 280
--   A signal gets TEXT and a 2000-character service cap (V584:97,
--   AccountSignalService.MAX_TEXT_CHARS) because it is a captured quote. A note is "one
--   line" (spec :313), and the cap is the privacy control rather than advice: nobody
--   pastes a meeting transcript naming eight people into one line.
--   ClientNoteService.MAX_NOTE_CHARS is 280 and REFUSES an over-long line with a 400
--   rather than truncating it — the author is looking at the box, and publishing half
--   their sentence on the most-read surface of the account page is worse than asking them
--   to shorten it. The column is deliberately wider than the cap so that raising 280 later
--   is one constant in each repo and not an ALTER, which is the one migration shape this
--   whole series has managed to avoid.
--
-- WHY A NOTE CAN BE REMOVED BUT NOT REWRITTEN
--   There is no edit path, and therefore no modified_at / modified_by: the row carries a
--   creation stamp only, exactly like account_signal (V584:107). The realistic failure is
--   a line that named somebody it should not have, and the remedy for that is removal, not
--   a rewrite — a rewritten line would leave a dated timeline asserting that what stands
--   there now is what was written then. Removal is a hard delete: there is no deleted_at
--   column. No table in V584-V589 has one, and on a table of third-party free text a soft
--   delete makes "delete" mean "hidden but retained", which is precisely what an erasure
--   request forbids; it would also add `and deleted_at is null` to noteRows, to
--   lastActivityForAll and to the list endpoint, where one forgotten predicate puts the
--   line back on the most-read surface of the page.
--
-- WHY author_uuid AND NOT created_by
--   The author of a note is content, not an audit stamp: the row means nothing without it,
--   and the timeline names them. Same column name and the same reason as
--   account_signal.author_uuid (V584:96).
--
-- THIRD-PARTY PERSONAL DATA — AN OPEN GAP, RECORDED
--   A note may name a real person at a client organisation, exactly like account_signal
--   (V584:118-121, AccountSignal.java:35-45) and account_meeting_attendee (V588:42-47).
--   It is worse in one specific way: a signal's name sits in a structured person_name
--   column that a purge job nulls with one UPDATE, and a note's name is inside free text
--   with no structure at all, so the only erasure primitive that works here is deleting
--   the row. The agreed retention is 24 months after the account's last activity (spec
--   §3.4, and the gap register at :378). THE PURGE JOB IS NOT BUILT. Do not read this as
--   an implemented control. After this migration THREE tables are waiting on it rather
--   than two, and this is the one that needs it most.
--
-- NO FOREIGN KEYS onto client / user, matching V219, V584, V585 and V587 (V585:38-42):
--   the domain is loosely coupled on purpose and a hard constraint would make a client
--   merge or a user cleanup fail on note rows.
--
-- NO CHECK CONSTRAINTS — V585-V587 dropped them in favour of service-side rules
--   (V587:19-20), and there is nothing here to constrain in any case.
--
-- COLLATION: utf8mb4_general_ci, as V585-V588 — client_uuid joins the legacy `client`
--   table, and unicode_ci against a general_ci table fails at RUNTIME with ERROR 1267
--   "Illegal mix of collations" (V585:49-52, the V315 incident).
--
-- STAGING SYNC
--   client_note joins the sp_sync_prod_to_staging exclusion list in §2. MySQL cannot
--   patch a stored procedure, so the whole body is re-emitted: §2 is a VERBATIM copy of
--   the definition from V588 — the NEWEST one — with a single name added to the NOT IN
--   list. If you re-emit this proc again, copy from the newest migration that defines it,
--   never from an older one, or you silently revert later exclusions.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS; no seed data.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   Re-emit sp_sync_prod_to_staging from V588 to drop the client_note exclusion.
--   No permission rows to revoke: notes are gated by accounts:read / accounts:write
--   (V585), so this migration invented no key (the V586:61 posture).
--   The TABLE is deliberately left in place, as V584 leaves account_signal: it holds lines
--   colleagues typed, and rolling a feature back is never a reason to destroy what they
--   wrote. Drop it by hand if the feature is abandoned for good.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The one hand-typed row in the account timeline
--
--    ONE INDEX, deliberately. idx_client_note_client (client_uuid, created_at) serves
--    both readers: noteRows' `where client_uuid = ? order by created_at desc` on the
--    account page, and lastActivityForAll's `group by client_uuid` max for the accounts
--    list. There is no idx_client_note_author, unlike idx_account_signal_author
--    (V584:111): account_signal has an owner queue that filters by status and author,
--    while every note read starts from a client and the delete authorization check is a
--    primary-key lookup.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_note
(
    uuid        CHAR(36)     NOT NULL COMMENT 'Primary key, minted by the service',
    client_uuid CHAR(36)     NOT NULL COMMENT 'The account the note is on (soft ref to client.uuid)',
    note_text   VARCHAR(500) NOT NULL COMMENT 'The one line, as typed. The service refuses over 280; the column is wider so that cap can move without an ALTER.',
    author_uuid CHAR(36)     NOT NULL COMMENT 'Who wrote it, from X-Requested-By (soft ref to user.uuid)',
    created_at  DATETIME     NOT NULL COMMENT 'When it was written. There is no modified_at: a note can be removed but never rewritten.',
    PRIMARY KEY (uuid),
    KEY idx_client_note_client (client_uuid, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='One hand-typed line on an account -- the only non-derived source in the account timeline (CRM spec 4.3). Free text that may name a third party; the 24-month retention of spec 3.4 applies and NO purge job exists.';

-- ----------------------------------------------------------------------------
-- 2. Keep prod notes out of staging — verbatim re-emission of V588's procedure
--    with one name added to the NOT IN list.
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
