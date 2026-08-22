-- ===================================================================
-- V524: Recruitment — list lengths and the scorecard nudge cap become
--       admin-tunable (no new tables)
-- ===================================================================
-- Domain:  recruitmentservice (app_settings only)
--
-- Why:
--   Four numbers that shaped what people see were compiled constants,
--   so changing a list length meant a deployment — and two of them had
--   silently drifted apart from the settings they were documented to
--   follow. They join the three P17 SLA thresholds (V447) in the admin
--   Settings -> Recruitment "Timing & cadence" card. All are read per
--   request/sweep with a missing or unparseable row falling back to the
--   compiled default, never to 0 and never to "off" (RecruitmentTunables) --
--   so a typo here can shorten a list but can never blank one or
--   silence a reminder loop.
--
--     recruitment.ui.task-rows                  (default 5)
--       Idle-candidate rows the landing "My tasks" card shows before
--       the rest collapse behind "Show N more", AND rows per Slack App
--       Home section. Deliberately ONE key: it is the same question on
--       two screens, and two answers would stop the same person's list
--       in two different places. Replaces
--       SlackAppHomeViews.MAX_ROWS_PER_SECTION.
--
--     recruitment.ui.activity-rows              (default 15)
--       Landing activity-feed rows. The raw pre-filter fetch is derived
--       as 4x this (visibility filtering drops rows after the read),
--       reproducing the previous fixed 15 -> 60 ratio.
--
--     recruitment.ui.upcoming-interview-rows    (default 5)
--       The viewer's own upcoming interviews on the landing page.
--
--     recruitment.sla.max-scorecard-nudges      (default 2)
--       Hard cap of scorecard DMs per interviewer per interview
--       (spec 8.4). A stop, not a cadence: past it the sweep goes quiet
--       for that pair forever, so the reminder never becomes the thing
--       people mute.
--
--   No behaviour changes on deploy: every seeded value equals the
--   constant it replaces.
--
-- Related, and NOT seeded here because it already exists:
--   RecruitmentBoardService's idle chip used a hard-coded 7 while
--   recruitment.sla.candidate-idle-days was 4 in production, so the
--   board called a candidate idle three days later than the landing
--   page and Slack did -- while the settings page told administrators
--   the one setting drove all three. The board now reads that setting.
--   Visible effect on deploy: board idle chips appear at the configured
--   threshold instead of 7 days.
--
-- Idempotency: INSERT IGNORE only; raw re-run safe. Admin-tuned values
--   survive re-runs.
--
-- Author: Claude Code
-- Date:   2026-08-22
-- Rollback: inert without the matching backend image. Full removal:
--     DELETE FROM app_settings WHERE setting_key LIKE 'recruitment.ui.%'
--        OR setting_key = 'recruitment.sla.max-scorecard-nudges';
-- ===================================================================

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.ui.task-rows',                '5',  'recruitment'),
    ('recruitment.ui.activity-rows',            '15', 'recruitment'),
    ('recruitment.ui.upcoming-interview-rows',  '5',  'recruitment'),
    ('recruitment.sla.max-scorecard-nudges',    '2',  'recruitment');
