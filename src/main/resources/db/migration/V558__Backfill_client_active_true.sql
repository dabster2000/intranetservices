-- Remove the client activate/deactivate feature (release 1 of 2).
--
-- The application no longer reads or writes `client.active` as of this release.
-- Backfill every row to 1 so the retired distinction has no lingering effect on
-- anything still reading the column directly (reporting, ad-hoc queries) during
-- the window before the column itself is dropped.
--
-- The column is deliberately NOT dropped here: an ECS Express canary runs the old
-- and new tasks side by side against the same database, and the old task's
-- Hibernate SELECT still names `active`. Dropping it now would 500 live requests
-- for the ~30s cutover window. The DROP ships as a separate follow-up migration
-- once this release is confirmed live in production.

UPDATE client SET active = 1 WHERE active <> 1;
