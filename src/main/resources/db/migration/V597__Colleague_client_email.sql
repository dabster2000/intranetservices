-- ============================================================================
-- V597: The client mailbox addresses our OWN consultants hold
-- ============================================================================
-- Feature: CRM spec §3.2/§3.7 — the calendar sync's colleague-at-client filter
--          (decision D2, 2026-09-14).
-- Domain:  aggregates/crm/calendar
--
-- WHAT THIS IS
--   A consultant placed at a client is given a mailbox AT THAT CLIENT.
--   mygx@novonordisk.com is Malthe Yde Andreasen. qnte@novonordisk.com is Nicolas
--   de Teilmann. To the domain join in AccountCalendarSyncService those addresses
--   are indistinguishable from the client's own CFO — novonordisk.com is a
--   client_domain row (V585) — so they were written into account_meeting_attendee
--   as EXTERNAL people the firm had met, and drawn in the account's relationship
--   graph as client contacts. The firm was being shown its own consultants as its
--   network into Novo Nordisk.
--
--   The filter that fixes that matches the attendee's DISPLAY NAME against the
--   names of people employed here on the date of the meeting. This table exists
--   because that match has a hole.
--
-- THE HOLE: GRAPH DOES NOT ALWAYS SEND A NAME
--   Microsoft Graph returns the same person with a display name in one mailbox's
--   answer and as a bare address in another's. mygx@novonordisk.com arrives as
--   "MYGX (Malthe Yde Andreasen)" on ten attendee rows and as a bare
--   mygx@novonordisk.com on twenty others, and a bare address can never be
--   name-matched. Malthe therefore kept reappearing on the Novo Nordisk account,
--   and — because the relationship graph grouped externals by display name — as a
--   SECOND person distinct from the named one. Six addresses in production are
--   affected. Measured 2026-09-14.
--
-- WHY IT IS A TABLE AND NOT A CONSTANT, AND WHY IT HAS NO UI
--   It is NOT hand-maintained and there is nothing to administer. Every row
--   records a fact the sync has already worked out for itself: on some run the
--   display name WAS present, the name match succeeded, and the address was
--   written down so that the next run — which may see only the bare address —
--   still knows whose it is. The sync writes it; the sync reads it; a human only
--   ever looks. A hard-coded list would have to be edited by an engineer every
--   time somebody starts at a client, which is the kind of maintenance that never
--   happens and then quietly stops being true.
--
--   source distinguishes the two ways a row can come to exist. LEARNED is the
--   sync's own inference and is what the nightly run writes. MANUAL is a human
--   correction, and CalendarFilterService.rememberColleagueEmails will NOT
--   overwrite a MANUAL row's user_uuid or display_name with an inferred one — a
--   correction that the next nightly run undoes is not a correction. There is no
--   endpoint that writes MANUAL today; the value exists so that a datafix can,
--   without the sync erasing it the same night.
--
-- CREATED EMPTY, ON PURPOSE
--   The initial six addresses are seeded by a hand-run datafix, not from here: the
--   mapping is derived from production account_meeting_attendee rows that do not
--   exist in every environment, and a migration that hard-codes named employees'
--   client addresses would put personal data in the schema history forever. After
--   the seed the sync maintains it on its own.
--
-- WHY email IS THE PRIMARY KEY
--   The address IS the identity (decision D4, 2026-09-14). One address belongs to
--   exactly one person; the same person can hold several — a consultant who moves
--   from one client to the next gets a new one and keeps neither — so one row per
--   address with a plain user_uuid, and no attempt at a (user, client) key.
--   Stored LOWER-CASED, because e-mail addresses are compared case-insensitively
--   here and the sync lower-cases every address before it compares anything.
--   VARCHAR(320) is the RFC 5321 maximum and matches account_meeting_attendee.email.
--
-- NO FOREIGN KEY onto user, matching V219, V584, V585 and V588. The rest of this
--   schema does not have one and a single table growing one would be a surprise,
--   not a safety net.
--
-- first_seen_at / last_seen_at: first_seen_at is the day the sync discovered the
--   mapping and never moves. last_seen_at is refreshed on every run that sees the
--   address, which is the only way anybody can later tell a live mapping from one
--   left behind by a consultant who finished at that client in 2024. Nothing
--   prunes on it yet; it is recorded so that something can.
--
-- RESERVED-WORD CHECK: none of email, user_uuid, display_name, source,
--   first_seen_at or last_seen_at is a MariaDB reserved word. (SOURCE is a mysql
--   CLIENT command, not a server keyword, and needs no quoting here.)
--
-- STAGING SYNC — a deliberate NON-exclusion, recorded so nobody has to re-derive it
--   account_meeting, account_meeting_attendee and user_calendar_consent are on
--   sp_sync_prod_to_staging's exclusion list (V588) because they hold third-party
--   personal data. This table holds none: every row maps a TRUSTWORKS EMPLOYEE to
--   an address issued to that employee. It is the same category as user.email,
--   which the procedure copies and anonymises, and staging is strictly better off
--   with the filter working than with it inert. It is therefore NOT added to the
--   exclusion list, and the 300-line procedure is not re-emitted for it. If that
--   judgement is ever revisited, re-emit the procedure from the NEWEST migration
--   that defines it, never from an older one (V588's own warning).
--
-- COLLATION: utf8mb4_general_ci, as V585-V596.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS. No data.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   Drop the table crm_colleague_client_email. The filter then degrades to
--   name-matching only, and bare addresses reappear as external contacts on the
--   accounts where our own consultants sit.
-- ============================================================================

CREATE TABLE IF NOT EXISTS crm_colleague_client_email
(
    email         VARCHAR(320)               NOT NULL COMMENT 'Lower-cased. The address a Trustworks employee holds at a client',
    user_uuid     CHAR(36)                   NOT NULL COMMENT 'The employee whose address it is',
    display_name  VARCHAR(255)               NULL COMMENT 'The name Graph gave it on the run that identified it; for humans reading the table',
    source        ENUM ('LEARNED', 'MANUAL') NOT NULL DEFAULT 'LEARNED' COMMENT 'LEARNED = the sync inferred it from a display-name match; MANUAL = a human corrected it and the sync must not overwrite it',
    first_seen_at DATETIME                   NOT NULL COMMENT 'Never moves',
    last_seen_at  DATETIME                   NOT NULL COMMENT 'Refreshed by every run that sees the address; the only way to spot a mapping that has gone stale',
    PRIMARY KEY (email),
    KEY idx_crm_colleague_client_email_user (user_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Addresses our own consultants hold at client domains. Self-learning: written by the calendar sync when a display-name match succeeds, read on every run so a later bare address is still recognised. Not hand-maintained, no UI.';
