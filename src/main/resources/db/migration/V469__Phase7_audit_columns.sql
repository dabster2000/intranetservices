-- V469: Row-level audit columns for the authorization tables (Phase 7, task 7.1)
--
-- Neither `roles` nor `page_registry` records who last changed a row; `role_permission`
-- records the granter but not subsequent modification. This migration adds the standard
-- AuditEntityListener columns so the Role, PageRegistry and RolePermission entities can
-- implement Auditable. The full change history (before/after, per mutation) lives in
-- `authz_audit` (V463), wired by Phase 7 task 7.2 — these columns are the cheap
-- "who touched this row last" view, not the audit trail itself.
--
-- Non-negotiables:
--   * Additive and idempotent (F-13: repair-at-start re-runs migrations after rollback).
--   * All columns NULLable — existing rows predate auditing and must stay valid.
--   * `roles` and `page_registry` are NOT excluded from sp_sync_prod_to_staging, so this
--     migration MUST be in production before the next 02:00 staging sync after it lands
--     on staging, or the sync strips the columns and every entity read on staging errors
--     (the V6.10 failure mode, findings 2026-08-06). Promotion the same day is the plan.
--
-- Column length 64: AuditEntityListener writes a user UUID (36), "system" or "anonymous";
-- 64 leaves headroom without inviting free text.

ALTER TABLE roles
  ADD COLUMN IF NOT EXISTS created_at DATETIME NULL,
  ADD COLUMN IF NOT EXISTS created_by VARCHAR(64) NULL,
  ADD COLUMN IF NOT EXISTS updated_at DATETIME NULL,
  ADD COLUMN IF NOT EXISTS modified_by VARCHAR(64) NULL;

-- page_registry already has created_at / updated_at (DB-managed defaults); only the
-- actor columns are missing.
ALTER TABLE page_registry
  ADD COLUMN IF NOT EXISTS created_by VARCHAR(64) NULL,
  ADD COLUMN IF NOT EXISTS modified_by VARCHAR(64) NULL;

ALTER TABLE role_permission
  ADD COLUMN IF NOT EXISTS created_at DATETIME NULL,
  ADD COLUMN IF NOT EXISTS created_by VARCHAR(64) NULL,
  ADD COLUMN IF NOT EXISTS updated_at DATETIME NULL,
  ADD COLUMN IF NOT EXISTS modified_by VARCHAR(64) NULL;
