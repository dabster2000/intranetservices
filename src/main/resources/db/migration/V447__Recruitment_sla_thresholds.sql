-- ===================================================================
-- V447: Recruitment ATS expansion — Phase 17: SLA nudges & landing
-- ===================================================================
-- Domain:  recruitmentservice (SLA automation — no new tables)
--
-- What:
--   Seeds the three SLA thresholds the P17 sweep reads from
--   app_settings (plan §P17 "Thresholds in app_settings"). Read per
--   sweep via AppSettingService (the RecruitmentFeatureFlag idiom —
--   no cache, missing row = built-in default), so an admin can tune
--   cadence without a redeploy:
--
--     recruitment.sla.scorecard-overdue-hours  (default 24)
--       An assigned interviewer whose round interview has passed and
--       whose own scorecard is still missing gets a Slack DM after
--       this many hours. Max 2 nudges per interviewer per interview
--       (hard cap in code, spec §8.4), never after submission.
--
--     recruitment.sla.candidate-idle-days      (default 7)
--       An open application sitting in the same stage this many days
--       pings the position's owner (hiring owner → circle owners →
--       current team leads). Re-pings at most every threshold period
--       while still idle.
--
--     recruitment.sla.debrief-stalled-hours    (default 48)
--       All scorecards in, no stage move/terminal yet: the decision
--       owner is pinged after this many hours, re-pinged at most
--       every threshold period.
--
--   INSERT IGNORE — admin-tuned values survive re-runs (the V433
--   flag-seed convention). All SLA side effects are gated by
--   recruitment.interviews.enabled (spec §11 places the nudges with
--   the interview loop); the thresholds are inert while it is off.
--
--   Deliberately NOT touched: page_registry. The /recruitment row's
--   sidebar audience is admin-managed at runtime (the rows were
--   manually re-sectioned in production on 2026-07-23); the landing
--   page itself is reachable by URL for every authenticated employee
--   and shapes/redirects per role server-side.
--
-- Idempotency: INSERT IGNORE only; raw re-run safe.
--
-- Author: Claude Code
-- Date:   2026-07-23
-- Rollback: inert without the P17 backend image. Full removal:
--     DELETE FROM app_settings WHERE setting_key LIKE 'recruitment.sla.%';
-- ===================================================================

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.sla.scorecard-overdue-hours', '24', 'recruitment'),
    ('recruitment.sla.candidate-idle-days',      '7', 'recruitment'),
    ('recruitment.sla.debrief-stalled-hours',   '48', 'recruitment');
