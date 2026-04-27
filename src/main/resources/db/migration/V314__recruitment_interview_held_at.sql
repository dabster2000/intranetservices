-- Slice 3b: track when an interview was actually held.
-- Populated by ScorecardService when status transitions SCHEDULED → HELD on the
-- first scorecard submission. Required by ScorecardOverdueDmWorker (24h/48h cadence).

ALTER TABLE recruitment_interview
    ADD COLUMN held_at DATETIME NULL AFTER scheduled_at;

-- Backfill for legacy rows so the overdue-DM worker doesn't fire on stale data.
-- For interviews already in HELD or ROUNDED_UP, treat scheduled_at as the held time.
UPDATE recruitment_interview
SET    held_at = scheduled_at
WHERE  held_at IS NULL
  AND  status IN ('HELD', 'ROUNDED_UP');
