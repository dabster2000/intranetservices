-- ============================================================================
-- V601 — the calendar as the door for coffee meetings (cut 2)
--
-- Spec: docs/specs/intra-crm-customers-former-prospects-contacts-2026-09-14.md §2.5
--
-- THE PROBLEM
--   Rule 1 of AccountCalendarSyncService drops any meeting with no client_domain match
--   "without being written anywhere". Only 34 of 307 clients have a domain, so a
--   company several colleagues keep meeting is invisible to Intra for ever. That is
--   precisely the population this whole spec is about.
--
-- WHAT IS STORED — and what is not
--   The DOMAIN and a COUNT. Never an attendee, never a subject, never a display name.
--   A domain identifies a COMPANY, not a person, which is why this is a smaller privacy
--   footprint than account_meeting_attendee already has. Only mailboxes on the existing
--   consent list are read (CalendarConsentService, unchanged).
--
-- WHY TWO TABLES
--   calendar_unmatched_domain is the spec's table: one row per domain, with the counts
--   the "Seen in calendars" panel shows and the decision somebody took.
--
--   calendar_unmatched_meeting is the ledger those counts are DERIVED from, and it is
--   not optional. The sync re-reads the last 14 days on every run (INCREMENTAL_BACK_DAYS),
--   so a counter that were merely incremented would count the same coffee fourteen times
--   and report "3 meetings since June" as 42. The ledger's primary key is deterministic
--   in (graph event, mailbox, domain), so a re-sync overwrites instead of adding, and the
--   aggregate columns are recomputed from it.
--
-- RETENTION
--   Rows purge after 12 months without a meeting (CalendarSuggestionService.purge()),
--   which is the same window the first-run calendar read uses.
--
-- RESERVED-WORD CHECK
--   `domain`, `status`, `first_seen`, `last_seen` are not reserved in MariaDB 10.11.
--   `LINES` — the word that broke V534 — does not appear.
--
-- NO FOREIGN KEYS onto client / user — matching V585, V586, V591 and V598.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   DROP TABLE calendar_unmatched_meeting, calendar_unmatched_domain;
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. One row per domain: the counts the panel shows, and the decision taken on it
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS calendar_unmatched_domain
(
    domain             VARCHAR(190) NOT NULL COMMENT 'Lower-cased. Freemail, our own and deny-listed domains never land here',
    meetings_90d       INT          NOT NULL DEFAULT 0,
    meetings_total     INT          NOT NULL DEFAULT 0,
    people_count       INT          NOT NULL DEFAULT 0 COMMENT 'Distinct Trustworks mailboxes that met the domain',
    first_seen         DATE         NULL,
    last_seen          DATE         NULL,
    status             ENUM ('NEW','IGNORED','LINKED') NOT NULL DEFAULT 'NEW',
    linked_client_uuid CHAR(36)     NULL COMMENT 'Set when somebody said this domain belongs to an existing client',
    decided_by         CHAR(36)     NULL,
    decided_at         DATETIME     NULL,
    created_at         DATETIME     NOT NULL,
    PRIMARY KEY (domain),
    KEY idx_cud_status (status),
    KEY idx_cud_last_seen (last_seen)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Meeting domains Intra could not attribute to any client (spec §2.5)';

-- ----------------------------------------------------------------------------
-- 2. The ledger the counts are derived from. One row per (event, mailbox, domain).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS calendar_unmatched_meeting
(
    uuid        CHAR(36)     NOT NULL COMMENT 'Deterministic in (graph event, mailbox, domain), so a re-sync updates instead of adding',
    domain      VARCHAR(190) NOT NULL,
    user_uuid   CHAR(36)     NOT NULL COMMENT 'The mailbox owner — what people_count counts',
    occurred_on DATE         NOT NULL,
    synced_at   DATETIME     NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_cum_domain (domain),
    KEY idx_cum_occurred (occurred_on)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Idempotency ledger for calendar_unmatched_domain — no names, no subjects';
