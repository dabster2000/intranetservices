-- ===================================================================
-- V445: Recruitment ATS — interview room booking (Graph resource)
-- ===================================================================
-- Feature: Recruitment ATS expansion (P11 follow-up: Graph calendar
--          room booking)
-- Domain:  recruitmentservice (interview loop)
--
-- Purpose:
--   Interviews can book a physical meeting room through the Outlook
--   event: the room's mailbox is invited as a Graph "resource"
--   attendee, which places the booking in the room's own calendar.
--   The chosen room mailbox must persist on the interview row so a
--   reschedule (PATCH rebuilds the full attendee list) keeps — and
--   moves — the booking instead of silently dropping it.
--
-- Design notes:
--   * room_email is the room MAILBOX address from Graph
--     /places/microsoft.graph.room — an org resource, PII-free.
--     NULL = no room booked (Teams interviews, manual scheduling,
--     rows created before this migration).
--   * location (V442) stays the human-readable label; the UI fills it
--     with the room's display name when a room is picked.
--   * No backfill: existing rows keep NULL and behave exactly as
--     before.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   ADD COLUMN IF NOT EXISTS (V430 convention).
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: additive and harmless to leave in place. Full removal:
--     ALTER TABLE recruitment_interviews DROP COLUMN room_email;
-- ===================================================================

ALTER TABLE recruitment_interviews
    ADD COLUMN IF NOT EXISTS room_email VARCHAR(255) NULL
        COMMENT 'Room mailbox invited as Graph "resource" attendee; NULL = no room booked'
        AFTER location;
