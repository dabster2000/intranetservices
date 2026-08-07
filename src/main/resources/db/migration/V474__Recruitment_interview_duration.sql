-- ===================================================================
-- V474: Recruitment ATS — interview duration
-- ===================================================================
-- Feature: Interview scheduling — duration picker (30/60/90/120/240
--          minutes) in the ScheduleInterviewDialog
-- Domain:  recruitmentservice (interview loop)
--
-- Purpose:
--   The interview length was hard-coded to 60 minutes in
--   RecruitmentCalendarService (Outlook event end time and the Graph
--   free/busy window). Schedulers can now pick a duration, so it must
--   persist on the interview row: a reschedule rebuilds the Outlook
--   event and must keep the chosen length, and the free/busy lookups
--   (rooms + interviewers) probe the same window that gets booked.
--
-- Design notes:
--   * Default 60 keeps every existing row at the length its Outlook
--     event was actually created with — no backfill needed.
--   * The API accepts 15..480; the UI offers 30/60/90/120/240. The
--     column is a plain INT so future picker changes need no DDL.
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   ADD COLUMN IF NOT EXISTS (V430 convention).
--
-- Author: Claude Code
-- Date:   2026-08-06
-- Rollback: additive and harmless to leave in place. Full removal:
--     ALTER TABLE recruitment_interviews DROP COLUMN duration_minutes;
-- ===================================================================

ALTER TABLE recruitment_interviews
    ADD COLUMN IF NOT EXISTS duration_minutes INT NOT NULL DEFAULT 60
        COMMENT 'Interview length in minutes; drives the Outlook event end time and free/busy windows'
        AFTER scheduled_at;
