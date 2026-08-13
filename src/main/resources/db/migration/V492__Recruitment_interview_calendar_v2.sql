-- ===================================================================
-- V492: Recruitment ATS — interview calendar v2 (Teams + organizer)
-- ===================================================================
-- Feature: Interview scheduling modernization Phase 2 — Teams
--          meetings, invitation recovery and organizer hardening
-- Domain:  recruitmentservice (interview loop)
--
-- Purpose:
--   * online_meeting / join_url: the scheduler can make the Outlook
--     event a Teams meeting; Graph returns the joinUrl and the UI
--     shows it on the interview row.
--   * graph_organizer: the mailbox the Graph event was created under.
--     Until now the organizer was derived AT CALL TIME from the first
--     interviewer, so removing interviewer #1 on a reschedule made
--     updateEvent PATCH the WRONG mailbox for the stored event id.
--     update/cancel now always use the stored organizer.
--
-- Design notes:
--   * Backfill: rows that already have an Outlook event were created
--     under their first interviewer's mailbox — store exactly that, so
--     the very next reschedule of an old row PATCHes the right place.
--     Rows without an event stay NULL (nothing to address).
--   * join_url is VARCHAR(1024): Teams join links commonly exceed 255.
--   * New events are created under the shared organizer mailbox
--     (dk.trustworks.recruitment.graph.calendar.organizer, D1:
--     career@trustworks.dk) — a config concern, not a schema one.
-- ===================================================================

ALTER TABLE recruitment_interviews
    ADD COLUMN online_meeting  BIT          NOT NULL DEFAULT 0,
    ADD COLUMN join_url        VARCHAR(1024) NULL,
    ADD COLUMN graph_organizer VARCHAR(255)  NULL;

UPDATE recruitment_interviews ri
JOIN user u ON u.uuid = JSON_UNQUOTE(JSON_EXTRACT(ri.interviewer_uuids, '$[0]'))
SET ri.graph_organizer = u.email
WHERE ri.graph_event_id IS NOT NULL
  AND u.email IS NOT NULL
  AND u.email <> '';
