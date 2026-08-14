-- ===================================================================
-- V495: Recruitment ATS — Method B candidate-option scheduling core
-- ===================================================================
-- Feature: Interview scheduling Method B Phase 8 (plan 2026-08-12) —
--          scheduling requests, proposed slots, interviewer approvals,
--          calendar holds, candidate option batches and the
--          transactional outbox that carries every external write.
-- Domain:  recruitmentservice (interview loop)
--
-- WHY
--   Method B lets the backend secure 1–3 interview options: it proposes
--   slots to interviewers in Slack, protects approved slots with
--   attendee-less calendar holds (D5), offers the options to the
--   candidate on a public page and finalizes the chosen one through the
--   Method A two-event machinery. Deploys kill in-flight jobs and the
--   ALB caps requests at 60 s, so the whole workflow is DB-persisted
--   state advanced by short idempotent steps — these tables ARE the
--   workflow. No in-memory orchestration exists anywhere.
--
-- Design notes:
--   * recruitment_scheduling_outbox follows the invoice-booking outbox
--     idiom (invoice_booking_attempt, V477): the state change and the
--     intended external action commit together; a dispatcher sweep
--     executes with a per-action idempotency key and an atomic claim,
--     so two instances (blue/green overlap) never double-execute.
--   * recruitment_option_batch stores only the SHA-256 of the candidate
--     token — the raw capability token is never persisted.
--   * recruitment_calendar_hold: one row per interviewer per slot plus
--     one for the room (D5 = N attendee-less events). The hold uuid is
--     the Graph transactionId, so a retried create never double-books.
--   * Statuses are CHECK-pinned like V490 — every state is known
--     upfront from spec §15; a new state is a deliberate migration.
--   * Collation utf8mb4_general_ci, matching the recruitment siblings.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is IF NOT EXISTS and the procedure is a full redefinition.
--
-- Author: Claude Code
-- Date:   2026-08-14
-- Rollback: inert without the backend image that writes these tables
--   (everything is dark behind dk.trustworks.recruitment.scheduling.
--   methodb.enabled). Full removal:
--     DROP TABLE recruitment_scheduling_outbox;
--     DROP TABLE recruitment_option_batch;
--     DROP TABLE recruitment_calendar_hold;
--     DROP TABLE recruitment_slot_approval;
--     DROP TABLE recruitment_proposed_slot;
--     DROP TABLE recruitment_scheduling_request;
--   (and re-apply V490's sp_sync_prod_to_staging to drop the exclusions).
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. The scheduling request — one Method B run on one application
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_scheduling_request (
    uuid VARCHAR(36) NOT NULL,
    application_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK recruitment_applications.uuid — the pipeline run being scheduled.',
    recruiter_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft FK users.uuid — who started Method B; receives escalations and handbacks.',

    -- Interview parameters mirroring the Method A create path
    kind VARCHAR(10) NOT NULL
        COMMENT 'ROUND or INFORMAL — same vocabulary as recruitment_interviews.kind.',
    round INT NULL
        COMMENT '1..3 for ROUND, NULL for INFORMAL.',
    duration_minutes INT NOT NULL DEFAULT 60,
    online_meeting BIT(1) NOT NULL DEFAULT b'0'
        COMMENT 'The finalized interview becomes a Teams meeting.',
    require_room BIT(1) NOT NULL DEFAULT b'0'
        COMMENT 'Only slots with a bookable free room qualify.',
    location VARCHAR(200) NULL
        COMMENT 'PII-free free-text location fallback, as on interviews.',
    interviewer_uuids JSON NOT NULL
        COMMENT 'Soft FKs users.uuid — REQUIRED interviewers; every one must approve a slot.',
    optional_interviewer_uuids JSON NULL
        COMMENT 'Soft FKs users.uuid — optional interviewers (defaults §29.7: never block, rank slots where they are free higher).',

    -- Method B option parameters
    requested_options TINYINT NOT NULL DEFAULT 3
        COMMENT '1–3 options to secure for the candidate.',
    window_start DATE NOT NULL,
    window_end DATE NOT NULL
        COMMENT 'Inclusive date window the options must fall in.',
    permitted_start TIME NULL,
    permitted_end TIME NULL
        COMMENT 'Optional wall-clock band inside the 07:00–19:00 probe window.',
    min_separation_hours INT NOT NULL DEFAULT 0
        COMMENT 'Minimum gap between any two offered options.',
    different_days BIT(1) NOT NULL DEFAULT b'0'
        COMMENT 'Every offered option must fall on a different calendar day.',
    candidate_deadline DATETIME NULL
        COMMENT 'Explicit candidate answer-by override; NULL = computed at send time (3 business days, defaults §29.2).',
    automation_deadline DATETIME NOT NULL
        COMMENT 'Two-week anchor (defaults §29.18): unfinished by here => holds released, request handed back.',
    review_required BIT(1) NOT NULL DEFAULT b'1'
        COMMENT 'D11: the recruiter reviews & sends the secured options; default ON.',
    options_approved_at DATETIME NULL
        COMMENT 'When the recruiter approved the option batch for sending (D11 review action).',

    status VARCHAR(26) NOT NULL DEFAULT 'DRAFT',
    handback_reason VARCHAR(1000) NULL
        COMMENT 'Why the request left automation (structural text, no candidate PII).',
    next_action_at DATETIME NULL
        COMMENT 'Advance-sweep pacing: NULL = act on next sweep; future = wait (e.g. search retry backoff).',
    version INT NOT NULL DEFAULT 0
        COMMENT 'JPA optimistic lock — sweeps and handlers race safely.',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(36) NOT NULL,
    modified_by VARCHAR(36) NULL,

    PRIMARY KEY (uuid),
    KEY idx_rsr_application (application_uuid),
    KEY idx_rsr_status_next (status, next_action_at),
    CONSTRAINT fk_rsr_application FOREIGN KEY (application_uuid)
        REFERENCES recruitment_applications (uuid),
    CONSTRAINT chk_rsr_status_enum CHECK (status IN (
        'DRAFT','SEARCHING','WAITING_FOR_INTERVIEWERS','HOLDING_OPTIONS',
        'READY_FOR_CANDIDATE','WAITING_FOR_CANDIDATE','FINALIZING',
        'SCHEDULED','HANDED_BACK','EXPIRED','CANCELLED')),
    CONSTRAINT chk_rsr_requested_options CHECK (requested_options BETWEEN 1 AND 3)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Method B: one candidate-option scheduling run on one application (spec §15 state machine)';

-- -------------------------------------------------------------------
-- 2. Proposed slots — the concrete times moving through the pipeline
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_proposed_slot (
    uuid VARCHAR(36) NOT NULL,
    request_uuid VARCHAR(36) NOT NULL,
    option_no INT NOT NULL
        COMMENT 'Per-request sequence (1,2,3,…) — the "mulighed i/n" label in hold subjects (D12: no candidate name there).',
    slot_start DATETIME NOT NULL
        COMMENT 'Wall-clock Europe/Copenhagen, as everywhere in the interview loop.',
    slot_end DATETIME NOT NULL,
    room_email VARCHAR(255) NULL
        COMMENT 'The bookable room secured for this slot; NULL = no room.',
    room_name VARCHAR(200) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED',
    reject_reason VARCHAR(200) NULL
        COMMENT 'Structural rejection cause: INTERVIEWER_DECLINED | RECHECK_CONFLICT | HOLD_FAILURE | HOLD_LOST | RECRUITER_RELEASED | …',
    expires_at DATETIME NULL
        COMMENT 'Candidate deadline + 1 h buffer once offered — the hold-cleanup sweep releases past-due slots.',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid),
    KEY idx_rps_request_status (request_uuid, status),
    CONSTRAINT fk_rps_request FOREIGN KEY (request_uuid)
        REFERENCES recruitment_scheduling_request (uuid),
    CONSTRAINT chk_rps_status_enum CHECK (status IN (
        'DISCOVERED','PROPOSED','PARTIALLY_APPROVED','APPROVED','RECHECKING',
        'HELD','OFFERED','SELECTED','FINALIZED','REJECTED','RELEASED','EXPIRED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Method B: one concrete interview time under consideration for one scheduling request';

-- -------------------------------------------------------------------
-- 3. Per-interviewer approvals on a slot (the Slack DM loop)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_slot_approval (
    uuid VARCHAR(36) NOT NULL,
    slot_uuid VARCHAR(36) NOT NULL,
    user_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft FK users.uuid — the required interviewer this approval belongs to.',
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    responded_at DATETIME NULL,
    slack_channel_id VARCHAR(30) NULL
        COMMENT 'The DM channel the proposal card was posted to — chat.update target.',
    slack_message_ts VARCHAR(30) NULL
        COMMENT 'The proposal card message ts — updated in place on approve/decline.',
    nudge_count INT NOT NULL DEFAULT 0
        COMMENT 'Silence reminders sent (defaults §29.16: 24 h, 72 h, then recruiter escalation).',
    last_nudged_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid),
    UNIQUE KEY uk_rsa_slot_user (slot_uuid, user_uuid),
    CONSTRAINT fk_rsa_slot FOREIGN KEY (slot_uuid)
        REFERENCES recruitment_proposed_slot (uuid),
    CONSTRAINT chk_rsa_status_enum CHECK (status IN ('PENDING','APPROVED','DECLINED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Method B: one required interviewer''s answer to one proposed slot';

-- -------------------------------------------------------------------
-- 4. Calendar holds — attendee-less Graph events protecting a slot (D5)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_calendar_hold (
    uuid VARCHAR(36) NOT NULL
        COMMENT 'Also the Graph transactionId of the create — a retried create never double-books.',
    slot_uuid VARCHAR(36) NOT NULL,
    owner_kind VARCHAR(4) NOT NULL
        COMMENT 'USER = an interviewer''s own calendar; ROOM = direct write into the room mailbox (Phase 7.5 spike: allowed in this tenant).',
    user_uuid VARCHAR(36) NULL
        COMMENT 'Soft FK users.uuid for USER holds; NULL for ROOM.',
    mailbox VARCHAR(255) NOT NULL
        COMMENT 'The calendar the hold event lives in.',
    graph_event_id VARCHAR(255) NULL
        COMMENT 'NULL until the outbox CREATE_HOLD action succeeded.',
    status VARCHAR(10) NOT NULL DEFAULT 'CREATED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_verified_at DATETIME NULL
        COMMENT 'Reconciliation sweep: last time Graph confirmed the event still exists.',
    released_at DATETIME NULL,

    PRIMARY KEY (uuid),
    KEY idx_rch_slot (slot_uuid),
    KEY idx_rch_status (status),
    CONSTRAINT fk_rch_slot FOREIGN KEY (slot_uuid)
        REFERENCES recruitment_proposed_slot (uuid),
    CONSTRAINT chk_rch_owner_enum CHECK (owner_kind IN ('USER','ROOM')),
    CONSTRAINT chk_rch_status_enum CHECK (status IN ('CREATED','VERIFIED','MISSING','RELEASED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Method B: one attendee-less [HOLD] calendar event per interviewer/room per held slot (D5, D12)';

-- -------------------------------------------------------------------
-- 5. Candidate option batches — the public page capability
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_option_batch (
    uuid VARCHAR(36) NOT NULL,
    request_uuid VARCHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL
        COMMENT 'SHA-256 hex of the 256-bit capability token. The raw token is NEVER stored.',
    sent_at DATETIME NULL,
    expires_at DATETIME NOT NULL
        COMMENT 'The candidate deadline — the public page answers a uniform 404 past this.',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid),
    UNIQUE KEY uk_rob_token_hash (token_hash),
    KEY idx_rob_request (request_uuid),
    CONSTRAINT fk_rob_request FOREIGN KEY (request_uuid)
        REFERENCES recruitment_scheduling_request (uuid),
    CONSTRAINT chk_rob_status_enum CHECK (status IN ('ACTIVE','CLOSED','EXPIRED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Method B: one tokenized option set offered to the candidate; at most one ACTIVE per request';

-- -------------------------------------------------------------------
-- 6. The scheduling outbox — every external write goes through here
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_scheduling_outbox (
    uuid VARCHAR(36) NOT NULL,
    request_uuid VARCHAR(36) NOT NULL,
    slot_uuid VARCHAR(36) NULL,
    action VARCHAR(40) NOT NULL
        COMMENT 'SEND_PROPOSAL_DM | CREATE_HOLD | DELETE_HOLD | SEND_RECRUITER_DM | … — executor registry key.',
    idempotency_key VARCHAR(200) NOT NULL
        COMMENT 'request+slot+action+version (plan §8.3) — the same intended action is never enqueued twice.',
    payload_json TEXT NULL
        COMMENT 'Structural action parameters only (uuids, mailboxes, option numbers). NEVER free text or candidate PII — that lives in event pii blocks.',
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    next_attempt_at DATETIME NOT NULL
        COMMENT 'Due time; exponential backoff on retry.',
    claimed_at DATETIME NULL
        COMMENT 'When an instance atomically claimed the row (PENDING -> IN_PROGRESS). Stale claims are re-eligible after the claim timeout.',
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid),
    UNIQUE KEY uk_rso_idempotency (idempotency_key),
    KEY idx_rso_due (status, next_attempt_at),
    KEY idx_rso_request (request_uuid),
    CONSTRAINT fk_rso_request FOREIGN KEY (request_uuid)
        REFERENCES recruitment_scheduling_request (uuid),
    CONSTRAINT chk_rso_status_enum CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Method B transactional outbox: state change + intended external write commit together; a dispatcher sweep executes with idempotency keys (invoice-booking idiom)';

-- -------------------------------------------------------------------
-- 7. Extend the prod→staging sync exclusion list.
--    Established pattern (V258, V453/V457, V466, V490): the FULL
--    procedure body below is copied VERBATIM from V490 — the latest
--    declaration — with exactly one change: the six Method B scheduling
--    tables appended to the TABLE_NAME NOT IN (...) list beside their
--    recruitment siblings, marked with a V495 comment.
--
--    They belong on the list for the same reason the rest of the
--    recruitment block does: slots/approvals/holds reference candidate
--    pipelines (GDPR-governed, not anonymized in the sync), and outbox/
--    batch rows carry prod Slack channel ids, Graph event ids and token
--    hashes that mean nothing — or are actively dangerous to act on —
--    in staging.
-- -------------------------------------------------------------------

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
              'recruitment_scheduling_request',   -- V495
              'recruitment_proposed_slot',        -- V495
              'recruitment_slot_approval',        -- V495
              'recruitment_calendar_hold',        -- V495
              'recruitment_option_batch',         -- V495
              'recruitment_scheduling_outbox',    -- V495
              'recruitment_signing_completed_cases',
              'recruitment_slack_inbound_dedupe',
              'recruitment_email_templates',
              'recruitment_pending_emails',
              'recruitment_fact_monthly',
              'recruitment_slack_threads',
              'recruitment_slack_channels',
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
