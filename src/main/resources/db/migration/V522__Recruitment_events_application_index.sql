-- Landing "My tasks" / SLA sweep: the idle clock reads the newest progress
-- event per application (RecruitmentIdleRule.PROGRESS_EVENTS) in one grouped
-- query. recruitment_events had indexes on (candidate_uuid, seq) and
-- (event_type, seq) only, so that query full-scanned the stream — cheap
-- today, unbounded as the append-only table grows.
--
-- Mirrors idx_re_candidate_seq: the uuid leads, seq follows so the index is
-- also usable for "the newest event on this application".
ALTER TABLE recruitment_events
    ADD INDEX idx_re_application_seq (application_uuid, seq);
