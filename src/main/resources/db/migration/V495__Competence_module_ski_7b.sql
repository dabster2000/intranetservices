-- =============================================================================
-- V495: Competence module (SKI 7.b — secure development)
--
-- Spec: docs/specs/competence-module-ski-7b-spec.md
--
-- Trustworks must be able to show a customer's auditor that the people who
-- develop the customer's critical systems are trained in a documented secure
-- development method. Each requirement (krav) carries a versioned microcourse
-- and a versioned test; a pass becomes evidence only once a named leader has
-- attested it. This migration creates the system of record for that claim.
--
-- Six tables, three of them append-only and enforced as such by triggers
-- rather than by service convention (spec §10.6): the value of the evidence
-- is that it cannot be edited after the fact, and "we don't edit those" is
-- not a control an auditor can test.
--
-- NOT included here: the four requirements and their 2026-08 content, which
-- land in V496 so a content reseed never has to replay DDL.
-- =============================================================================

-- -------------------------------------------------------------------
-- 1. competence_requirement — the unit of compliance and the targeting
--    carrier.
--
--    The three target_* columns are byte-compatible with
--    questionnaire.target_practice_uuids / target_teams (JSON array of
--    uuids, NULL/blank = absent) so the storage concept stays singular.
--    The COMBINATION RULE DIFFERS and that difference is load-bearing:
--    the questionnaire reader chains its filters, so practice AND team
--    must both match. Here it is a UNION. See spec §5.2 — under AND
--    semantics a requirement targeted at the TECH practice would exclude
--    every technology team lead, because all nine team leads hold their
--    only MEMBER role on the practice-less "Teamleads" org team and so
--    carry practice_uuid = NULL. Those are precisely the people who
--    approve the pull requests this module is about.
-- -------------------------------------------------------------------
CREATE TABLE competence_requirement (
    uuid                   CHAR(36)      NOT NULL,
    comp_id                VARCHAR(64)   NOT NULL,
    kref                   VARCHAR(32)   NOT NULL,
    name                   VARCHAR(255)  NOT NULL,
    description            VARCHAR(1000) NULL,
    target_practice_uuids  TEXT          NULL,
    target_teams           TEXT          NULL,
    target_useruuids       TEXT          NULL,
    cadence_days_override  INT           NULL,
    sort_order             INT           NOT NULL DEFAULT 0,
    active                 TINYINT(1)    NOT NULL DEFAULT 1,
    created_at             DATETIME(6)   NULL,
    created_by             VARCHAR(64)   NULL,
    updated_at             DATETIME(6)   NULL,
    modified_by            VARCHAR(64)   NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_competence_requirement_comp_id (comp_id),
    KEY idx_competence_requirement_active (active, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_danish_ci;

-- -------------------------------------------------------------------
-- 2. competence_content_version — one table for both artefact kinds.
--
--    A requirement has TWO independently versioned artefacts with
--    separate version numbers, publish decisions and force-retake flags
--    (spec §2.2): a typo fix in the reading material must not invalidate
--    everyone's passed test, and a sharpened question must not force
--    everyone to re-read 13 screens.
--
--    payload_json is LONGTEXT, deliberately NOT a JSON-typed column.
--    This app registers a global JavaTimeObjectMapperCustomizer, and
--    Hibernate refuses to reuse a customised ObjectMapper for JSON
--    columns: the SessionFactory fails to build and the container
--    crashes AT BOOT. That failure is runtime-only — mvn package and
--    the fast test tier both pass — and ECS Express then rolls back
--    silently on a green-looking deploy. The entity maps this column as
--    a plain String and the service (de)serialises it with a
--    module-local ObjectMapper in CompetencePayloadCodec, which never
--    touches the CDI-managed one — the same sidestep
--    ConferenceParticipantFieldsConverter makes.
--
--    active_key / draft_key give "at most one ACTIVE and at most one
--    DRAFT per (requirement, kind)" as a DATABASE guarantee. MariaDB
--    unique indexes ignore NULLs, so the key is NULL for every other
--    status and those rows escape the constraint.
--
--    They are PLAIN columns written by a trigger, NOT generated
--    columns. The spec drafted them as GENERATED ... PERSISTENT, which
--    MariaDB 10.11 refuses: any generated column whose expression
--    CONCATenates strings is rejected with error 1901 ("cannot be used
--    in the GENERATED ALWAYS AS clause"), whether PERSISTENT or
--    VIRTUAL, indexed or not. Verified 2026-08-14 against the local
--    10.11 server, which matches the deployed version. Persisted string
--    results could go stale if a collation changed, so MariaDB declines
--    to store them; the six generated columns already live in
--    production are all numeric, which is why V242's
--    invoices.invoicenumber_unique idiom works and this one cannot.
--
--    The spec's stated fallback was a trigger that counts existing rows
--    and SIGNALs. This is deliberately different and stronger: a
--    counting trigger cannot see other transactions' uncommitted rows,
--    so it has a race window and is not a real constraint. Here the
--    trigger only COMPUTES the key and the UNIQUE INDEX does the
--    enforcing, which is atomic. Recorded as a deviation in the spec.
--
--    Consequence for the publish transaction: the outgoing ACTIVE must
--    be archived BEFORE the DRAFT is activated. The reverse order
--    collides on uk_competence_content_active, by design.
--
--    Because these are plain columns they carry through the nightly
--    staging sync verbatim (V305/V490 exclude only columns with a
--    non-empty GENERATION_EXPRESSION), so no ERROR 1906 risk either.
-- -------------------------------------------------------------------
CREATE TABLE competence_content_version (
    uuid                CHAR(36)                            NOT NULL,
    requirement_uuid    CHAR(36)                            NOT NULL,
    content_kind        ENUM ('COURSE','TEST')              NOT NULL,
    version_label       VARCHAR(32)                         NOT NULL,
    status              ENUM ('DRAFT','ACTIVE','ARCHIVED')  NOT NULL,
    payload_json        LONGTEXT                            NOT NULL,
    forced_retake       TINYINT(1)                          NOT NULL DEFAULT 1,
    published_by        CHAR(36)                            NULL,
    published_at        DATETIME(6)                         NULL,
    superseded_by_uuid  CHAR(36)                            NULL,
    created_at          DATETIME(6)                         NULL,
    created_by          VARCHAR(64)                         NULL,
    updated_at          DATETIME(6)                         NULL,
    modified_by         VARCHAR(64)                         NULL,
    active_key          VARCHAR(80)                         NULL,
    draft_key           VARCHAR(80)                         NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_competence_content_active (active_key),
    UNIQUE KEY uk_competence_content_draft (draft_key),
    KEY idx_competence_content_req (requirement_uuid, content_kind, status),
    CONSTRAINT fk_competence_content_requirement
        FOREIGN KEY (requirement_uuid) REFERENCES competence_requirement (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_danish_ci;

-- -------------------------------------------------------------------
-- 3. competence_course_completion — append-only. The newest row for a
--    (user, requirement) pair drives status.
--
--    useruuid is a soft reference with no FK, matching the convention
--    V489 set: a user row can be merged or removed without taking the
--    compliance history with it.
--
--    version_label is denormalised so an export still reads correctly
--    after a content version is purged.
--
--    Force-retake-off carry-forward (spec §5.3) appends a NEW row per
--    affected completion carrying the new version — it never updates
--    the old one, which is why UPDATE can stay blocked outright.
-- -------------------------------------------------------------------
CREATE TABLE competence_course_completion (
    uuid                  CHAR(36)    NOT NULL,
    useruuid              CHAR(36)    NOT NULL,
    requirement_uuid      CHAR(36)    NOT NULL,
    content_version_uuid  CHAR(36)    NOT NULL,
    version_label         VARCHAR(32) NOT NULL,
    completed_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_ccc_user_req (useruuid, requirement_uuid, completed_at DESC),
    CONSTRAINT fk_ccc_requirement
        FOREIGN KEY (requirement_uuid) REFERENCES competence_requirement (uuid),
    CONSTRAINT fk_ccc_content_version
        FOREIGN KEY (content_version_uuid) REFERENCES competence_content_version (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_danish_ci;

-- -------------------------------------------------------------------
-- 4. competence_attempt — the evidence row. Created when a test starts,
--    scored once, then frozen.
--
--    threshold_snapshot and content_version_uuid are frozen at START,
--    not read at scoring time: raising the pass threshold must never
--    retroactively fail somebody who already passed, and publishing a
--    new test version mid-attempt must not corrupt the attempt in
--    flight (spec §5.7, §5.8).
--
--    ANSWERS ARE NEVER STORED — only the aggregate. That is a
--    data-minimisation decision the concept makes explicitly ("kun
--    hændelsen, ikke dine svar") and it is what lets the module keep
--    records for years without holding an assessment record on each
--    employee. option_order_json holds the per-attempt shuffle so
--    navigating back and forth does not move the answers; it is never
--    returned after submit.
-- -------------------------------------------------------------------
CREATE TABLE competence_attempt (
    uuid                  CHAR(36)      NOT NULL,
    useruuid              CHAR(36)      NOT NULL,
    requirement_uuid      CHAR(36)      NOT NULL,
    content_version_uuid  CHAR(36)      NOT NULL,
    version_label         VARCHAR(32)   NOT NULL,
    kref                  VARCHAR(32)   NOT NULL,
    threshold_snapshot    DECIMAL(4, 3) NOT NULL,
    option_order_json     TEXT          NOT NULL,
    started_at            DATETIME(6)   NOT NULL,
    submitted_at          DATETIME(6)   NULL,
    correct_count         SMALLINT      NULL,
    question_count        SMALLINT      NOT NULL,
    score                 DECIMAL(5, 4) NULL,
    passed                TINYINT(1)    NULL,
    abandoned             TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid),
    KEY idx_ca_user_req (useruuid, requirement_uuid, submitted_at DESC),
    KEY idx_ca_open (useruuid, submitted_at),
    CONSTRAINT fk_ca_requirement
        FOREIGN KEY (requirement_uuid) REFERENCES competence_requirement (uuid),
    CONSTRAINT fk_ca_content_version
        FOREIGN KEY (content_version_uuid) REFERENCES competence_content_version (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_danish_ci;

-- -------------------------------------------------------------------
-- 5. competence_attempt_decision — append-only approval ledger.
--
--    The attempt row carries no approval state, so it can stay frozen.
--    Effective state = the latest decision, or PENDING when there is
--    none.
--
--    REVOKED exists because an immutable row cannot be corrected any
--    other way: without it, a wrongly approved result could only be
--    fixed by editing evidence, i.e. not at all.
-- -------------------------------------------------------------------
CREATE TABLE competence_attempt_decision (
    uuid          CHAR(36)                     NOT NULL,
    attempt_uuid  CHAR(36)                     NOT NULL,
    decision      ENUM ('APPROVED','REVOKED')  NOT NULL,
    actor_uuid    CHAR(36)                     NOT NULL,
    decided_at    DATETIME(6)                  NOT NULL,
    note          VARCHAR(1000)                NULL,
    PRIMARY KEY (uuid),
    KEY idx_cad_attempt (attempt_uuid, decided_at DESC),
    CONSTRAINT fk_cad_attempt
        FOREIGN KEY (attempt_uuid) REFERENCES competence_attempt (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_danish_ci;

-- -------------------------------------------------------------------
-- 6. competence_settings_audit — the visible, exportable change log.
--
--    app_settings holds only the current value, and the concept
--    requires showing what the threshold and cadence USED to be, since
--    both change what a green cell means. Append-only.
-- -------------------------------------------------------------------
CREATE TABLE competence_settings_audit (
    uuid         CHAR(36)     NOT NULL,
    setting_key  VARCHAR(200) NOT NULL,
    old_value    TEXT         NULL,
    new_value    TEXT         NOT NULL,
    changed_by   VARCHAR(64)  NOT NULL,
    changed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_csa_key (setting_key, changed_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_danish_ci;

-- =============================================================================
-- IMMUTABILITY TRIGGERS (spec §4.4, §4.5, §4.6, §10.6)
--
-- These turn "we don't edit those" into a control an auditor can test by
-- issuing an UPDATE. The default application DB connection is read-only, so
-- a manual fix already needs a deliberate escalation; these make the
-- escalation insufficient on the evidence tables specifically.
--
-- NOTE FOR STAGING: sp_sync_prod_to_staging Phase 1 does DROP TABLE +
-- CREATE TABLE ... LIKE, and CREATE TABLE ... LIKE does NOT copy triggers.
-- After a nightly refresh these triggers are absent in staging until the
-- migration is replayed. That is a staging-only gap — production applies
-- V495 once and keeps them — but do not read "the UPDATE succeeded in
-- staging" as evidence the control is broken in production.
-- =============================================================================

DELIMITER $$

-- ---- competence_content_version: compute the conditional unique keys ----
--
-- The trigger unconditionally overwrites whatever the application supplied,
-- so active_key/draft_key cannot be set wrong from Java — the entity does
-- not map them at all. The UNIQUE INDEXes do the actual enforcing.
CREATE TRIGGER trg_competence_content_version_keys_ins
    BEFORE INSERT
    ON competence_content_version
    FOR EACH ROW
BEGIN
    SET NEW.active_key = IF(NEW.status = 'ACTIVE', CONCAT(NEW.requirement_uuid, ':', NEW.content_kind), NULL);
    SET NEW.draft_key = IF(NEW.status = 'DRAFT', CONCAT(NEW.requirement_uuid, ':', NEW.content_kind), NULL);
END$$

CREATE TRIGGER trg_competence_content_version_keys_upd
    BEFORE UPDATE
    ON competence_content_version
    FOR EACH ROW
BEGIN
    SET NEW.active_key = IF(NEW.status = 'ACTIVE', CONCAT(NEW.requirement_uuid, ':', NEW.content_kind), NULL);
    SET NEW.draft_key = IF(NEW.status = 'DRAFT', CONCAT(NEW.requirement_uuid, ':', NEW.content_kind), NULL);
END$$

-- ---- competence_course_completion: append-only ----
CREATE TRIGGER trg_competence_course_completion_no_update
    BEFORE UPDATE
    ON competence_course_completion
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'competence_course_completion is append-only: UPDATE is not permitted';
END$$

CREATE TRIGGER trg_competence_course_completion_no_delete
    BEFORE DELETE
    ON competence_course_completion
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'competence_course_completion is append-only: DELETE is not permitted';
END$$

-- ---- competence_attempt: exactly three permitted transitions ----
--
-- (a) the scoring write  — submitted_at NULL -> NOT NULL, with every
--     identity and snapshot column unchanged and only the scoring
--     outputs written;
-- (b) the reaper         — abandoned 0 -> 1 on a row still in progress,
--     with nothing else changed;
-- (c) GDPR erasure       — useruuid rewritten to an 'erased:' pseudonym,
--     with nothing else changed.
--
-- (c) exists because spec §10.9 requires erasure to pseudonymise rather
-- than delete, so that aggregate compliance history survives while the
-- row stops identifying a person. Without this branch the retention
-- policy would be unimplementable against its own immutability rule.
-- The 'erased:' prefix is what makes the rewrite auditable — an
-- arbitrary useruuid swap stays refused.
CREATE TRIGGER trg_competence_attempt_before_update
    BEFORE UPDATE
    ON competence_attempt
    FOR EACH ROW
BEGIN
    DECLARE v_identity_unchanged TINYINT(1);

    SET v_identity_unchanged = (
        NEW.uuid <=> OLD.uuid
            AND NEW.useruuid <=> OLD.useruuid
            AND NEW.requirement_uuid <=> OLD.requirement_uuid
            AND NEW.content_version_uuid <=> OLD.content_version_uuid
            AND NEW.version_label <=> OLD.version_label
            AND NEW.kref <=> OLD.kref
            AND NEW.threshold_snapshot <=> OLD.threshold_snapshot
            AND NEW.option_order_json <=> OLD.option_order_json
            AND NEW.started_at <=> OLD.started_at
            AND NEW.question_count <=> OLD.question_count
        );

    IF OLD.submitted_at IS NULL
        AND NEW.submitted_at IS NOT NULL
        AND v_identity_unchanged
        AND NEW.abandoned <=> OLD.abandoned THEN
        -- (a) the scoring write: permitted, outputs may change.
        SET @competence_attempt_transition = 'SCORE';

    ELSEIF OLD.submitted_at IS NULL
        AND NEW.submitted_at IS NULL
        AND OLD.abandoned = 0
        AND NEW.abandoned = 1
        AND v_identity_unchanged
        AND NEW.correct_count <=> OLD.correct_count
        AND NEW.score <=> OLD.score
        AND NEW.passed <=> OLD.passed THEN
        -- (b) the reaper: permitted.
        SET @competence_attempt_transition = 'ABANDON';

    ELSEIF NEW.useruuid <> OLD.useruuid
        AND NEW.useruuid LIKE 'erased:%'
        AND NEW.uuid <=> OLD.uuid
        AND NEW.requirement_uuid <=> OLD.requirement_uuid
        AND NEW.content_version_uuid <=> OLD.content_version_uuid
        AND NEW.version_label <=> OLD.version_label
        AND NEW.kref <=> OLD.kref
        AND NEW.threshold_snapshot <=> OLD.threshold_snapshot
        AND NEW.option_order_json <=> OLD.option_order_json
        AND NEW.started_at <=> OLD.started_at
        AND NEW.question_count <=> OLD.question_count
        AND NEW.submitted_at <=> OLD.submitted_at
        AND NEW.correct_count <=> OLD.correct_count
        AND NEW.score <=> OLD.score
        AND NEW.passed <=> OLD.passed
        AND NEW.abandoned <=> OLD.abandoned THEN
        -- (c) GDPR pseudonymisation: permitted.
        SET @competence_attempt_transition = 'ERASE';

    ELSE
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'competence_attempt is immutable: only the scoring write, the abandon flag, or an erased: pseudonymisation are permitted';
    END IF;
END$$

CREATE TRIGGER trg_competence_attempt_before_delete
    BEFORE DELETE
    ON competence_attempt
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'competence_attempt is immutable: DELETE is not permitted (GDPR erasure pseudonymises, see spec §10.9)';
END$$

-- ---- competence_attempt_decision: append-only ----
CREATE TRIGGER trg_competence_attempt_decision_no_update
    BEFORE UPDATE
    ON competence_attempt_decision
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'competence_attempt_decision is append-only: correct a decision by appending a REVOKED row, not by editing';
END$$

CREATE TRIGGER trg_competence_attempt_decision_no_delete
    BEFORE DELETE
    ON competence_attempt_decision
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'competence_attempt_decision is append-only: DELETE is not permitted';
END$$

-- ---- competence_settings_audit: append-only ----
CREATE TRIGGER trg_competence_settings_audit_no_update
    BEFORE UPDATE
    ON competence_settings_audit
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'competence_settings_audit is append-only: UPDATE is not permitted';
END$$

CREATE TRIGGER trg_competence_settings_audit_no_delete
    BEFORE DELETE
    ON competence_settings_audit
    FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'competence_settings_audit is append-only: DELETE is not permitted';
END$$

DELIMITER ;

-- =============================================================================
-- PERMISSIONS
--
-- V464 (the generated catalogue seed) has been regenerated to include these
-- three keys so the byte-gated seed-drift test passes, but V464 has already
-- run in every environment — repair-at-start realigns its checksum WITHOUT
-- re-running it. The rows therefore have to be inserted here, and before the
-- grants below: role_permission.permission_key is an FK onto permission.
-- This is the same reasoning V486 recorded for recruitment:manage.
-- =============================================================================

INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('competence:read', 'Competence — read', NULL, 'Competence', 'CODE', 'ACTIVE'),
       ('competence:write', 'Competence — write', NULL, 'Competence', 'CODE', 'ACTIVE'),
       ('competence:approve', 'Competence — approve', NULL, 'Competence', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

-- -------------------------------------------------------------------
-- Role bindings (spec §4.8).
--
-- Employees need no binding at all — self-access is intrinsic
-- (architecture.md § row-level data scope), which is why the learner
-- endpoints derive the subject from X-Requested-By and never take a
-- useruuid parameter.
--
-- TEAMLEAD gets TEAM scope, not ALL: the matrix is colleagues'
-- training records, i.e. employee performance data. ADMIN reaches
-- everything through admin:* expansion and needs no row here.
--
-- role_definition rows for these four already exist in production, but
-- are seeded defensively because role_permission.role is an FK onto
-- role_definition.name and a fresh local DB or a post-refresh staging
-- would otherwise fail the FK and take the deploy gate down with it.
-- -------------------------------------------------------------------
INSERT INTO role_definition (name, display_label, is_system, created_at, updated_at)
VALUES ('TECHPARTNER', 'Tech Partner', 0, NOW(), NOW()),
       ('TEAMLEAD', 'Team Lead', 0, NOW(), NOW()),
       ('HR', 'HR', 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE display_label = VALUES(display_label);

INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES ('TECHPARTNER', 'competence:read', 'ALL', NOW(), 'V495'),
       ('TECHPARTNER', 'competence:write', 'ALL', NOW(), 'V495'),
       ('TECHPARTNER', 'competence:approve', 'ALL', NOW(), 'V495'),
       ('TEAMLEAD', 'competence:read', 'TEAM', NOW(), 'V495'),
       ('TEAMLEAD', 'competence:approve', 'TEAM', NOW(), 'V495'),
       ('HR', 'competence:read', 'ALL', NOW(), 'V495')
ON DUPLICATE KEY UPDATE
    revoked_at  = NULL,
    updated_at  = NOW(),
    modified_by = 'V495';

-- =============================================================================
-- SETTINGS
--
-- Threshold and cadence live in app_settings, category 'competence'. The
-- change log lives in competence_settings_audit above, because app_settings
-- keeps only the current value.
--
-- NOTE FOR STAGING: app_settings seeds revert on the nightly refresh. A
-- reverted value here is the refresh doing its job, not a bug.
-- =============================================================================

INSERT INTO app_settings (setting_key, setting_value, category, updated_by)
VALUES ('competence.pass-threshold', '0.8', 'competence', 'V495'),
       ('competence.cadence-days', '365', 'competence', 'V495'),
       ('competence.attempt-timeout-minutes', '120', 'competence', 'V495'),
       ('competence.retention-years', '5', 'competence', 'V495')
ON DUPLICATE KEY UPDATE setting_value = setting_value;

-- =============================================================================
-- PAGE REGISTRY
--
-- display_order 140/150 sits the two pages directly after Projects (130) and
-- before the SETTINGS section (160). NOT the 33/34 the spec drafted: V318
-- renumbered KNOWLEDGE to 510/520/530 and a later change moved it again, so
-- the deployed values are 110/120/130 — verified against production
-- 2026-08-14. Orders 33/34 would have sorted competence above Bubbles.
--
-- The employee page carries required_roles='USER' and no permission, matching
-- its three KNOWLEDGE siblings: every employee can be in an audience.
-- RouteAccessGuard is a UI guard only — the BFF gate behind each route is
-- what actually protects anything.
--
-- NOTE FOR STAGING: page_registry is wiped by the nightly refresh. Re-seed
-- after a reset.
-- =============================================================================

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission,
     display_order, section, icon_name, is_external, external_url)
VALUES ('competence', 'Competence', 1, '/knowledge/competence',
        'USER', NULL, 140, 'KNOWLEDGE', 'ShieldCheck', 0, NULL),
       ('competence-admin', 'Competence admin', 1, '/knowledge/competence/admin',
        'USER', 'competence:approve', 150, 'KNOWLEDGE', 'ClipboardCheck', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label          = VALUES(page_label),
    react_route         = VALUES(react_route),
    required_roles      = VALUES(required_roles),
    required_permission = VALUES(required_permission),
    display_order       = VALUES(display_order),
    section             = VALUES(section),
    icon_name           = VALUES(icon_name);
