-- ===================================================================
-- V311: Create recruitment_candidates table
-- ===================================================================
-- Feature: Recruitment Dossier
-- Domain:  recruitmentservice (new bounded context)
--
-- Purpose:
--   Aggregate root for the Recruitment Dossier feature. Stores a
--   prospective hire BEFORE a User row exists. A candidate becomes a
--   User only after CandidateConversionUseCase succeeds (status -> HIRED).
--
-- Lifecycle:
--   ACTIVE -> HIRED      (terminal; sets converted_user_uuid)
--   ACTIVE -> DECLINED   (terminal; sets decline_reason)
--   ACTIVE -> WITHDRAWN  (terminal; sets decline_reason)
--   Terminal transitions auto-close all open dossiers (see V312).
--
-- Cross-context references (soft-FK pattern, NO DB FK constraint):
--   target_company_uuid   -> Company (companies.uuid)
--   created_by_useruuid   -> User    (users.uuid)
--   converted_user_uuid   -> User    (users.uuid; populated on HIRED)
--   These match the project pattern used by signing_cases.user_uuid.
--
-- SharePoint move (column-as-queue):
--   sharepoint_move_status drives the post-convert copy-and-delete
--   batchlet (V311+). NULL until the conversion flow enqueues a move.
--   Lifecycle: PENDING -> COPIED -> COMPLETED, or PARTIAL / FAILED.
--
-- Author: Claude Code
-- Date:   2026-05-04
-- Rollback: DROP TABLE recruitment_candidates;
-- ===================================================================

START TRANSACTION;

CREATE TABLE recruitment_candidates (
    -- Primary key
    uuid VARCHAR(36) PRIMARY KEY
        COMMENT 'Aggregate root identifier for a recruitment candidate',

    -- Identity
    first_name VARCHAR(100) NOT NULL
        COMMENT 'Candidate first name (required at creation)',
    last_name  VARCHAR(100) NOT NULL
        COMMENT 'Candidate last name (required at creation)',
    email      VARCHAR(255) NOT NULL
        COMMENT 'Candidate email address; used as recipient for review/signature actions',
    phone      VARCHAR(50)  NULL
        COMMENT 'Optional contact phone number',

    -- Target hire details
    target_company_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK to companies.uuid (NO DB FK constraint, cross-context reference)',
    target_start_date   DATE        NULL
        COMMENT 'Optional planned start date for the candidate',
    notes               TEXT        NULL
        COMMENT 'Free-form recruiter notes',

    -- State machine
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'Candidate status enum (Java @Enumerated(EnumType.STRING)): ACTIVE | HIRED | DECLINED | WITHDRAWN',
    decline_reason TEXT NULL
        COMMENT 'Human-readable reason; populated on DECLINED or WITHDRAWN',

    -- Conversion outcome (set on HIRED)
    converted_user_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK to users.uuid (NO DB FK constraint); populated when candidate is converted to an employee',

    -- SharePoint async move queue
    sharepoint_folder_path VARCHAR(512) NULL
        COMMENT 'Lazy-created SharePoint folder path; populated on first signature send',
    sharepoint_move_status VARCHAR(20)  NULL
        COMMENT 'Move queue status enum (Java @Enumerated(EnumType.STRING)): PENDING | COPIED | COMPLETED | PARTIAL | FAILED',

    -- Audit fields (creator only; updated_by tracked via app-layer audit log)
    created_by_useruuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK to users.uuid (NO DB FK constraint); the manager who created this candidate',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP                                  NOT NULL
        COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP      NOT NULL
        COMMENT 'Last modification timestamp',

    -- Data integrity
    CONSTRAINT chk_rc_status_enum
        CHECK (status IN ('ACTIVE','HIRED','DECLINED','WITHDRAWN')),
    CONSTRAINT chk_rc_sharepoint_move_status_enum
        CHECK (sharepoint_move_status IS NULL
               OR sharepoint_move_status IN ('PENDING','COPIED','COMPLETED','PARTIAL','FAILED')),
    CONSTRAINT chk_rc_email_not_empty
        CHECK (CHAR_LENGTH(TRIM(email)) > 0)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Recruitment Dossier: prospective hires before User creation';

-- Indexes per AC #1
CREATE INDEX idx_rc_status      ON recruitment_candidates(status);
CREATE INDEX idx_rc_company     ON recruitment_candidates(target_company_uuid);
CREATE INDEX idx_rc_email       ON recruitment_candidates(email);
CREATE INDEX idx_rc_created_by  ON recruitment_candidates(created_by_useruuid);

COMMIT;

-- ===================================================================
-- Migration Notes
-- ===================================================================
-- - All cross-context FKs are soft refs (CHAR(36)/VARCHAR(36) with no DB
--   FK constraint), matching signing_cases.user_uuid pattern.
-- - status and sharepoint_move_status enums are validated by both a CHECK
--   constraint AND Hibernate @Enumerated(EnumType.STRING). The CHECK
--   constraint is the DB-level guard; the column comment additionally
--   documents the values for ad-hoc DB readers.
-- - sharepoint_move_status is NULLABLE: it's only set once the convert
--   flow enqueues a move. Existing rows for terminal candidates that
--   never went through convert remain NULL forever.
-- - No FK from converted_user_uuid to users.uuid because this column may
--   be set to a UUID created in the same transaction; cross-context refs
--   stay soft per project convention.
-- - Reapply: DROP TABLE recruitment_candidates; (no dependent tables yet
--   in this migration; V312-V314 add internal FKs in subsequent files).
-- ===================================================================
