-- ===================================================================
-- V444: Recruitment ATS expansion — Phase 13: Slack inbound
--       infrastructure
-- ===================================================================
-- Feature: Recruitment ATS expansion (plan 2026-07-18 §P13, Slack
--          companion spec §4.2–4.3)
-- Domain:  recruitmentservice (Slack inbound dispatch)
--
-- Purpose:
--   1. recruitment_slack_inbound_dedupe — short-TTL atomic claims for
--      inbound Slack payload ids. Slack's Events API retries every
--      delivery up to 3× on slow/failed acks, and interactive payloads
--      can double-fire; the dispatcher claims the payload id BEFORE
--      dispatching (INSERT IGNORE — the V441 claims idiom), so a retry
--      finds the row and is dropped without a second command
--      execution. Rows are pruned opportunistically by the dispatcher
--      once older than the TTL (24 h — Slack's own retry horizon is
--      minutes, so this is generous).
--
--   2. Seed the twelve recruitment.slack.* feature toggles (Slack spec
--      §3.1), all 'false' — every Slack feature is opt-in, defaults
--      dark. recruitment.slack.interactivity.enabled is the MASTER
--      kill switch for all inbound handling; the other eleven gate
--      individual features shipping in P14–P25 (their toggles persist
--      now, take effect when their phase ships).
--
--   3. Rename the settings tab page-registry row: the P9 tab
--      ('Recruitment AI') gains a Slack section in P13 and presents as
--      'Recruitment AI & Slack' (Slack spec §3.2).
--
-- Design notes:
--   * payload_key VARCHAR(191): '<surface>:<provider id>' (e.g.
--     'events:Ev0123456789', 'interactions:trigger_id.action_id') —
--     the PRIMARY KEY alone IS the idempotency guarantee, same as
--     V441's case_key. 191 keeps the PK inside one index page under
--     utf8mb4; Slack ids are far shorter.
--   * No FK anywhere: the claim is a lock record, not a projection; it
--     carries no personal data, so GDPR anonymization (P19) never
--     touches it.
--   * received_at is indexed for the TTL prune (DELETE ... WHERE
--     received_at < NOW() - INTERVAL 24 HOUR).
--
-- Collation: utf8mb4_general_ci — module convention since V315/V433.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   DDL is IF NOT EXISTS, seeds are INSERT IGNORE (never clobber an
--   admin's later edit), the title UPDATE is naturally idempotent.
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: inert without the P13 backend image; the table is additive
--   and harmless to leave in place. Full removal:
--     DROP TABLE recruitment_slack_inbound_dedupe;
--     DELETE FROM app_settings WHERE setting_key LIKE 'recruitment.slack.%.enabled'
--        OR setting_key = 'recruitment.slack.interactivity.enabled';
--     UPDATE page_registry SET page_label = 'Recruitment AI'
--        WHERE page_key = 'settings-recruitment-ai';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. Inbound payload dedupe (short-TTL atomic claims)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recruitment_slack_inbound_dedupe (
    payload_key VARCHAR(191) NOT NULL PRIMARY KEY
        COMMENT 'Surface-prefixed Slack payload id (events:<event_id> | interactions:<trigger_id>[.<action_id>] | commands:<trigger_id>). The PK IS the idempotency guarantee: only the transaction whose INSERT IGNORE affected a row dispatches.',
    slack_team_id VARCHAR(32) NULL
        COMMENT 'Slack workspace (team) id — diagnostic context only, not part of the key',
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT 'UTC. Claim time; rows older than the 24 h TTL are pruned opportunistically by the dispatcher',
    INDEX idx_rsid_received_at (received_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Recruitment ATS: short-TTL dedupe claims for inbound Slack payloads (Events API retries 3x, interactive payloads double-fire)';

-- -------------------------------------------------------------------
-- 2. Seed the twelve recruitment.slack.* toggles (Slack spec §3.1),
--    all OFF. INSERT IGNORE keeps re-runs from clobbering admin edits.
-- -------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.slack.interactivity.enabled',    'false', 'recruitment'),
    ('recruitment.slack.cards.enabled',            'false', 'recruitment'),
    ('recruitment.slack.partner-channels.enabled', 'false', 'recruitment'),
    ('recruitment.slack.refer.enabled',            'false', 'recruitment'),
    ('recruitment.slack.triage-actions.enabled',   'false', 'recruitment'),
    ('recruitment.slack.capture.enabled',          'false', 'recruitment'),
    ('recruitment.slack.lookup.enabled',           'false', 'recruitment'),
    ('recruitment.slack.scorecard.enabled',        'false', 'recruitment'),
    ('recruitment.slack.app-home.enabled',         'false', 'recruitment'),
    ('recruitment.slack.morning-brief.enabled',    'false', 'recruitment'),
    ('recruitment.slack.dpo-digest.enabled',       'false', 'recruitment'),
    ('recruitment.slack.assistant.enabled',        'false', 'recruitment');

-- -------------------------------------------------------------------
-- 3. The shared settings tab now presents as 'Recruitment AI & Slack'
--    (P9 seeded it as 'Recruitment AI'; naturally idempotent).
-- -------------------------------------------------------------------
UPDATE page_registry
   SET page_label = 'Recruitment AI & Slack'
 WHERE page_key = 'settings-recruitment-ai';
