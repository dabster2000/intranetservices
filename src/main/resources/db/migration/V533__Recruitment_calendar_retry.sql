-- ===================================================================
-- Recruitment: calendar retry marker (candidate-invite robustness)
--
-- Purpose:
--   Give a failed Graph calendar write on an interview a durable retry
--   marker, so a transient failure (429 / 5xx / timeout) is retried by
--   the RecruitmentCalendarRepairJob sweep instead of being swallowed.
--
--   Trigger: production 2026-08-24 15:08 — Graph answered 504 Gateway
--   Timeout on the CANDIDATE event create for interview
--   fdb0eb14-c1d3-48ad-b11f-4ca2abbf1907. The code logged a WARN
--   ("internal event stands alone") and moved on. Because the two-event
--   split never puts the candidate on the internal event, that WARN
--   meant: the candidate had NO invitation at all, nobody was told, and
--   the timeline said calendar_synced=true (that flag tracks only the
--   internal event). The interview passed the next day without an
--   invite ever existing.
--
-- Columns (on recruitment_interviews — the interview row IS the work
-- item; what needs repairing is derived from the linkage columns
-- graph_event_id / graph_candidate_event_id / status, never stored):
--
--   calendar_retry_at          When the sweep should retry; NULL = no
--                              repair pending. Also the claim lease:
--                              the sweep's atomic claim pushes it
--                              forward, so a second instance skips the
--                              row (the scheduling-outbox idiom).
--   calendar_retry_attempts    Attempts burned, inline try included.
--                              Dead-letters at the job's cap (8, the
--                              scheduling-outbox cap) with a Slack
--                              alert to HR and a terminal
--                              INTERVIEW_CANDIDATE_INVITE_FAILED event.
--   calendar_retry_last_error  Last classified Graph error, truncated
--                              to 500 chars (STRICT_TRANS_TABLES).
--
-- Additive only — old tasks during the ECS bake window ignore the new
-- columns.
-- ===================================================================

ALTER TABLE recruitment_interviews
    ADD COLUMN calendar_retry_at DATETIME(3) NULL,
    ADD COLUMN calendar_retry_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN calendar_retry_last_error VARCHAR(500) NULL;

-- The sweep's work-list probe: "due now" on a table that is almost
-- entirely NULL in this column.
CREATE INDEX idx_ri_calendar_retry ON recruitment_interviews (calendar_retry_at);
