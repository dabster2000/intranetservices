-- V470: role_permission.data_scope (authorization-model-unification Phase 8, task 8.1)
--
-- Adds the row-level dimension to a grant: WHICH records a permission reaches, not just
-- whether the action is allowed. One permission, scoped grants — never scope-suffixed
-- permission keys (the phase design forbids salaries:read:own-style multiplication).
--
-- The plan file numbers this V463; V463–V469 were consumed by Phases 4–7, so it ships
-- as V470 (recorded in findings.md).
--
-- DEFAULT 'ALL' means every existing grant is byte-for-byte unchanged on deploy: before
-- this migration, holding a permission always implied unbounded reach. The column is
-- unread until the Phase 8 resolver ships, and legacy boolean consumers are satisfied
-- only by ALL-scope grants (owner decision 2026-08-06), so sub-ALL grants seeded here
-- are invisible to every pre-Phase-8 gate.
--
-- Non-negotiable details (findings F-12, F-13):
--   * IF NOT EXISTS / idempotent everywhere — `repair-at-start: true` means a rollback
--     deletes the Flyway history row and a re-deploy re-runs this file.
--   * Never resurrect a tombstoned row: the seeds' ON DUPLICATE KEY UPDATE touches
--     data_scope only, never revoked_at.
--   * salaries:* is in the ProtectedPermissions set: its bindings AND scopes are
--     console-immutable (the Phase 8 scope rail extends 7.9), so these seeded scopes
--     can only change through a reviewed migration — a seed re-run cannot fight a
--     console edit.

ALTER TABLE role_permission
  ADD COLUMN IF NOT EXISTS data_scope
    ENUM('OWN','TEAM','PRACTICE','COMPANY','ALL') NOT NULL DEFAULT 'ALL';

-- Worked example seeds (phase 8 design table). ADMIN and HR keep their existing
-- salaries:read grants at the column default ALL — deliberately not touched here.
--
-- USER → salaries:read @ OWN: every employee may read their own salary (the profile
-- Paycheck tab today reaches this via the BFF guard's allowSelf branch; this makes the
-- policy data). Invisible to boolean gates (see header), so /salary-payment stays HR/ADMIN.
INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'OWN' FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'salaries:read'
  ON DUPLICATE KEY UPDATE data_scope = 'OWN';

-- TEAMLEAD → salaries:read @ TEAM: reach is resolved from the live team relationship
-- (active LEADER/SPONSOR as of the check), never from the role alone — a TEAMLEAD role
-- holder with no active led team resolves to nothing beyond OWN.
INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'TEAM' FROM role_definition rd, permission p
  WHERE rd.name = 'TEAMLEAD' AND p.permission_key = 'salaries:read'
  ON DUPLICATE KEY UPDATE data_scope = 'TEAM';
