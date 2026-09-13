-- ============================================================================
-- V584: Account signals — the "Heard something?" capture
-- ============================================================================
-- Feature: CRM spec §3.4 / §4.6 (docs/specs/intra-crm-spec-2026-09-12.md).
-- Domain:  aggregates/crm/signal + platform authorization (permission grant)
--
-- WHAT THIS IS
--   Any employee types one line about a client — "@Ørsted hired a new head of
--   AI called Benny Hoffmann whom I know from school" — an extractor reads the
--   person, their role, how the author knows them and what kind of signal it
--   is, and the row lands against the client. It is the one deliberate
--   "we all sell" input, replacing both the suspect list and the contact
--   database nobody would ever maintain.
--
--   This migration ships the CAPTURE path only. The read surfaces (the account
--   plan's "People & what we've heard", the owner's queue, the timeline) and
--   the decide actions are specified but not built, which is why status,
--   lead_uuid, decided_by and decided_at exist here but are never written yet:
--   the decide surface must not need a schema change to arrive.
--
-- WHY A NEW PERMISSION KEY
--   Capture is open to every employee. crm:write means the SALES tier in this
--   codebase (V468 grants it to SALES/ADMIN/PARTNER only, and the frontend
--   reads isSales: can("crm:write")), so it would 403 most of the firm; and
--   crm:read is a READ key that should not authorize a write. So signals:write
--   is its own key, granted to role USER — the recruitment:refer precedent.
--
--   Because every employee holds it, the frontend access ratchet correctly
--   classifies the BFF route as an ungated write: signals:write is listed in
--   scanner.ts UNIVERSAL_PERMISSIONS and the route carries a documented line
--   in src/access/ungated-write-baseline.json. That is deliberate and visible,
--   not an oversight — the alternative was to let a universally-held key pass
--   as a per-user gate, which is exactly the technicality that list prevents.
--
-- WHY data_scope = 'ALL'
--   DbAuthzStore.loadEffectivePermissions filters on data_scope = 'ALL'; any
--   other scope is invisible to the BFF's requirePermission() and the UI's
--   can(), so the capture button would never render. Same reasoning as V525.
--
-- WHY A NEW MIGRATION AND NOT AN EDIT TO V464
--   V464 (the generated catalogue seed) has been regenerated to include
--   signals:write so the seed-drift gate passes, but V464 has already run in
--   every environment — repair-at-start realigns its checksum WITHOUT
--   re-running it, so the regeneration inserts nothing anywhere. The
--   permission row must be inserted here, and BEFORE the grant below:
--   role_permission.permission_key is an FK onto permission.permission_key
--   (fk_role_permission_permission, V462).
--
-- NO FOREIGN KEYS
--   client_uuid / author_uuid / lead_uuid are soft references, matching every
--   other table in this schema (V219 client_activity_log, V566, V568): the
--   domain is loosely coupled on purpose and a hard constraint would make a
--   client merge or a user cleanup fail on signal rows.
--
-- RESERVED-WORD CHECK
--   SIGNAL *is* a MariaDB reserved word, so the free-text column is
--   signal_text — an unquoted reserved word fails CREATE TABLE at parse time,
--   which is how V534's `lines` column took down the staging canary on
--   2026-08-26. account_signal as a TABLE name is safe.
--
-- COLLATION
--   utf8mb4_general_ci: client_uuid joins the legacy `client` table, and
--   unicode_ci against a general_ci table fails at RUNTIME with ERROR 1267
--   "Illegal mix of collations" (the V315 incident).
--
-- STAGING SYNC
--   account_signal is added to the sp_sync_prod_to_staging exclusion list
--   below — it holds third-party personal data. MySQL cannot patch a stored
--   procedure, so the whole body is re-emitted: this is a VERBATIM copy of the
--   definition from V565 (the current one) with a single line added to the
--   NOT IN list. If you re-emit this proc again, copy from the newest
--   migration that defines it, never from an older one, or you silently
--   revert later exclusions.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS and INSERT ... ON DUPLICATE KEY
--   UPDATE throughout; safe to re-run by hand in any environment.
--
-- Author: Claude Code
-- Date:   2026-09-12
-- Rollback:
--   UPDATE role_permission SET revoked_at = NOW(), modified_by = 'V584-rollback'
--    WHERE permission_key = 'signals:write';
--   The permission row is left in place — revocation is a tombstone in this
--   schema, never a delete. The TABLE is left in place too: it holds captured
--   signals, and rolling a feature back is never a reason to destroy what
--   colleagues already told us.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The capture table
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_signal
(
    uuid          VARCHAR(36)  NOT NULL COMMENT 'Primary key, minted by the service',
    client_uuid   VARCHAR(36)  NOT NULL COMMENT 'The client the signal is about (soft ref to client.uuid)',
    author_uuid   VARCHAR(36)  NOT NULL COMMENT 'The employee who heard it, from X-Requested-By (soft ref to user.uuid)',
    source        VARCHAR(20)  NOT NULL COMMENT 'INTRA | SLACK_COMMAND | SLACK_REACTION -- only INTRA is reachable today',
    signal_text   TEXT         NOT NULL COMMENT 'The verbatim line as typed, @Client prefix included',
    person_name   VARCHAR(255) NULL     COMMENT 'Extracted third-party person; NULL when the line named nobody',
    person_role   VARCHAR(255) NULL     COMMENT 'Extracted role, e.g. "Head of AI (new)"',
    relation_text VARCHAR(500) NULL     COMMENT 'How the author knows them, phrased from the author',
    signal_type   VARCHAR(20)  NOT NULL COMMENT 'ORG_CHANGE | COMING_PROJECT | CONTACT_MOVED | TENDER | OTHER',
    status        VARCHAR(20)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW | LEAD_CREATED | PARKED | NOT_RELEVANT -- nothing leaves NEW in this cut',
    lead_uuid     VARCHAR(36)  NULL     COMMENT 'Set when status becomes LEAD_CREATED (soft ref to sales_lead.uuid)',
    decided_by    VARCHAR(36)  NULL     COMMENT 'The owner who decided (soft ref to user.uuid)',
    decided_at    DATETIME(6)  NULL     COMMENT 'When the owner decided',
    created_at    DATETIME(6)  NOT NULL COMMENT 'Capture time',
    PRIMARY KEY (uuid),
    KEY idx_account_signal_client_created (client_uuid, created_at),
    KEY idx_account_signal_status (status),
    KEY idx_account_signal_author (author_uuid),
    CONSTRAINT chk_account_signal_source
        CHECK (source IN ('INTRA', 'SLACK_COMMAND', 'SLACK_REACTION')),
    CONSTRAINT chk_account_signal_type
        CHECK (signal_type IN ('ORG_CHANGE', 'COMING_PROJECT', 'CONTACT_MOVED', 'TENDER', 'OTHER')),
    CONSTRAINT chk_account_signal_status
        CHECK (status IN ('NEW', 'LEAD_CREATED', 'PARKED', 'NOT_RELEVANT'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'CRM spec 3.4 -- one line a colleague heard about a client. Third-party personal data; agreed retention is 24 months after the account last saw activity, but the purge job is NOT built in this cut.';

-- ----------------------------------------------------------------------------
-- 2. The permission row (FK target of role_permission.permission_key)
--
--    Metadata only on conflict, matching the generated seed's contract: never
--    touch state, revoked_at, origin or enforce_acting_user, so a key marked
--    STALE or revoked stays that way across a re-deploy.
-- ----------------------------------------------------------------------------
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('signals:write', 'Account signals — capture', NULL, 'CRM', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

-- ----------------------------------------------------------------------------
-- 3. The grant -- every employee, because that is the whole point
-- ----------------------------------------------------------------------------
INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES ('USER', 'signals:write', 'ALL', NOW(), 'V584')
ON DUPLICATE KEY UPDATE
    -- revoked_at is deliberately NOT reset. Clearing it would make a re-run of this
    -- migration silently undo the documented rollback below, which revokes the grant
    -- by tombstone. An intentional re-grant is an explicit UPDATE, never a side effect
    -- of re-running a migration.
    data_scope  = VALUES(data_scope),
    updated_at  = NOW(),
    modified_by = 'V584';

-- ----------------------------------------------------------------------------
-- 4. Keep prod signal rows out of staging -- verbatim re-emission of V565's
--    procedure with 'account_signal' added to the NOT IN list.
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
