-- ============================================================================
-- V586: The account plan — four sentences, objectives, actions, people, reviews
-- ============================================================================
-- Feature: CRM spec §3.3 + the account-plan data model of 2026-09-12, built out by
--          docs/specs/account-page-completion-2026-09-13.md.
-- Domain:  aggregates/crm/plan
--
-- WHAT THIS IS
--   The "Account plan & people" tab shipped on 2026-09-12 with no backend at all: the
--   plan was seeded from a hash of the client uuid and a person's edits were kept in
--   THEIR OWN BROWSER's localStorage. Two people looking at the same account saw two
--   different plans, and clearing site data threw one away. These tables are the
--   contract src/lib/crm/accountPlanTypes.ts was written against, filled in.
--
-- THE SHAPE IS THE FRONTEND'S, DELIBERATELY
--   Column-for-field with IAccountPlan. The tab, its dialogs and the pure reducers in
--   accountPlanReducers.ts are already agreed and tested; reshaping the model here
--   would mean rewriting a UI nobody asked to change.
--
-- WHAT IS *NOT* STORED
--   Everything Intra can work out: plan freshness, which required fields are missing,
--   the live value behind a RATE or CONSULTANTS objective, the suggested actions, the
--   contradictions, "what changed since the last review". Those stay derived in
--   planDerivations.ts. Storing a derived value is how it goes stale.
--
-- NULL RAG IS A REAL STATE
--   health_rag and objective.rag are NULLABLE and NULL means "not assessed" — rule 8
--   of the data model: blank must never read as green. A green with no reason in
--   rag_why is stored as NULL by the service for the same reason.
--
-- JSON COLUMNS ON THE SNAPSHOT ONLY
--   client_plan_snapshot.objective_rags and .stakeholder_ids are JSON because a
--   snapshot is a frozen photograph that is only ever read back whole, never queried
--   by member. Everything live is relational.
--
-- NO FOREIGN KEYS onto client / user / sales_lead, matching V219, V584 and V585.
--   Inside this feature's own tables the FKs ARE real (an objective's leads, a
--   stakeholder's relations, a review's decisions) with ON DELETE CASCADE, so deleting
--   an objective cannot leave orphaned link rows.
--
-- RESERVED-WORD CHECK
--   `current` and `rank` ARE MariaDB reserved words. The relation's 0–4 ratings are
--   therefore current_level / target_level, not current / target — an unquoted
--   reserved word fails CREATE TABLE at parse time, which is how V534's `lines` column
--   took down the staging canary on 2026-08-26. `status`, `role`, `source`, `result`
--   and `action` are non-reserved keywords in MariaDB and are used unquoted elsewhere
--   in this schema; `text` is a type name, so the sentence column is sentence_text.
--
-- COLLATION: utf8mb4_general_ci, as V585 — these join `client` and `user`.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS throughout; no seed data.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   DROP TABLE client_plan_snapshot, client_plan_review_decision,
--              client_plan_review_attendee, client_plan_review, client_plan_relation,
--              client_plan_stakeholder, client_plan_action,
--              client_plan_objective_lead, client_plan_objective,
--              client_plan_sentence, client_plan;
--   No permission rows: the plan is gated by accounts:read / accounts:write (V585).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The plan itself
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_plan
(
    client_uuid   CHAR(36)     NOT NULL,
    status        ENUM ('NONE','ACTIVE') NOT NULL DEFAULT 'NONE',
    version       INT          NOT NULL DEFAULT 1,
    next_review   DATE         NULL,
    health_rag    ENUM ('GREEN','AMBER','RED') NULL COMMENT 'NULL = not assessed; never read as green',
    health_why    VARCHAR(500) NULL,
    health_set_by CHAR(36)     NULL,
    health_set_at DATETIME     NULL,
    updated_at    DATETIME     NULL COMMENT 'When anyone last confirmed or saved the plan',
    updated_by    CHAR(36)     NULL,
    created_at    DATETIME     NOT NULL,
    created_by    CHAR(36)     NOT NULL,
    PRIMARY KEY (client_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='The account plan a person owns (CRM spec 3.3)';

-- ----------------------------------------------------------------------------
-- 2. The four sentences. The SLOT decides the assertion kind — nobody picks it,
--    so "we will own it" can never be stored as "we own it".
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_plan_sentence
(
    client_uuid   CHAR(36)      NOT NULL,
    slot          ENUM ('WHY','CURRENT','DESIRED','QUESTION') NOT NULL,
    sentence_text VARCHAR(1000) NOT NULL,
    by_uuid       CHAR(36)      NOT NULL COMMENT 'Who wrote it — not who last confirmed the plan',
    validated_at  DATETIME      NOT NULL,
    PRIMARY KEY (client_uuid, slot),
    CONSTRAINT fk_plan_sentence_plan FOREIGN KEY (client_uuid)
        REFERENCES client_plan (client_uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Why this matters / where we stand / where we want to be / the open question';

-- ----------------------------------------------------------------------------
-- 3. Objectives — at most four; the fifth is never read
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_plan_objective
(
    uuid          CHAR(36)     NOT NULL,
    client_uuid   CHAR(36)     NOT NULL,
    ordinal       TINYINT      NOT NULL,
    category      ENUM ('COMMERCIAL','RELATIONSHIP','CAPABILITY','DELIVERY') NOT NULL,
    title         VARCHAR(300) NOT NULL,
    measure_kind  ENUM ('RATE','CONSULTANTS','NUMBER','TEXT') NOT NULL
        COMMENT 'RATE and CONSULTANTS read their current value from Intra and are never retyped',
    measure_label VARCHAR(200) NULL,
    baseline      VARCHAR(80)  NULL,
    target        VARCHAR(80)  NULL,
    unit          VARCHAR(40)  NULL,
    hold          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'A floor to hold, not a level to reach',
    target_date   DATE         NOT NULL,
    owner_uuid    CHAR(36)     NOT NULL,
    rag           ENUM ('GREEN','AMBER','RED') NULL COMMENT 'NULL = not assessed',
    rag_why       VARCHAR(500) NULL COMMENT 'Required for a green; a green without one is stored as NULL',
    closed_at     DATETIME     NULL,
    created_at    DATETIME     NOT NULL,
    created_by    CHAR(36)     NOT NULL,
    modified_at   DATETIME     NOT NULL,
    modified_by   CHAR(36)     NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_plan_objective_client (client_uuid, ordinal),
    CONSTRAINT fk_plan_objective_plan FOREIGN KEY (client_uuid)
        REFERENCES client_plan (client_uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='At most four objectives per plan (CRM spec 3.3)';

CREATE TABLE IF NOT EXISTS client_plan_objective_lead
(
    objective_uuid CHAR(36) NOT NULL,
    lead_uuid      CHAR(36) NOT NULL COMMENT 'sales_lead.uuid — soft reference',
    PRIMARY KEY (objective_uuid, lead_uuid),
    CONSTRAINT fk_plan_objective_lead_objective FOREIGN KEY (objective_uuid)
        REFERENCES client_plan_objective (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Which leads an objective is riding on';

-- ----------------------------------------------------------------------------
-- 4. Actions. An action with neither a due date nor a cadence is incomplete
--    (rule 2) — enforced in AccountPlanService, not as a DB CHECK, because
--    MariaDB CHECK constraints in this schema have a history of being absent in
--    one environment and present in another (chk_consultant_positive_rate).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_plan_action
(
    uuid               CHAR(36)      NOT NULL,
    client_uuid        CHAR(36)      NOT NULL,
    title              VARCHAR(300)  NOT NULL,
    how                VARCHAR(500)  NULL,
    owner_uuid         CHAR(36)      NOT NULL,
    due                DATE          NULL,
    cadence            ENUM ('WEEKLY','MONTHLY','QUARTERLY') NULL,
    next_due           DATE          NULL COMMENT 'Next occurrence of a recurring action',
    status             ENUM ('OPEN','DONE') NOT NULL DEFAULT 'OPEN',
    priority           ENUM ('HIGH','NORMAL') NOT NULL DEFAULT 'NORMAL',
    objective_uuid     CHAR(36)      NULL,
    signal_uuid        CHAR(36)      NULL COMMENT 'account_signal.uuid — soft reference',
    stakeholder_uuid   CHAR(36)      NULL,
    result             VARCHAR(1000) NULL COMMENT 'What happened, written when it is closed',
    closed_at          DATETIME      NULL,
    from_suggestion_id VARCHAR(120)  NULL COMMENT 'Set when accepted from an Intra suggestion, so it is not offered twice',
    created_at         DATETIME      NOT NULL,
    created_by         CHAR(36)      NOT NULL,
    modified_at        DATETIME      NOT NULL,
    modified_by        CHAR(36)      NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_plan_action_client (client_uuid, status),
    KEY idx_plan_action_objective (objective_uuid),
    CONSTRAINT fk_plan_action_plan FOREIGN KEY (client_uuid)
        REFERENCES client_plan (client_uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='The open actions on a plan (CRM spec 3.3)';

-- ----------------------------------------------------------------------------
-- 5. People on the plan.
--
--    MINIMAL BY DESIGN: this is a plan-scoped stakeholder record, not a contact
--    database. No e-mail, no phone, no address — only what account work needs, and
--    only ever brought in by a signal or typed as a seat. The account-plan data model
--    calls this out explicitly as the safe first version.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_plan_stakeholder
(
    uuid             CHAR(36)     NOT NULL,
    client_uuid      CHAR(36)     NOT NULL,
    name             VARCHAR(255) NULL COMMENT 'NULL when only the seat is known',
    role_label       VARCHAR(255) NULL,
    title            VARCHAR(255) NOT NULL,
    unit             VARCHAR(255) NOT NULL,
    buying           ENUM ('DECISION_MAKER','ECONOMIC_BUYER','TECHNICAL_BUYER','USER_BUYER','GATEKEEPER') NOT NULL,
    influence        ENUM ('HIGH','MEDIUM','LOW') NOT NULL,
    validated_at     DATETIME     NOT NULL COMMENT 'When somebody last confirmed the row is still right',
    from_signal_uuid CHAR(36)     NULL COMMENT 'The signal that brought the name in; names are never typed',
    created_at       DATETIME     NOT NULL,
    created_by       CHAR(36)     NOT NULL,
    modified_at      DATETIME     NOT NULL,
    modified_by      CHAR(36)     NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_plan_stakeholder_client (client_uuid),
    CONSTRAINT fk_plan_stakeholder_plan FOREIGN KEY (client_uuid)
        REFERENCES client_plan (client_uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Third-party personal data. Purpose: commercial relationship management. Agreed retention 24 months after the account last saw activity; the purge job is NOT built.';

CREATE TABLE IF NOT EXISTS client_plan_relation
(
    uuid             CHAR(36) NOT NULL,
    stakeholder_uuid CHAR(36) NOT NULL,
    user_uuid        CHAR(36) NOT NULL,
    current_level    TINYINT  NOT NULL COMMENT '0-4; `current` is a MariaDB reserved word',
    target_level     TINYINT  NOT NULL COMMENT '0-4',
    role             ENUM ('PRIMARY','SUPPORTING') NOT NULL,
    last_interaction DATE     NULL,
    source           ENUM ('CONTRACT','CALENDAR','SIGNAL','SIGNAL_AND_CALENDAR') NOT NULL,
    assessed_by      CHAR(36) NOT NULL COMMENT 'A rating records who assessed it and when (rule 5)',
    assessed_at      DATETIME NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_plan_relation (stakeholder_uuid, user_uuid),
    CONSTRAINT fk_plan_relation_stakeholder FOREIGN KEY (stakeholder_uuid)
        REFERENCES client_plan_stakeholder (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='One Trustworks person''s relationship with one stakeholder';

-- ----------------------------------------------------------------------------
-- 6. Reviews and the snapshot taken when one closes (rule 9)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_plan_review
(
    uuid        CHAR(36)      NOT NULL,
    client_uuid CHAR(36)      NOT NULL,
    review_date DATE          NOT NULL,
    version     INT           NOT NULL COMMENT 'The plan version this review closed',
    outcome     VARCHAR(1000) NOT NULL,
    next_review DATE          NULL,
    created_at  DATETIME      NOT NULL,
    created_by  CHAR(36)      NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_plan_review_client (client_uuid, review_date),
    CONSTRAINT fk_plan_review_plan FOREIGN KEY (client_uuid)
        REFERENCES client_plan (client_uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='The review log — governance and history (CRM spec 3.3)';

CREATE TABLE IF NOT EXISTS client_plan_review_attendee
(
    review_uuid CHAR(36) NOT NULL,
    user_uuid   CHAR(36) NOT NULL,
    PRIMARY KEY (review_uuid, user_uuid),
    CONSTRAINT fk_plan_review_attendee_review FOREIGN KEY (review_uuid)
        REFERENCES client_plan_review (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS client_plan_review_decision
(
    uuid          CHAR(36)     NOT NULL,
    review_uuid   CHAR(36)     NOT NULL,
    ordinal       TINYINT      NOT NULL,
    decision_text VARCHAR(500) NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_plan_review_decision_review (review_uuid, ordinal),
    CONSTRAINT fk_plan_review_decision_review FOREIGN KEY (review_uuid)
        REFERENCES client_plan_review (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS client_plan_snapshot
(
    client_uuid             CHAR(36)       NOT NULL,
    snapshot_date           DATE           NOT NULL,
    version                 INT            NOT NULL,
    health                  ENUM ('GREEN','AMBER','RED') NOT NULL,
    objective_rags          JSON           NOT NULL COMMENT 'objective uuid -> RAG or null, read back whole',
    stakeholder_ids         JSON           NOT NULL,
    fact_weighted_rate      DECIMAL(10, 2) NULL,
    fact_consultants        INT            NOT NULL,
    fact_open_leads         INT            NOT NULL,
    fact_weighted_pipeline  DECIMAL(14, 2) NOT NULL,
    created_at              DATETIME       NOT NULL,
    PRIMARY KEY (client_uuid),
    CONSTRAINT fk_plan_snapshot_plan FOREIGN KEY (client_uuid)
        REFERENCES client_plan (client_uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='What the plan and the account looked like when the last review closed (rule 9)';
