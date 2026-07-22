-- ===================================================================
-- V433: Recruitment ATS expansion — Phase 1: event backbone
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18, spec §3.2–3.4)
-- Domain:  recruitmentservice.events (new event-sourcing-lite backbone)
--
-- Purpose:
--   The append-only event store + reactor bookkeeping that every later
--   ATS phase (P2–P25) writes to and reads from. State tables remain
--   authoritative for current state; this stream is authoritative for
--   history, timelines, and reactor-driven side effects.
--
-- Tables:
--   recruitment_events            — the event stream (append-only; the
--                                   ONLY later mutation is GDPR
--                                   anonymization rewriting pii/pii_state,
--                                   arriving in P19)
--   recruitment_reactor_offsets   — per-reactor catch-up watermark
--   recruitment_reactor_deliveries— per-event dedupe for live (EventBus)
--                                   deliveries that run ahead of the
--                                   watermark; pruned as the watermark
--                                   sweeps past
--
-- Design notes (deviations from the spec §3.3 sketch, deliberate):
--   * seq (BIGINT AUTO_INCREMENT) is the PRIMARY KEY, event_id
--     (UUIDv7, VARCHAR(36)) is UNIQUE — not the other way around.
--     InnoDB clusters on the PK; a monotonically increasing PK gives
--     append-only insert locality, and the three secondary indexes
--     carry an 8-byte PK pointer instead of a 36-byte one. All spec
--     semantics survive: event_id stays the globally unique identity /
--     idempotency key, seq stays the global order + offset key.
--   * UUIDs are VARCHAR(36), not BINARY(16): every other table in this
--     schema stores UUIDs as VARCHAR(36) (see recruitment_candidates),
--     and candidate_uuid/application_uuid/position_uuid must JOIN
--     against those columns without conversion.
--   * recruitment_reactor_deliveries is additional to the plan's two
--     tables: the plan requires a per-event idempotency key (event
--     UUID) *besides* the offset watermark. In-memory dedupe would
--     double-fire external side effects (Slack, mail) after every
--     deploy, so the dedupe is durable.
--
-- Collation: utf8mb4_general_ci from the start — V315 already had to
--   repair the V311–V314 tables from unicode_ci because JOINs against
--   legacy tables (users, companies) fail with "Illegal mix of
--   collations".
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   all DDL is IF NOT EXISTS, seeds are INSERT IGNORE.
--
-- Author: Claude Code
-- Date:   2026-07-22
-- Rollback: inert without the P1 backend image; tables are additive and
--   harmless to leave in place. Full removal (only if the programme is
--   abandoned):
--     DROP TABLE recruitment_reactor_deliveries;
--     DROP TABLE recruitment_reactor_offsets;
--     DROP TABLE recruitment_events;
--     DELETE FROM app_settings WHERE setting_key IN
--       ('recruitment.pipeline.enabled','recruitment.interviews.enabled',
--        'recruitment.gdpr.enabled');
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. The event stream
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_events (
    seq BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY
        COMMENT 'Global total order; reactor offsets key on this. AUTO_INCREMENT gaps (rolled-back appends) are expected and harmless.',

    event_id VARCHAR(36) NOT NULL
        COMMENT 'UUIDv7 (time-ordered). Globally unique event identity and idempotency key.',

    event_type VARCHAR(64) NOT NULL
        COMMENT 'RecruitmentEventType enum name, e.g. APPLICATION_STAGE_CHANGED. Full catalog: spec §3.4.',

    -- Subject references (soft FKs, NO DB constraint — module convention;
    -- position/application tables arrive in P2/P4)
    candidate_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK recruitment_candidates.uuid; NULL for position-only events',
    application_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK recruitment_applications.uuid (table arrives in P4)',
    position_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK recruitment_positions.uuid (table arrives in P2)',

    -- Actor attribution
    actor_uuid VARCHAR(36) NULL
        COMMENT 'Soft-FK users.uuid from X-Requested-By; NULL for SYSTEM/CANDIDATE/SCHEDULER actors',
    actor_type VARCHAR(20) NOT NULL
        COMMENT 'Java enum RecruitmentActorType: USER | SYSTEM | CANDIDATE | SCHEDULER',

    occurred_at DATETIME(3) NOT NULL
        COMMENT 'UTC. Set by the recorder at append time; also the catch-up grace-horizon key.',

    visibility VARCHAR(10) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'Java enum RecruitmentEventVisibility: NORMAL | CIRCLE. CIRCLE = partner-track, filtered per viewer.',

    -- The payload/pii split IS the anonymization contract (spec §3.3):
    -- payload carries structural facts only; every personal-data fragment
    -- goes in pii. GDPR anonymization (P19) rewrites pii to
    -- {"anonymized": true} and flips pii_state — payload/order/type are
    -- never touched.
    payload JSON NULL
        COMMENT 'Structural facts only: stage codes, template ids, counts. NEVER personal data.',
    pii JSON NULL
        COMMENT 'The ONLY place personal data may appear (names in prose, note text, salary...). Rewritten on anonymization.',
    pii_state VARCHAR(12) NOT NULL DEFAULT 'NONE'
        COMMENT 'Java enum RecruitmentPiiState: PRESENT | ANONYMIZED | NONE',

    UNIQUE KEY uk_recruitment_events_event_id (event_id),

    CONSTRAINT chk_re_actor_type_enum
        CHECK (actor_type IN ('USER','SYSTEM','CANDIDATE','SCHEDULER')),
    CONSTRAINT chk_re_visibility_enum
        CHECK (visibility IN ('NORMAL','CIRCLE')),
    CONSTRAINT chk_re_pii_state_enum
        CHECK (pii_state IN ('PRESENT','ANONYMIZED','NONE'))

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: append-only domain event stream (spec §3.3 envelope)';

-- Spec §3.3 index set: (candidate_uuid, seq) for timelines,
-- (event_type, seq) for typed projections, (seq) is the PK.
CREATE INDEX IF NOT EXISTS idx_re_candidate_seq  ON recruitment_events (candidate_uuid, seq);
CREATE INDEX IF NOT EXISTS idx_re_type_seq       ON recruitment_events (event_type, seq);

-- -------------------------------------------------------------------
-- 2. Reactor offsets (catch-up watermark)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_reactor_offsets (
    reactor_name VARCHAR(100) NOT NULL PRIMARY KEY
        COMMENT 'Stable reactor identity (RecruitmentReactor.name()). Renaming a reactor re-seeds it to head.',
    last_processed_seq BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'Watermark: every event with seq <= this is settled for this reactor (processed or deliberately skipped). Seeded to the stream head at reactor deploy — no historical replay.',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
        COMMENT 'Last watermark advance'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: per-reactor catch-up watermark';

-- -------------------------------------------------------------------
-- 3. Live-delivery dedupe (runs ahead of the watermark)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_reactor_deliveries (
    reactor_name VARCHAR(100) NOT NULL
        COMMENT 'RecruitmentReactor.name()',
    event_seq BIGINT UNSIGNED NOT NULL
        COMMENT 'recruitment_events.seq (1:1 with event_id) handled by the live EventBus path before the watermark reached it',
    status VARCHAR(12) NOT NULL DEFAULT 'PROCESSED'
        COMMENT 'PROCESSED | SKIPPED (poison event deliberately advanced past)',
    processed_at DATETIME(3) NOT NULL
        COMMENT 'UTC',
    PRIMARY KEY (reactor_name, event_seq),
    CONSTRAINT chk_rrd_status_enum
        CHECK (status IN ('PROCESSED','SKIPPED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: per-event live-delivery dedupe; rows pruned once the watermark passes them';

-- -------------------------------------------------------------------
-- 4. Seed the three core feature flags (spec §11), all OFF.
--    Companion toggles (recruitment.ai.*, recruitment.slack.*) are
--    seeded by their own phases (P9, P13, ...). INSERT IGNORE keeps the
--    migration idempotent and never overwrites an admin's later edit.
-- -------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.pipeline.enabled',   'false', 'recruitment'),
    ('recruitment.interviews.enabled', 'false', 'recruitment'),
    ('recruitment.gdpr.enabled',       'false', 'recruitment');
