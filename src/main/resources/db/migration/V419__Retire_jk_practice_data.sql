-- ====================================================================
-- V419: JK retirement (Part 1, data step).
--
-- JK was a team (the Junior Team) masquerading as a practice code.
-- Junior consultants become no-practice people (stored sentinel UD) in
-- the Junior Team, which gets standard team reporting like every other
-- team. Budget-neutral: it_budget moved to team level in V418 with the
-- Junior Team seeded 0.
--
-- Spec: docs/superpowers/specs/2026-07-19-practice-data-model-design.md §3.2
--
-- Trigger interplay (safe in either order relative to the practice-
-- engine teardown migration):
--   * Statement 1 fires the V407 per-row AFTER UPDATE triggers on
--     `user`, which close each open JK history interval and insert a
--     new open UD row — correct effective-dated transitions.
--   * Statement 2 then corrects the residual JK rows (the sliver the
--     triggers just closed, plus rows of terminated JK users). History
--     coverage only began 2026-07-14, so this is a data correction of
--     wrong data, not a restatement of meaningful history.
--   * If the V411 watermark triggers on user_practice_history still
--     exist when this runs, the procedures they call also still exist;
--     after the engine teardown both are gone. Either way statements
--     1-2 execute cleanly.
--
-- All statements are idempotent (re-running matches zero rows).
-- Rollback: forward-only by design; JK-as-practice is retired data.
-- ====================================================================

-- 1) Junior users become no-practice people (V407 triggers fire per row).
UPDATE `user` SET practice = 'UD' WHERE practice = 'JK';

-- 2) Correct the remaining JK history rows (closed sliver + terminated users).
UPDATE user_practice_history SET practice = 'UD' WHERE practice = 'JK';

-- 3) Remove the junior tag from sales leads (spec decision 6) - all rows,
--    open and historical; no replacement field.
UPDATE sales_lead SET practice = 'UD' WHERE practice = 'JK';

-- 4) Retire practice_settings data (it_budget lives in team_settings since
--    V418; the resource/service/entity were removed in the same release).
--    The empty table itself is dropped in Part 2.
DELETE FROM practice_settings;

-- 5) Nothing references JK anymore: delete the transitional registry row.
--    (practice_lead and team FKs never pointed at it.)
DELETE FROM practice WHERE code = 'JK';

-- 6) Retire the bespoke JK dashboard page entry (the page and its API are
--    removed in the same release; Junior Team uses the standard team
--    dashboard).
DELETE FROM page_registry WHERE page_key = 'jk-team-dashboard';
