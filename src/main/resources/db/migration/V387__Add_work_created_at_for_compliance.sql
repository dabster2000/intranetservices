-- V387__Add_work_created_at_for_compliance.sql
--
-- Team-dashboard audit finding C8 (risk 8/10): the KPC "Time Registration
-- Compliance" chart scored a work-day compliant via DATEDIFF(work.updated_at,
-- work.registered) <= 7. But `work.updated_at` is
--   `timestamp ... ON UPDATE current_timestamp()`
-- so it is rewritten by ANY later UPDATE of the row — most damagingly by
-- WorkService.registerAsPaidout (fired from InvoiceBookedPayoutObserver when an
-- invoice books), which stamps `paid_out = now` on every work row of the booked
-- contract/project/month 1-2 months after the work date. `updated_at` is
-- therefore "last modified", not "first registered", so invoiced work looks
-- registered weeks late and diligent consultants render red.
--
-- Measured on prod (2026-07-02): for un-invoiced rows `updated_at` still reflects
-- registration (avg 2.2 days lag, 93.8% within 7 days on Team ACE); for
-- paid-out rows it is clobbered (avg 36.4 days, 15.8% within 7 days).
--
-- Fix: add an IMMUTABLE creation timestamp that no UPDATE ever rewrites, and
-- measure compliance from it (see TeamDashboardService.getConsultantCompliance).
-- `created_at` is DB-managed: DEFAULT current_timestamp() with NO `ON UPDATE`
-- clause, so it is set once at INSERT and never changes on payout/edit. The Work
-- entity maps it read-only (insertable=false, updatable=false) so the app never
-- writes it — the DB default is authoritative.
--
-- Additive column → safe during an ECS Express canary rollout: the old task-def
-- never SELECTs or writes the new column (it is not in its entity), so it keeps
-- running unaffected while the new task-def uses it.
--
-- Verify on staging before promoting to production.

-- 1. Add the column (nullable; all existing rows become NULL). Appended at the
--    end of the table so MariaDB 10.11 applies it INSTANT — no rewrite, no lock.
ALTER TABLE work
    ADD COLUMN created_at TIMESTAMP NULL DEFAULT NULL
        COMMENT 'Immutable row-creation timestamp for time-registration compliance. DB-managed (DEFAULT current_timestamp, NO on-update). NULL = pre-V387 row whose true creation time is unrecoverable (updated_at was clobbered by payout/edit).';

-- 2. Best-effort seed for rows the payout/edit path never clobbered
--    (paid_out IS NULL): for these, `updated_at` still equals the original
--    registration time (validated above), so it is a faithful created_at.
--    Rows with paid_out set are intentionally LEFT NULL — their true creation
--    time is gone and the metric excludes them (never counts them as late).
--    Bounded to the last 15 months: older un-paid rows' updated_at is unreliable
--    (bulk re-saves / historical migrations) and they are never in the chart's
--    6-complete-month window anyway.
--    `updated_at = updated_at` is assigned explicitly so the ON UPDATE trigger is
--    suppressed and this seed does NOT disturb the existing updated_at values.
UPDATE work
   SET created_at = updated_at,
       updated_at = updated_at
 WHERE paid_out IS NULL
   AND registered >= (CURDATE() - INTERVAL 15 MONTH);

-- 3. Make the DB stamp created_at on every future INSERT. No ON UPDATE clause,
--    so it stays immutable for the life of the row. Existing NULLs are untouched
--    (a DEFAULT only applies to inserts that omit the column).
ALTER TABLE work
    MODIFY COLUMN created_at TIMESTAMP NULL DEFAULT current_timestamp()
        COMMENT 'Immutable row-creation timestamp for time-registration compliance. DB-managed (DEFAULT current_timestamp, NO on-update). NULL = pre-V387 row whose true creation time is unrecoverable (updated_at was clobbered by payout/edit).';
