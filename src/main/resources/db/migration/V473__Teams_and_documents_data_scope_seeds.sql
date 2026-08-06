-- V473: delivery & people data-scope seeds (authorization-model-unification Phase 10)
--
-- Owner-decided rules (access-intent §4 "Delivery & people data scope" + Decisions
-- 10–14, 2026-08-06). Everything here is keep-as-today; the seeds either add
-- boolean-invisible sub-ALL rows or close matrix-bookkeeping gaps, never change what
-- any caller can do the day they land.
--
-- What is deliberately ABSENT from this file:
--   * timeregistration:* — Decision 14: client hours are shared data; every grant
--     stays at the column-default ALL. No seed, no narrowing.
--   * teams:read for USER — the phase file sketches "USER @ ALL (structure is not
--     confidential)", but teams:read is a PAGE-AUDIENCE key (team-dasboard and
--     management-team, audience ADMIN+TEAMLEAD since V467/V468). An ALL-scope USER
--     grant is boolean-visible and would open the team dashboard to every employee.
--     Structure reads stay session-gated at the BFF; recorded in findings.
--   * crm:* / contracts:* — Decision 5: no client-ownership scope kind; all ALL.
--   * conference:read — reads stay session-gated; seeding a holder would be a
--     boolean-visible grant with no consumer.
--   * recruitment:* — the recruitment module's own visibility engine is the
--     row-level model there (Decision 12); grants stay exactly as V465/V468 seeded.
--
-- Non-negotiable details (findings F-12, F-13, V470 header):
--   * Idempotent — repair-at-start means a rollback re-runs this file.
--   * ON DUPLICATE KEY UPDATE touches data_scope only, never revoked_at: a seed
--     re-run cannot resurrect a console revoke (tombstone-safe).
--   * role_permission is staging-sync-excluded (V466), so staging-ahead-of-prod
--     survives the nightly refresh.

-- TEAMLEAD → teams:write @ TEAM (Decision 10 — forward intent, boolean-invisible):
-- a team lead may edit their own team's membership; the self-widening property
-- (membership feeds the TEAM resolver) is deliberately accepted. Sub-ALL, so no
-- page, can() gate or legacy boolean changes; the BFF's membership routes stay
-- HR/ADMIN-gated until a UI for leads exists.
INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'TEAM' FROM role_definition rd, permission p
  WHERE rd.name = 'TEAMLEAD' AND p.permission_key = 'teams:write'
  ON DUPLICATE KEY UPDATE data_scope = 'TEAM';

-- USER → documents:read @ OWN (Decision 13): an employee reaches exactly their own
-- employee documents — the audience the BFF already enforces (allowSelf: true,
-- allowTeamLead: false). Narrowing from the implicit ALL is boolean-safe: no page,
-- can() site or requirePermission gate keys on documents:read (verified 2026-08-06).
INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'OWN' FROM role_definition rd, permission p
  WHERE rd.name = 'USER' AND p.permission_key = 'documents:read'
  ON DUPLICATE KEY UPDATE data_scope = 'OWN';

-- HR → documents:write @ ALL (matrix bookkeeping): the BFF upload/delete audience is
-- HR + ADMIN, but only ADMIN holds documents:write in the traced matrix. Without this
-- row, the upload subject check would resolve HR to no reach and 403 the review flow
-- the day actor headers arrive — the Phase 9 ADMIN-expenses hazard, HR-shaped.
INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'ALL' FROM role_definition rd, permission p
  WHERE rd.name = 'HR' AND p.permission_key = 'documents:write'
  ON DUPLICATE KEY UPDATE data_scope = 'ALL';

-- documents:gdpr → ADMIN + DPO @ ALL (matrix bookkeeping): the erase/DSAR endpoints
-- gate on documents:gdpr, which no role holds — a reach check would deny everyone.
-- Mirrors the recruitment:gdpr audience (V465). Boolean-visible for ADMIN/DPO only,
-- and nothing consumes can('documents:gdpr') (verified 2026-08-06).
INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'ALL' FROM role_definition rd, permission p
  WHERE rd.name = 'ADMIN' AND p.permission_key = 'documents:gdpr'
  ON DUPLICATE KEY UPDATE data_scope = 'ALL';

INSERT INTO role_permission (role, permission_key, data_scope)
  SELECT rd.name, p.permission_key, 'ALL' FROM role_definition rd, permission p
  WHERE rd.name = 'DPO' AND p.permission_key = 'documents:gdpr'
  ON DUPLICATE KEY UPDATE data_scope = 'ALL';
