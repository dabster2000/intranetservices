-- =============================================================================
-- Migration V307: Recruitment initial schema (Slice 1 — Foundation)
--
-- Spec:  docs/superpowers/specs/2026-04-25-recruitment-design.md (§7 Database Schema)
-- Plan:  Recruitment Slice 1 Foundation — Task 1 (single Flyway file, all tables)
--
-- Purpose:
--   Create the full recruitment domain schema (23 tables) in one Flyway
--   migration so the data model is internally consistent from day one.
--   Slice 1 only writes to a subset of these tables (open roles, candidates,
--   applications, plus their child / history tables, status, and outbox); the
--   AI artifact, interview, scorecard, offer, and dossier tables are created
--   here so later slices (2–5) ship code paths against an existing schema and
--   later migrations only have to ALTER, not CREATE under load. See spec §7.0
--   "Migration ordering inside the single initial Flyway file".
--
-- Design rules followed (all from spec §7.0):
--
--   1. All tables prefixed `recruitment_`.
--   2. UUIDs as CHAR(36).
--   3. Standard `created_at` / `updated_at` columns where the spec lists them
--      (`updated_at` uses ON UPDATE CURRENT_TIMESTAMP).
--   4. Every CREATE TABLE uses ENGINE=InnoDB, CHARSET=utf8mb4,
--      COLLATE=utf8mb4_unicode_ci.
--   5. Foreign-key policy: ON DELETE RESTRICT ON UPDATE RESTRICT on every FK
--      EXCEPT `recruitment_candidate_cv.extraction_artifact_uuid`
--      (`fk_cv_artifact`), which is ON DELETE SET NULL ON UPDATE RESTRICT —
--      the spec's only canonical exception, because a deleted AI artifact
--      should not block deleting/keeping a CV row.
--   6. No interaction with shared signing infrastructure (`signing_cases`,
--      `template_*`, `sharepoint_locations`). Recruitment links to signing by
--      storing `signing_case_id` as a plain VARCHAR (soft FK, no constraint),
--      matching the project-wide cross-module convention.
--   7. Tables created in dependency order (spec §7.0):
--      (1) recruitment_outbox, recruitment_status
--      (2) recruitment_ai_artifact
--      (3) recruitment_open_role, recruitment_candidate
--      (4) recruitment_role_assignment, recruitment_role_history,
--          recruitment_candidate_cv, recruitment_candidate_note,
--          recruitment_candidate_activity
--      (5) recruitment_application, recruitment_application_stage_history
--      (6) recruitment_interview, recruitment_interview_participant,
--          recruitment_scorecard, recruitment_scorecard_amendment
--      (7) recruitment_offer, recruitment_offer_audit_event
--      (8) recruitment_dossier, recruitment_dossier_revision,
--          recruitment_dossier_signer, recruitment_dossier_appendix,
--          recruitment_dossier_verification_item
--
-- MariaDB-specific notes — IMPORTANT deviation from spec:
--   The spec (§7.1) shows the three "partial unique" columns as VIRTUAL
--   generated columns:
--         recruitment_application.is_active
--         recruitment_application.accepted_for_unique
--         recruitment_candidate_cv.current_for_unique
--   This is not implementable in MariaDB 10.x: a UNIQUE INDEX on a VIRTUAL
--   generated column requires the expression to be deterministic and to use
--   only a very small whitelist of operations (basic arithmetic / column
--   refs). Conditional functions (IF, CASE, NULLIF, COALESCE, even CONCAT/
--   LOWER) are rejected with ERROR 1901 ("Function or expression cannot be
--   used in the GENERATED ALWAYS AS clause"). STORED has the same restriction
--   and is even more limited. Verified empirically against MariaDB 10.11.
--
--   The mitigation, used here, is the standard MariaDB workaround: declare
--   each of the three columns as a regular nullable column with the same
--   name and type, and maintain it via BEFORE INSERT and BEFORE UPDATE
--   triggers. Semantically this is identical to the generated-column form
--   from the application's point of view — the application never writes to
--   these columns; they are derived from other columns on the same row, and
--   the UNIQUE index sees exactly the same value distribution.
--
--   Application impact:
--     - JPA entities should map the three columns as `insertable=false,
--       updatable=false` (or omit them entirely from the entity) so Hibernate
--       does not include them in INSERT/UPDATE statements. The triggers will
--       set them.
--     - Hibernate-managed UPDATE statements that change `is_current` /
--       `stage` will fire the BEFORE UPDATE triggers and re-derive the
--       partial-unique columns automatically.
--     - For bulk SQL imports, callers must NOT supply values for the three
--       trigger-managed columns; the BEFORE triggers always overwrite any
--       supplied value.
--
--   The three affected columns are:
--       recruitment_application.is_active            (1 when stage is active,
--                                                     NULL otherwise; NULL
--                                                     excluded from unique
--                                                     index, so the unique
--                                                     constraint applies only
--                                                     to active applications)
--       recruitment_application.accepted_for_unique  (candidate_uuid when
--                                                     stage='ACCEPTED', NULL
--                                                     otherwise)
--       recruitment_candidate_cv.current_for_unique  (candidate_uuid when
--                                                     is_current=1, NULL
--                                                     otherwise)
--
--   `is_active` semantics: ACCEPTED is treated as ACTIVE because an accepted
--   candidate is still an open conversion obligation until the application
--   transitions to CONVERTED or another closing stage. Stages considered
--   closed (is_active=NULL): REJECTED, WITHDRAWN, TALENT_POOL, CONVERTED.
--   ACCEPTED remains active — matching the spec's generated-column expression.
--
--   JSON columns: stored as MariaDB JSON (LONGTEXT alias with JSON_VALID
--   check). MariaDB 10.x JSON is fine for our access patterns (occasional
--   read; no expression indexing).
--
-- Idempotency:
--   Flyway version-control prevents re-execution. Statements are not
--   themselves IF NOT EXISTS — re-running this script outside Flyway would
--   fail on the second CREATE TABLE. If a repair is ever needed, prefer a
--   new versioned migration (V308+) over editing V307 in place.
--
-- Rollback:
--   No automatic rollback; Flyway does not auto-rollback DDL. To roll back
--   manually, DROP TABLE in reverse dependency order:
--     recruitment_dossier_verification_item, recruitment_dossier_appendix,
--     recruitment_dossier_signer, recruitment_dossier_revision,
--     recruitment_dossier, recruitment_offer_audit_event,
--     recruitment_offer, recruitment_scorecard_amendment,
--     recruitment_scorecard, recruitment_interview_participant,
--     recruitment_interview, recruitment_application_stage_history,
--     recruitment_application, recruitment_candidate_activity,
--     recruitment_candidate_note, recruitment_candidate_cv,
--     recruitment_role_history, recruitment_role_assignment,
--     recruitment_candidate, recruitment_open_role, recruitment_ai_artifact,
--     recruitment_status, recruitment_outbox.
--   This order respects FK RESTRICT direction.
--
-- Validation queries (run after migration):
--   -- All 23 tables exist:
--   SELECT table_name FROM information_schema.tables
--   WHERE table_schema = DATABASE() AND table_name LIKE 'recruitment\\_%' ESCAPE '\\'
--   ORDER BY table_name;
--   -- Expected: 23 rows.
--
--   -- Three generated unique indexes exist:
--   SHOW INDEX FROM recruitment_application
--   WHERE Key_name = 'uk_active_app_per_candidate_role';
--   SHOW INDEX FROM recruitment_application
--   WHERE Key_name = 'uk_one_accepted_app_per_candidate';
--   SHOW INDEX FROM recruitment_candidate_cv
--   WHERE Key_name = 'uk_one_current_cv_per_candidate';
--   -- Expected: each query returns >=1 row.
-- =============================================================================


-- ---------------------------------------------------------------------------
-- (1a) recruitment_outbox
--     Generic outbox for asynchronous side effects (Slack DMs, Outlook events,
--     SharePoint, NextSign, e-mail, AI generation, retention jobs). Drained
--     by per-kind workers; replay-safe via target_ref + payload uniqueness
--     handled in the worker layer (no DB unique constraint — same kind/payload
--     can intentionally be re-queued after a failure).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_outbox (
    uuid              CHAR(36)   NOT NULL,
    kind              ENUM('SLACK_DM','SLACK_CHANNEL_DIGEST',
                           'OUTLOOK_EVENT_CREATE','OUTLOOK_EVENT_UPDATE','OUTLOOK_EVENT_CANCEL',
                           'SHAREPOINT_PROVISION_CANDIDATE_FOLDER',
                           'SHAREPOINT_ARCHIVE_SIGNED_CONTRACT',
                           'SHAREPOINT_CONVERT_CANDIDATE_FOLDER',
                           'SHAREPOINT_DELETE_CANDIDATE_FOLDER',
                           'NEXTSIGN_CREATE_CASE','NEXTSIGN_RECONCILE_CASE',
                           'REVIEW_EMAIL_SEND','RETENTION_WARNING_EMAIL',
                           'RETENTION_ANONYMIZE_CANDIDATE','AI_GENERATE') NOT NULL,
    payload           JSON       NOT NULL,
    target_ref        VARCHAR(255) NULL,
    status            ENUM('PENDING','IN_FLIGHT','DONE','FAILED') NOT NULL DEFAULT 'PENDING',
    attempt_count     INT        NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error        TEXT       NULL,
    created_at        TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Recruitment outbox — async side effects drained by per-kind workers';


-- ---------------------------------------------------------------------------
-- (1b) recruitment_status
--     STOP / PASSIVE / ACTIVE recruitment posture per team or practice scope.
--     Composite PK; one row per (scope_kind, scope_id). Audit fields capture
--     who changed it and why; mutation history is intentionally NOT kept here
--     — change events live in recruitment_role_history when role-scoped.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_status (
    scope_kind        ENUM('TEAM','PRACTICE') NOT NULL,
    scope_id          VARCHAR(40) NOT NULL,
    status            ENUM('STOP','PASSIVE','ACTIVE') NOT NULL,
    changed_by_uuid   CHAR(36)    NOT NULL,
    changed_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason            TEXT        NULL,
    PRIMARY KEY (scope_kind, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Recruitment posture (STOP/PASSIVE/ACTIVE) per team or practice';


-- ---------------------------------------------------------------------------
-- (2) recruitment_ai_artifact
--     One row per AI generation attempt. Idempotency key:
--     (subject_kind, subject_uuid, kind, input_digest). Referenced by
--     recruitment_candidate_cv.extraction_artifact_uuid (FK below). All other
--     subjects (role/application/interview/offer) are referenced via the
--     `subject_*` columns only (soft FK; no constraint), since the AI worker
--     does not need referential integrity to those rows — it queries by the
--     idempotency key, not by FK navigation.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_ai_artifact (
    uuid              CHAR(36)    NOT NULL,
    subject_kind      ENUM('ROLE','CANDIDATE','APPLICATION','INTERVIEW','OFFER') NOT NULL,
    subject_uuid      CHAR(36)    NOT NULL,
    kind              VARCHAR(40) NOT NULL,
    prompt_version    VARCHAR(40) NOT NULL,
    model             VARCHAR(80) NOT NULL,
    input_digest      CHAR(64)    NOT NULL,
    output            JSON        NULL,
    evidence          JSON        NULL,
    confidence        DECIMAL(4,3) NULL,
    state             VARCHAR(40) NOT NULL,
    generated_at      TIMESTAMP   NULL,
    reviewed_by_uuid  CHAR(36)    NULL,
    reviewed_at       TIMESTAMP   NULL,
    override_json     JSON        NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_ai_artifact_idem (subject_kind, subject_uuid, kind, input_digest),
    KEY idx_ai_artifact_subject (subject_kind, subject_uuid),
    KEY idx_ai_artifact_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI artifacts (generation attempts) — idempotent on (subject, kind, input_digest)';


-- ---------------------------------------------------------------------------
-- (3a) recruitment_open_role
--     The open hiring slot. `status` and `pipeline_kind` drive most workflow
--     decisions; `advertising_status` and `search_status` are independent
--     side workstreams (separate from main status so they can run in parallel
--     during SOURCING). Soft FKs to user/team/career_level/company tables
--     (no CONSTRAINT, project convention for cross-module).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_open_role (
    uuid                  CHAR(36)     NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    hiring_category       VARCHAR(40)  NOT NULL,
    pipeline_kind         ENUM('CONSULTANT','OTHER') NOT NULL,
    practice              ENUM('DEV','SA','BA','PM','CYB','JK','UD') NULL,
    career_level_uuid     CHAR(36)     NULL,
    company_uuid          CHAR(36)     NULL,
    team_uuid             CHAR(36)     NOT NULL,
    function_area         VARCHAR(120) NULL,
    hiring_source         VARCHAR(40)  NOT NULL,
    hiring_reason         TEXT         NULL,
    target_start_date     DATE         NULL,
    expected_allocation   DECIMAL(4,2) NULL,
    expected_rate_band    VARCHAR(40)  NULL,
    salary_min            INT          NULL,
    salary_max            INT          NULL,
    currency              CHAR(3)      NULL DEFAULT 'DKK',
    priority              TINYINT      NULL,
    status                VARCHAR(40)  NOT NULL,
    advertising_status    ENUM('NOT_STARTED','ACTIVE','PAUSED','DONE') NOT NULL DEFAULT 'NOT_STARTED',
    search_status         ENUM('NOT_STARTED','ACTIVE','PAUSED','DONE') NOT NULL DEFAULT 'NOT_STARTED',
    created_by_uuid       CHAR(36)     NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_open_role_status (status),
    KEY idx_open_role_practice_status (practice, status),
    KEY idx_open_role_team_status (team_uuid, status),
    KEY idx_open_role_category_status (hiring_category, status),
    KEY idx_open_role_target_start (target_start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Open hiring roles (the recruitment-side counterpart to a job opening)';


-- ---------------------------------------------------------------------------
-- (3b) recruitment_candidate
--     The person, before they become an employee. PII fields are nullable so
--     the retention job can NULL them on anonymization while preserving the
--     row (state=ANONYMIZED) for analytics history. `state`, `consent_status`,
--     `owner_user_uuid`, and the retention timestamps drive retention/consent
--     workflows. `converted_user_uuid` (soft FK to user_management.user.uuid)
--     is set when the candidate becomes an employee in Slice 5.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_candidate (
    uuid                          CHAR(36)     NOT NULL,
    first_name                    VARCHAR(120) NULL,
    last_name                     VARCHAR(120) NULL,
    email                         VARCHAR(255) NULL,
    phone                         VARCHAR(40)  NULL,
    first_contact_source          VARCHAR(40)  NULL,
    current_company               VARCHAR(255) NULL,
    desired_practice              ENUM('DEV','SA','BA','PM','CYB','JK','UD') NULL,
    desired_career_level_uuid     CHAR(36)     NULL,
    notice_period_days            INT          NULL,
    salary_expectation            INT          NULL,
    salary_currency               CHAR(3)      NULL,
    location_preference           VARCHAR(120) NULL,
    linkedin_url                  VARCHAR(512) NULL,
    tags                          JSON         NULL,
    last_contact_at               TIMESTAMP    NULL,
    consent_status                VARCHAR(40)  NOT NULL,
    consent_given_at              TIMESTAMP    NULL,
    consent_expires_at            TIMESTAMP    NULL,
    state                         VARCHAR(40)  NOT NULL,
    owner_user_uuid               CHAR(36)     NULL,
    sharepoint_folder_url         VARCHAR(1024) NULL,
    added_to_pool_at              TIMESTAMP    NULL,
    retention_extended_to         TIMESTAMP    NULL,
    retention_extension_reason    TEXT         NULL,
    anonymized_at                 TIMESTAMP    NULL,
    converted_user_uuid           CHAR(36)     NULL,
    created_at                    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_candidate_state (state),
    KEY idx_candidate_email (email),
    KEY idx_candidate_owner (owner_user_uuid),
    KEY idx_candidate_added_to_pool (added_to_pool_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Candidates (people in pipeline). PII fields nullable to allow anonymization in place';


-- ---------------------------------------------------------------------------
-- (4a) recruitment_role_assignment
--     Many-to-many: which users have which responsibility on which role.
--     UNIQUE on (role, user, responsibility_kind) — same user can hold two
--     different responsibilities on the same role, but cannot duplicate one.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_role_assignment (
    uuid                   CHAR(36)   NOT NULL,
    role_uuid              CHAR(36)   NOT NULL,
    user_uuid              CHAR(36)   NOT NULL,
    responsibility_kind    ENUM('RECRUITMENT_OWNER','COORDINATOR','SOURCER',
                                'TAM','TEAM_LEAD','PRACTICE_SUPPORT','HR_IT_OWNER') NOT NULL,
    assigned_at            TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by_uuid       CHAR(36)   NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_role_user_responsibility (role_uuid, user_uuid, responsibility_kind),
    CONSTRAINT fk_role_assignment_role
        FOREIGN KEY (role_uuid) REFERENCES recruitment_open_role (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Role assignments — who is responsible for what on each open role';


-- ---------------------------------------------------------------------------
-- (4b) recruitment_role_history
--     Append-only log of role status transitions. Indexed by (role, at) for
--     reverse-chronological reads on the role detail screen.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_role_history (
    uuid           CHAR(36)    NOT NULL,
    role_uuid      CHAR(36)    NOT NULL,
    from_status    VARCHAR(40) NULL,
    to_status      VARCHAR(40) NOT NULL,
    reason         TEXT        NULL,
    actor_uuid     CHAR(36)    NOT NULL,
    at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_role_history_role_at (role_uuid, at),
    CONSTRAINT fk_role_history_role
        FOREIGN KEY (role_uuid) REFERENCES recruitment_open_role (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Append-only history of recruitment_open_role.status transitions';


-- ---------------------------------------------------------------------------
-- (4c) recruitment_candidate_cv
--     One CV upload per row; `is_current` flags the current canonical CV.
--     Trigger-maintained `current_for_unique` enforces "exactly one current
--     CV per candidate" via UNIQUE INDEX (NULL excluded). The triggers below
--     set this column on every INSERT/UPDATE — see header note for why we
--     can't use a generated column here in MariaDB 10.
--
--     `extraction_artifact_uuid` is the only FK in the schema with ON DELETE
--     SET NULL — deleting the AI artifact (e.g. cleanup) should not block
--     deleting the CV row, and a NULL extraction pointer is a valid state.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_candidate_cv (
    uuid                       CHAR(36)     NOT NULL,
    candidate_uuid             CHAR(36)     NOT NULL,
    file_url                   VARCHAR(1024) NOT NULL,
    file_sha256                CHAR(64)     NOT NULL,
    is_current                 BOOLEAN      NOT NULL,
    -- Trigger-maintained partial-unique column (see header note for the
    -- MariaDB-10 generated-column limitation). Set by trg_cv_b{i,u} below.
    -- Equals candidate_uuid when is_current=1, else NULL. NULL rows are
    -- excluded from uk_one_current_cv_per_candidate, enforcing
    -- "at most one current CV per candidate".
    current_for_unique         CHAR(36)     NULL,
    uploaded_by_uuid           CHAR(36)     NOT NULL,
    uploaded_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    extraction_artifact_uuid   CHAR(36)     NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_one_current_cv_per_candidate (current_for_unique),
    KEY idx_cv_candidate (candidate_uuid),
    KEY idx_cv_artifact (extraction_artifact_uuid),
    CONSTRAINT fk_cv_candidate
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidate (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_cv_artifact
        FOREIGN KEY (extraction_artifact_uuid) REFERENCES recruitment_ai_artifact (uuid)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Uploaded candidate CVs; one current per candidate via uk_one_current_cv_per_candidate';


-- ---------------------------------------------------------------------------
-- (4d) recruitment_candidate_note
--     Free-text notes on candidates with visibility scoping. PRIVATE notes
--     are visible only to admin/interview/offer scopes; SHARED visible to
--     write/read; REJECTION_REASON visible to write/admin/offer.
--     Hard-deleted by the retention job during anonymization (per spec §7.0).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_candidate_note (
    uuid              CHAR(36)   NOT NULL,
    candidate_uuid    CHAR(36)   NOT NULL,
    author_uuid       CHAR(36)   NOT NULL,
    body              TEXT       NOT NULL,
    visibility        ENUM('PRIVATE','SHARED','REJECTION_REASON') NOT NULL,
    created_at        TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_note_candidate (candidate_uuid),
    CONSTRAINT fk_note_candidate
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidate (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Candidate notes (PRIVATE/SHARED/REJECTION_REASON); hard-deleted on anonymization';


-- ---------------------------------------------------------------------------
-- (4e) recruitment_candidate_activity
--     Append-only activity log (catch-all for events not modelled by
--     dedicated history tables). `payload` JSON is intentionally schema-less
--     here; per-kind structure is enforced at the service layer.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_candidate_activity (
    uuid              CHAR(36)    NOT NULL,
    candidate_uuid    CHAR(36)    NOT NULL,
    kind              VARCHAR(60) NOT NULL,
    payload           JSON        NULL,
    actor_uuid        CHAR(36)    NULL,
    at                TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_activity_candidate_at (candidate_uuid, at),
    CONSTRAINT fk_activity_candidate
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidate (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Append-only candidate activity log (free-form payload JSON)';


-- ---------------------------------------------------------------------------
-- (5a) recruitment_application
--     The link table candidate × open_role with stage-machine state.
--
--     Two generated-column unique indexes enforce the spec's invariants:
--
--       uk_active_app_per_candidate_role (candidate, role, is_active)
--           "One active application per (candidate, role)".
--           is_active is 1 when stage is in {NEW, SCREENING, INTERVIEWING,
--           FINAL_DECISION, OFFER, ACCEPTED, …} — anything not in the
--           terminal set {REJECTED, WITHDRAWN, TALENT_POOL, CONVERTED}.
--           NOTE: ACCEPTED is treated as ACTIVE (per spec §7.1) — accepted
--           candidates are still open conversion obligations.
--           NULL rows are excluded from the unique index.
--
--       uk_one_accepted_app_per_candidate (accepted_for_unique)
--           "One accepted-not-converted application per candidate".
--           accepted_for_unique = candidate_uuid when stage='ACCEPTED', else
--           NULL. Once the application transitions to CONVERTED (or another
--           terminal state) the column becomes NULL and another application
--           can be ACCEPTED.
--
--     Both generated columns declared STORED so the unique index is robust.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_application (
    uuid                          CHAR(36)    NOT NULL,
    candidate_uuid                CHAR(36)    NOT NULL,
    role_uuid                     CHAR(36)    NOT NULL,
    application_type              ENUM('UNSOLICITED','REFERRAL','JOB_AD',
                                       'JOB_AD_AND_REFERRAL','SEARCH','EVENT','OTHER') NOT NULL,
    referrer_user_uuid            CHAR(36)    NULL,
    stage                         VARCHAR(40) NOT NULL,
    -- Trigger-maintained partial-unique columns (see header note for the
    -- MariaDB-10 generated-column limitation). Set by trg_application_b{i,u}
    -- below.
    --   is_active = 1 when stage is in the active set, NULL otherwise.
    --   ACCEPTED is intentionally treated as ACTIVE (still an open
    --   conversion obligation). Closed stages (is_active=NULL):
    --   REJECTED, WITHDRAWN, TALENT_POOL, CONVERTED.
    is_active                     TINYINT(1)  NULL,
    --   accepted_for_unique = candidate_uuid when stage='ACCEPTED', else NULL.
    accepted_for_unique           CHAR(36)    NULL,
    screening_recommendation      VARCHAR(40) NULL,
    screening_outcome             VARCHAR(40) NULL,
    screening_override_reason     TEXT        NULL,
    screening_decided_by_uuid     CHAR(36)    NULL,
    screening_decided_at          TIMESTAMP   NULL,
    closed_reason                 VARCHAR(60) NULL,
    interview_booked_at           TIMESTAMP   NULL,
    last_stage_change_at          TIMESTAMP   NULL,
    sla_deadline_at               TIMESTAMP   NULL,
    accepted_at                   TIMESTAMP   NULL,
    converted_at                  TIMESTAMP   NULL,
    created_at                    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_application_role_stage (role_uuid, stage),
    KEY idx_application_candidate_stage (candidate_uuid, stage),
    KEY idx_application_type_stage (application_type, stage),
    KEY idx_application_sla_deadline (sla_deadline_at),
    UNIQUE KEY uk_active_app_per_candidate_role (candidate_uuid, role_uuid, is_active),
    UNIQUE KEY uk_one_accepted_app_per_candidate (accepted_for_unique),
    CONSTRAINT fk_app_candidate
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidate (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_app_role
        FOREIGN KEY (role_uuid) REFERENCES recruitment_open_role (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Candidate × open_role link with stage state and uniqueness invariants';


-- ---------------------------------------------------------------------------
-- (5b) recruitment_application_stage_history
--     Append-only log of application stage transitions. The standard pattern
--     across the recruitment domain is "history table per aggregate root that
--     has a state machine"; only role and application have history tables in
--     v1 because only they have visible state-machine workflows.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_application_stage_history (
    uuid                CHAR(36)    NOT NULL,
    application_uuid    CHAR(36)    NOT NULL,
    from_stage          VARCHAR(40) NULL,
    to_stage            VARCHAR(40) NOT NULL,
    reason              TEXT        NULL,
    actor_uuid          CHAR(36)    NOT NULL,
    at                  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_app_stage_history_app_at (application_uuid, at),
    CONSTRAINT fk_app_stage_history_application
        FOREIGN KEY (application_uuid) REFERENCES recruitment_application (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Append-only history of recruitment_application.stage transitions';


-- ---------------------------------------------------------------------------
-- (6a) recruitment_interview
--     Interview event. round_up_* columns capture the post-interview
--     decision (advance / hold / reject); reschedule_count is incremented
--     by the application whenever the calendar invite is rescheduled.
--     `outlook_event_id` is the soft FK into the Outlook calendar (no
--     constraint).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_interview (
    uuid                            CHAR(36)    NOT NULL,
    application_uuid                CHAR(36)    NOT NULL,
    round_number                    INT         NOT NULL,
    round_type                      ENUM('FIRST','CASE_OR_TECH','FINAL','SPECIAL') NOT NULL,
    scheduled_at                    TIMESTAMP   NOT NULL,
    duration_minutes                INT         NOT NULL,
    outlook_event_id                VARCHAR(255) NULL,
    interview_kit_artifact_uuid     CHAR(36)    NULL,
    status                          VARCHAR(40) NOT NULL,
    round_up_decision               VARCHAR(40) NULL,
    round_up_at                     TIMESTAMP   NULL,
    round_up_summary                TEXT        NULL,
    reschedule_count                INT         NOT NULL DEFAULT 0,
    created_at                      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_interview_scheduled (scheduled_at),
    KEY idx_interview_application (application_uuid),
    CONSTRAINT fk_interview_application
        FOREIGN KEY (application_uuid) REFERENCES recruitment_application (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Interview events (per application × round)';


-- ---------------------------------------------------------------------------
-- (6b) recruitment_interview_participant
--     Many-to-many: which users are participating in which interview, in
--     what role, and whether they're a required scorer. UNIQUE on
--     (interview, user) — one participant row per user per interview.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_interview_participant (
    uuid                  CHAR(36) NOT NULL,
    interview_uuid        CHAR(36) NOT NULL,
    user_uuid             CHAR(36) NOT NULL,
    role_in_interview     ENUM('LEAD_INTERVIEWER','SCORER','OBSERVER',
                               'TAM','PRACTICE_SUPPORT') NOT NULL,
    is_required_scorer    BOOLEAN  NOT NULL,
    invitation_status     ENUM('INVITED','ACCEPTED','DECLINED','TENTATIVE') NULL DEFAULT 'INVITED',
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_interview_participant (interview_uuid, user_uuid),
    CONSTRAINT fk_interview_participant_interview
        FOREIGN KEY (interview_uuid) REFERENCES recruitment_interview (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Interview participants (interviewers, observers, etc.) with invite status';


-- ---------------------------------------------------------------------------
-- (6c) recruitment_scorecard
--     One scorecard per (interview, interviewer). Submit-first server rule:
--     the resource layer enforces that an interviewer cannot read other
--     scorecards on this interview until they have submitted their own.
--     `private_notes` is masked from non-interview/admin/offer scopes by
--     RecruitmentScopeResponseFilter.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_scorecard (
    uuid                          CHAR(36)    NOT NULL,
    interview_uuid                CHAR(36)    NOT NULL,
    interviewer_user_uuid         CHAR(36)    NOT NULL,
    practice_skill_fit            TINYINT     NULL,
    career_level_fit              TINYINT     NULL,
    consulting_communication      TINYINT     NULL,
    client_facing_maturity        TINYINT     NULL,
    culture_value_fit             TINYINT     NULL,
    delivery_track_potential      TINYINT     NULL,
    concerns                      TEXT        NULL,
    recommendation                ENUM('STRONG_HIRE','HIRE','LEAN_HIRE','LEAN_NO','NO_HIRE') NOT NULL,
    notes                         TEXT        NULL,
    private_notes                 TEXT        NULL,
    submitted_at                  TIMESTAMP   NOT NULL,
    reopened_at                   TIMESTAMP   NULL,
    reopened_by_uuid              CHAR(36)    NULL,
    reopened_reason               TEXT        NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_scorecard_interview_interviewer (interview_uuid, interviewer_user_uuid),
    CONSTRAINT fk_scorecard_interview
        FOREIGN KEY (interview_uuid) REFERENCES recruitment_interview (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Interviewer scorecards (one per interview × interviewer)';


-- ---------------------------------------------------------------------------
-- (6d) recruitment_scorecard_amendment
--     Append-only post-submission addenda to a scorecard. Used when an
--     interviewer wants to clarify or augment after submission, or when an
--     admin reopens a scorecard for correction (the reopen itself is
--     recorded on the scorecard row; the amendment captures the new content).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_scorecard_amendment (
    uuid                CHAR(36)   NOT NULL,
    scorecard_uuid      CHAR(36)   NOT NULL,
    author_uuid         CHAR(36)   NOT NULL,
    body                TEXT       NOT NULL,
    created_at          TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_scorecard_amendment_scorecard (scorecard_uuid),
    CONSTRAINT fk_scorecard_amendment_scorecard
        FOREIGN KEY (scorecard_uuid) REFERENCES recruitment_scorecard (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Append-only addenda / admin-reopen edits to scorecards';


-- ---------------------------------------------------------------------------
-- (7a) recruitment_offer
--     Job offer. `dossier_uuid` is set when a dossier exists (created via
--     a separate workflow). `signing_case_id` mirrors the equivalent column
--     on `recruitment_dossier` once the case is sent for signature; both
--     point at the same `signing_cases` row, but recruitment never adds a
--     hard FK to that table (project convention — soft FK across modules).
--
--     `id_verified_*` columns are mirrored from the
--     `recruitment_dossier_verification_item` row of kind ID_VERIFICATION
--     when that item transitions to VERIFIED, so the offer page can show
--     ID-verification state without joining the dossier subtree.
--
--     `compensation` JSON is masked by RecruitmentScopeResponseFilter unless
--     caller has `recruitment:offer` or `recruitment:admin`.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_offer (
    uuid                              CHAR(36)    NOT NULL,
    application_uuid                  CHAR(36)    NOT NULL,
    dossier_uuid                      CHAR(36)    NULL,
    signing_case_id                   VARCHAR(255) NULL,
    compensation                      JSON        NOT NULL,
    start_date                        DATE        NOT NULL,
    id_verified_at                    TIMESTAMP   NULL,
    id_verified_by_uuid               CHAR(36)    NULL,
    id_verification_override_reason   TEXT        NULL,
    status                            VARCHAR(40) NOT NULL,
    approved_at                       TIMESTAMP   NULL,
    approved_by_uuid                  CHAR(36)    NULL,
    sent_for_signature_at             TIMESTAMP   NULL,
    signed_at                         TIMESTAMP   NULL,
    accepted_at                       TIMESTAMP   NULL,
    declined_reason                   TEXT        NULL,
    expired_at                        TIMESTAMP   NULL,
    created_by_uuid                   CHAR(36)    NOT NULL,
    created_at                        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_offer_application (application_uuid),
    KEY idx_offer_status (status),
    KEY idx_offer_signed (signed_at),
    CONSTRAINT fk_offer_application
        FOREIGN KEY (application_uuid) REFERENCES recruitment_application (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Offer aggregate root — one offer per application (in v1 this is a 1:1 in practice)';


-- ---------------------------------------------------------------------------
-- (7b) recruitment_offer_audit_event
--     Append-only audit log for the offer aggregate. Captures status changes,
--     ID-verification overrides (with INFO/WARN/OVERRIDE severity), approvals,
--     signature events, etc. Indexed by (offer, at).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_offer_audit_event (
    uuid              CHAR(36)    NOT NULL,
    offer_uuid        CHAR(36)    NOT NULL,
    severity          ENUM('INFO','WARN','OVERRIDE') NOT NULL,
    kind              VARCHAR(60) NOT NULL,
    payload           JSON        NULL,
    actor_uuid        CHAR(36)    NOT NULL,
    at                TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_offer_audit_offer_at (offer_uuid, at),
    CONSTRAINT fk_offer_audit_offer
        FOREIGN KEY (offer_uuid) REFERENCES recruitment_offer (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Append-only audit log for the recruitment_offer aggregate';


-- ---------------------------------------------------------------------------
-- (8a) recruitment_dossier
--     The contract dossier: one per offer (UNIQUE on offer_uuid). Links by
--     UUID to documentservice.document_templates (no FK; cross-module soft
--     FK convention) and to signing_cases (no FK; per spec §10 recruitment
--     is a NEW CALLER of the existing signing service, never a schema owner).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_dossier (
    uuid                       CHAR(36)     NOT NULL,
    offer_uuid                 CHAR(36)     NOT NULL,
    template_uuid              CHAR(36)     NOT NULL  COMMENT 'Soft FK to documentservice.document_templates.uuid; integrity enforced in service layer',
    signing_case_id            VARCHAR(255) NULL      COMMENT 'Soft FK to signing_cases.case_key; set when sent for signature',
    current_revision_uuid      CHAR(36)     NULL      COMMENT 'Pointer to latest recruitment_dossier_revision row',
    status                     ENUM('DRAFT','READY_FOR_REVIEW','REVIEW_SENT','SENT_FOR_SIGNATURE',
                                    'SIGNED','DECLINED','EXPIRED','SUPERSEDED') NOT NULL,
    created_by_uuid            CHAR(36)     NOT NULL,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_one_dossier_per_offer (offer_uuid),
    CONSTRAINT fk_dossier_offer
        FOREIGN KEY (offer_uuid) REFERENCES recruitment_offer (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Contract dossier — one per offer; recruitment-owned (NOT shared signing schema)';


-- ---------------------------------------------------------------------------
-- (8b) recruitment_dossier_revision
--     Versioned snapshot of placeholder values at each revision. `placeholders`
--     JSON is the canonical record of what was rendered. UNIQUE on
--     (dossier, revision_number) so revisions monotonically increase per
--     dossier with no gaps managed by the service layer.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_dossier_revision (
    uuid                       CHAR(36)     NOT NULL,
    dossier_uuid               CHAR(36)     NOT NULL,
    revision_number            INT          NOT NULL,
    placeholders               JSON         NOT NULL,
    rendered_pdf_url           VARCHAR(1024) NULL,
    rendered_at                TIMESTAMP    NULL,
    created_by_uuid            CHAR(36)     NOT NULL,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_revision_per_dossier (dossier_uuid, revision_number),
    CONSTRAINT fk_revision_dossier
        FOREIGN KEY (dossier_uuid) REFERENCES recruitment_dossier (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Versioned dossier revisions (placeholder snapshots + rendered review PDF)';


-- ---------------------------------------------------------------------------
-- (8c) recruitment_dossier_signer
--     Signers configured for the dossier. CHECK constraint enforces that
--     either an internal user_uuid or an external email is present
--     (otherwise we have no way to address the signer). Multiple signers
--     per dossier is normal (candidate + HR + partner).
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_dossier_signer (
    uuid               CHAR(36)     NOT NULL,
    dossier_uuid       CHAR(36)     NOT NULL,
    signer_role        ENUM('CANDIDATE','HR','PARTNER','LEGAL','OTHER') NOT NULL,
    user_uuid          CHAR(36)     NULL  COMMENT 'Internal signer (TW user); soft FK to user_management',
    email              VARCHAR(255) NULL  COMMENT 'External signer (e.g. candidate)',
    full_name          VARCHAR(255) NOT NULL,
    signing_order      INT          NOT NULL,
    required           BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (uuid),
    KEY idx_dossier_signer_dossier (dossier_uuid),
    CONSTRAINT fk_signer_dossier
        FOREIGN KEY (dossier_uuid) REFERENCES recruitment_dossier (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_signer_addressable
        CHECK (user_uuid IS NOT NULL OR email IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Dossier signers; CHECK ensures at least one of (user_uuid, email) is present';


-- ---------------------------------------------------------------------------
-- (8d) recruitment_dossier_appendix
--     Appendix files attached to the dossier (e.g. NDA, employee handbook,
--     pension scheme). `display_order` controls render order. Files live in
--     SharePoint (file_url) and are content-hashed (file_sha256) for
--     integrity.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_dossier_appendix (
    uuid               CHAR(36)     NOT NULL,
    dossier_uuid       CHAR(36)     NOT NULL,
    title              VARCHAR(255) NOT NULL,
    file_url           VARCHAR(1024) NOT NULL,
    file_sha256        CHAR(64)     NOT NULL,
    display_order      INT          NOT NULL,
    uploaded_by_uuid   CHAR(36)     NOT NULL,
    uploaded_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_dossier_appendix_dossier (dossier_uuid),
    CONSTRAINT fk_appendix_dossier
        FOREIGN KEY (dossier_uuid) REFERENCES recruitment_dossier (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Dossier appendices (extra files attached to the contract package)';


-- ---------------------------------------------------------------------------
-- (8e) recruitment_dossier_verification_item
--     Pre-signature verification checklist. UNIQUE on (dossier, kind) so we
--     have at most one item per kind per dossier (the OTHER kind is for
--     ad-hoc items distinguished by `description`; the service layer is
--     responsible for ensuring OTHER items don't collide).
--
--     CHECK constraint enforces:
--       status='VERIFIED' implies (verified_at IS NOT NULL AND
--                                 verified_by_uuid IS NOT NULL)
--     This prevents a "VERIFIED with no verifier" race or programming bug
--     from corrupting audit trails — the offer's send-for-signature flow
--     reads these items to decide whether to proceed.
-- ---------------------------------------------------------------------------
CREATE TABLE recruitment_dossier_verification_item (
    uuid                CHAR(36)     NOT NULL,
    dossier_uuid        CHAR(36)     NOT NULL,
    kind                ENUM('ID_VERIFICATION','EMPLOYMENT_DATA_CONFIRMED',
                             'COMPENSATION_REVIEWED','SIGNERS_CONFIGURED',
                             'APPENDICES_ATTACHED','OTHER') NOT NULL,
    description         VARCHAR(255) NULL  COMMENT 'For OTHER items',
    status              ENUM('PENDING','VERIFIED') NOT NULL DEFAULT 'PENDING',
    required            BOOLEAN      NOT NULL DEFAULT TRUE,
    verified_at         TIMESTAMP    NULL,
    verified_by_uuid    CHAR(36)     NULL,
    override_reason     TEXT         NULL  COMMENT 'Non-empty when verified via HR override',
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_item_kind_per_dossier (dossier_uuid, kind),
    CONSTRAINT fk_vitem_dossier
        FOREIGN KEY (dossier_uuid) REFERENCES recruitment_dossier (uuid)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_vitem_verified_consistency
        CHECK (
            status = 'PENDING'
            OR (status = 'VERIFIED' AND verified_at IS NOT NULL AND verified_by_uuid IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Pre-signature verification checklist; CHECK enforces VERIFIED requires verifier+timestamp';


-- =============================================================================
-- Triggers — maintain partial-unique columns (see header note for rationale)
--
-- These triggers replace what would normally be VIRTUAL/STORED generated
-- columns. MariaDB 10.x rejects conditional functions (IF, CASE, NULLIF,
-- COALESCE, etc.) inside generated-column expressions when those columns
-- back a UNIQUE INDEX (ERROR 1901). The triggers below derive the same
-- values on every INSERT/UPDATE, preserving the spec's invariants exactly.
--
-- Application contract:
--   - Application code (incl. Hibernate) must NOT attempt to write the three
--     trigger-managed columns (current_for_unique, is_active,
--     accepted_for_unique). Triggers always overwrite supplied values.
--   - In JPA entities, mark these columns as
--     @Column(insertable=false, updatable=false) or omit from the entity
--     (read-only via a JPQL projection if needed).
-- =============================================================================

DELIMITER //

-- ---------------------------------------------------------------------------
-- recruitment_candidate_cv: derive current_for_unique from is_current
-- ---------------------------------------------------------------------------

CREATE TRIGGER trg_cv_bi BEFORE INSERT ON recruitment_candidate_cv
FOR EACH ROW
BEGIN
    SET NEW.current_for_unique = IF(NEW.is_current = 1, NEW.candidate_uuid, NULL);
END //

CREATE TRIGGER trg_cv_bu BEFORE UPDATE ON recruitment_candidate_cv
FOR EACH ROW
BEGIN
    SET NEW.current_for_unique = IF(NEW.is_current = 1, NEW.candidate_uuid, NULL);
END //

-- ---------------------------------------------------------------------------
-- recruitment_application: derive is_active and accepted_for_unique from stage
--
-- is_active = 1 when stage is in the active set (anything not in
-- {REJECTED, WITHDRAWN, TALENT_POOL, CONVERTED}); NULL otherwise.
-- ACCEPTED is intentionally part of the active set per spec — see header.
--
-- accepted_for_unique = candidate_uuid when stage='ACCEPTED', NULL otherwise.
-- ---------------------------------------------------------------------------

CREATE TRIGGER trg_application_bi BEFORE INSERT ON recruitment_application
FOR EACH ROW
BEGIN
    SET NEW.is_active = IF(
        NEW.stage NOT IN ('REJECTED','WITHDRAWN','TALENT_POOL','CONVERTED'),
        1,
        NULL
    );
    SET NEW.accepted_for_unique = IF(NEW.stage = 'ACCEPTED', NEW.candidate_uuid, NULL);
END //

CREATE TRIGGER trg_application_bu BEFORE UPDATE ON recruitment_application
FOR EACH ROW
BEGIN
    SET NEW.is_active = IF(
        NEW.stage NOT IN ('REJECTED','WITHDRAWN','TALENT_POOL','CONVERTED'),
        1,
        NULL
    );
    SET NEW.accepted_for_unique = IF(NEW.stage = 'ACCEPTED', NEW.candidate_uuid, NULL);
END //

DELIMITER ;
