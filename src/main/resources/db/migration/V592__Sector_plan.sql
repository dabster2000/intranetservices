-- ============================================================================
-- V592: The sector plan — a thin plan per segment, and the link that lets an
--       account objective serve a sector objective
-- ============================================================================
-- Feature: docs/specs/intra-crm-sectors-gtm-teams-2026-09-13.md §3–§4 (V591 in that
--          spec; renumbered because V590 was taken by client notes on staging).
-- Domain:  aggregates/crm/sector
--
-- WHAT THIS IS
--   Session #2 of the CRM spec said "sector plans are roll-ups, never a separate
--   document"; session #3 defined a SECTOR-scoped plan with its own objectives. Hans
--   settled it on 2026-09-13 as BOTH: a thin record per segment — the four sentences, at
--   most four objectives, the open actions, a next review, a health — and everything
--   else derived from the accounts in the sector. The one typed connection between the
--   two levels is `client_plan_objective.sector_objective_uuid`: an account objective
--   may declare that it SERVES a sector objective, and the sector objective's status is
--   then derived from the account objectives serving it.
--
-- SEPARATE TABLES, THE SAME SHAPE
--   Column-for-column the account plan's tables (V586) minus the people: stakeholders
--   live on accounts, and a sector-level stakeholder is not in this cut. The shape is
--   kept identical on purpose — one TypeScript type, one set of reducers and one set of
--   components serve both plans. A `scope` column on client_plan was rejected: its
--   primary key is client_uuid and six child tables cascade on it; reshaping it would
--   mean rewriting the plan tab that shipped on 2026-09-13.
--
-- SECTOR OBJECTIVES ARE CLOSED, NEVER DELETED
--   client_plan_objective.sector_objective_uuid is a soft reference. Closing keeps the
--   row, so a link never dangles and an account objective can still say "served X
--   (closed)". The service refuses a link to a closed objective or to another segment's.
--
-- NULL RAG IS A REAL STATE (rule 8), JSON only on the snapshot, no FKs onto client /
-- user, real FKs inside the feature with ON DELETE CASCADE — all as V586.
--
-- RESERVED-WORD CHECK
--   `current` and `rank` avoided; `text` is a type name so the sentence column is
--   sentence_text; `segment`, `slot`, `hold`, `result` are not reserved.
--
-- COLLATION: utf8mb4_general_ci, as V585/V586.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, ADD INDEX IF NOT
-- EXISTS; no seed data.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   ALTER TABLE client_plan_objective DROP INDEX idx_plan_objective_sector,
--                                     DROP COLUMN sector_objective_uuid;
--   DROP TABLE sector_plan_snapshot, sector_plan_review_decision,
--              sector_plan_review_attendee, sector_plan_review, sector_plan_action,
--              sector_plan_objective, sector_plan_sentence, sector_plan;
--   No permission rows: the sector plan is gated by accounts:read / accounts:write.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The plan itself — one per segment
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sector_plan
(
    segment       ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
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
    PRIMARY KEY (segment)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='The sector plan the sector lead owns (sectors spec 3)';

-- ----------------------------------------------------------------------------
-- 2. The four sentences
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sector_plan_sentence
(
    segment       ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
    slot          ENUM ('WHY','CURRENT','DESIRED','QUESTION') NOT NULL,
    sentence_text VARCHAR(1000) NOT NULL,
    by_uuid       CHAR(36)      NOT NULL COMMENT 'Who wrote it — not who last confirmed the plan',
    validated_at  DATETIME      NOT NULL,
    PRIMARY KEY (segment, slot),
    CONSTRAINT fk_sector_plan_sentence_plan FOREIGN KEY (segment)
        REFERENCES sector_plan (segment) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Why this sector matters / where we stand / where we want to be / the open question';

-- ----------------------------------------------------------------------------
-- 3. Objectives — at most four; closed, never deleted
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sector_plan_objective
(
    uuid          CHAR(36)     NOT NULL,
    segment       ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
    ordinal       TINYINT      NOT NULL,
    category      ENUM ('COMMERCIAL','RELATIONSHIP','CAPABILITY','DELIVERY') NOT NULL,
    title         VARCHAR(300) NOT NULL,
    measure_kind  ENUM ('RATE','CONSULTANTS','NUMBER','TEXT') NOT NULL
        COMMENT 'RATE and CONSULTANTS read their current value over the sector and are never retyped',
    measure_label VARCHAR(200) NULL,
    baseline      VARCHAR(80)  NULL,
    target        VARCHAR(80)  NULL,
    unit          VARCHAR(40)  NULL,
    hold          TINYINT(1)   NOT NULL DEFAULT 0,
    target_date   DATE         NOT NULL,
    owner_uuid    CHAR(36)     NOT NULL,
    rag           ENUM ('GREEN','AMBER','RED') NULL COMMENT 'NULL = not assessed; the derived colour is computed, never stored',
    rag_why       VARCHAR(500) NULL,
    closed_at     DATETIME     NULL COMMENT 'Closed, never deleted — account objectives may still point here',
    created_at    DATETIME     NOT NULL,
    created_by    CHAR(36)     NOT NULL,
    modified_at   DATETIME     NOT NULL,
    modified_by   CHAR(36)     NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_sector_plan_objective_segment (segment, ordinal),
    CONSTRAINT fk_sector_plan_objective_plan FOREIGN KEY (segment)
        REFERENCES sector_plan (segment) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='At most four objectives per sector plan (sectors spec 3.2)';

-- ----------------------------------------------------------------------------
-- 4. Actions — a due date OR a cadence (rule 2, enforced in the service)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sector_plan_action
(
    uuid               CHAR(36)      NOT NULL,
    segment            ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
    title              VARCHAR(300)  NOT NULL,
    how                VARCHAR(500)  NULL,
    owner_uuid         CHAR(36)      NOT NULL,
    due                DATE          NULL,
    cadence            ENUM ('WEEKLY','MONTHLY','QUARTERLY') NULL,
    next_due           DATE          NULL,
    status             ENUM ('OPEN','DONE') NOT NULL DEFAULT 'OPEN',
    priority           ENUM ('HIGH','NORMAL') NOT NULL DEFAULT 'NORMAL',
    objective_uuid     CHAR(36)      NULL,
    result             VARCHAR(1000) NULL,
    closed_at          DATETIME      NULL,
    from_suggestion_id VARCHAR(120)  NULL,
    created_at         DATETIME      NOT NULL,
    created_by         CHAR(36)      NOT NULL,
    modified_at        DATETIME      NOT NULL,
    modified_by        CHAR(36)      NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_sector_plan_action_segment (segment, status),
    KEY idx_sector_plan_action_objective (objective_uuid),
    CONSTRAINT fk_sector_plan_action_plan FOREIGN KEY (segment)
        REFERENCES sector_plan (segment) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='The open actions on a sector plan (sectors spec 3)';

-- ----------------------------------------------------------------------------
-- 5. Reviews and the snapshot taken when one closes (rule 9)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sector_plan_review
(
    uuid        CHAR(36)      NOT NULL,
    segment     ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
    review_date DATE          NOT NULL,
    version     INT           NOT NULL COMMENT 'The plan version this review closed',
    outcome     VARCHAR(1000) NOT NULL,
    next_review DATE          NULL,
    created_at  DATETIME      NOT NULL,
    created_by  CHAR(36)      NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_sector_plan_review_segment (segment, review_date),
    CONSTRAINT fk_sector_plan_review_plan FOREIGN KEY (segment)
        REFERENCES sector_plan (segment) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='The sector review log — governance and history';

CREATE TABLE IF NOT EXISTS sector_plan_review_attendee
(
    review_uuid CHAR(36) NOT NULL,
    user_uuid   CHAR(36) NOT NULL,
    PRIMARY KEY (review_uuid, user_uuid),
    CONSTRAINT fk_sector_plan_review_attendee_review FOREIGN KEY (review_uuid)
        REFERENCES sector_plan_review (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS sector_plan_review_decision
(
    uuid          CHAR(36)     NOT NULL,
    review_uuid   CHAR(36)     NOT NULL,
    ordinal       TINYINT      NOT NULL,
    decision_text VARCHAR(500) NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_sector_plan_review_decision_review (review_uuid, ordinal),
    CONSTRAINT fk_sector_plan_review_decision_review FOREIGN KEY (review_uuid)
        REFERENCES sector_plan_review (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS sector_plan_snapshot
(
    segment                ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
    snapshot_date          DATE           NOT NULL,
    version                INT            NOT NULL,
    health                 ENUM ('GREEN','AMBER','RED') NOT NULL,
    objective_rags         JSON           NOT NULL COMMENT 'objective uuid -> RAG or null, read back whole',
    fact_fy_revenue        DECIMAL(14, 2) NOT NULL DEFAULT 0,
    fact_weighted_rate     DECIMAL(10, 2) NULL,
    fact_consultants       INT            NOT NULL,
    fact_open_leads        INT            NOT NULL,
    fact_weighted_pipeline DECIMAL(14, 2) NOT NULL,
    fact_accounts_by_band  JSON           NOT NULL COMMENT '{"STRATEGIC": n, "ACTIVE": n, "BACKLOG": n}',
    fact_plan_coverage     JSON           NOT NULL COMMENT '{"ok": n, "stale": n, "overdue": n, "missing": n}',
    created_at             DATETIME       NOT NULL,
    PRIMARY KEY (segment),
    CONSTRAINT fk_sector_plan_snapshot_plan FOREIGN KEY (segment)
        REFERENCES sector_plan (segment) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='What the sector and its plan looked like when the last review closed (rule 9)';

-- ----------------------------------------------------------------------------
-- 6. The connection: an account objective may serve a sector objective
-- ----------------------------------------------------------------------------
ALTER TABLE client_plan_objective
    ADD COLUMN IF NOT EXISTS sector_objective_uuid CHAR(36) NULL
        COMMENT 'sector_plan_objective.uuid — soft reference; the account objective serves it'
        AFTER rag_why;

ALTER TABLE client_plan_objective
    ADD INDEX IF NOT EXISTS idx_plan_objective_sector (sector_objective_uuid);
