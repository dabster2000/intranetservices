-- V373: Auto-fix worker tunable configuration (model, effort, turns, budget).
--
-- Adds tunable keys to autofix_config. BugReportAutoFixService.buildMetadataJson()
-- reads these into each task's metadata JSON; the Fargate worker (worker.py) then
-- applies them to the `claude` invocation. The values seeded here are the current
-- defaults — admins change them via PUT /bug-reports/auto-fix/config (Settings UI).
-- See docs/finalized/infrastructure/auto-fix-pipeline.md §3.6.
--
-- INSERT IGNORE: never clobber a value an admin already set (and safe to re-run
-- under the nightly staging refresh).

INSERT IGNORE INTO autofix_config (config_key, config_value, updated_by) VALUES
    ('autofix.model',          'claude-opus-4-8', 'system:migration'),
    ('autofix.effort',         'xhigh',           'system:migration'),
    ('autofix.max_turns',      '150',             'system:migration'),
    ('autofix.max_budget_usd', '10.00',           'system:migration');
