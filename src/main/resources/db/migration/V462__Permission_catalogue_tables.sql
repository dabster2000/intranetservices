-- V462: Permission catalogue tables (authorization-model-unification Phase 4, task 4.3)
--
-- Establishes the single catalogue of permissions (`permission`) and the table binding
-- roles to them (`role_permission`). DORMANT when this ships: nothing reads these tables
-- until Phase 5. Seeds arrive in V464 (permission, generated from Permissions.java) and
-- V465 (role_permission, derived from the live frontend gate values).
--
-- Non-negotiable details (findings F-12, F-13):
--   * `permission_key`, never `key` — `key` is a MariaDB reserved word.
--   * Explicit per-column COLLATE — `role_definition` runs utf8mb4_general_ci in
--     production, but at least one table (`page_registry`) has a declared/runtime
--     collation divergence, so never rely on a table default for an FK column.
--   * IF NOT EXISTS everywhere — `repair-at-start: true` means a rollback deletes the
--     Flyway history row and a re-deploy re-runs this file.
--   * Revocation is a `revoked_at` tombstone, never a DELETE — a deleted row would be
--     resurrected by a seed re-run.
--
-- Role deletion policy (owner decision 2026-08-06): the FK is ON DELETE RESTRICT and the
-- service layer refuses to delete a role definition that still has permission bindings,
-- with a clear message. Bindings must be revoked (tombstoned) and hard-removed
-- deliberately before a role can be deleted.

CREATE TABLE IF NOT EXISTS permission (
  permission_key      VARCHAR(64) COLLATE utf8mb4_general_ci NOT NULL,
  display_name        VARCHAR(128) NOT NULL,
  description         TEXT,
  category            VARCHAR(64),
  origin              ENUM('CODE','UI') NOT NULL DEFAULT 'CODE',
  state               ENUM('ACTIVE','STALE') NOT NULL DEFAULT 'ACTIVE',
  enforce_acting_user BOOLEAN NOT NULL DEFAULT FALSE,
  revoked_at          TIMESTAMP NULL,
  PRIMARY KEY (permission_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS role_permission (
  role           VARCHAR(50) COLLATE utf8mb4_general_ci NOT NULL,
  permission_key VARCHAR(64) COLLATE utf8mb4_general_ci NOT NULL,
  granted_by     VARCHAR(36),
  granted_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at     TIMESTAMP NULL,
  PRIMARY KEY (role, permission_key),
  CONSTRAINT fk_role_permission_role
    FOREIGN KEY (role) REFERENCES role_definition(name)
    ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_role_permission_permission
    FOREIGN KEY (permission_key) REFERENCES permission(permission_key)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
