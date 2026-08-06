-- V463: authz_version + authz_audit (authorization-model-unification Phase 4, task 4.4)
--
-- `authz_version` is the cross-task cache-invalidation signal for
-- EffectivePermissionService: every authorization write bumps it in the same
-- transaction; each Fargate task polls it at most once per second and flushes its
-- local Caffeine cache on change. Worst-case staleness ~1s, no Redis.
--
-- `authz_audit` records authorization mutations (who changed what, before/after).
-- Phase 4 only creates it; Phase 7 wires the admin console writes.
--
-- Idempotent by construction (F-13): IF NOT EXISTS, and the version seed uses
-- ON DUPLICATE KEY UPDATE version = version so a re-run never resets a live counter.
--
-- NOTE: before_json / after_json are JSON columns, but the Hibernate entity maps them
-- as String — @JdbcTypeCode(JSON) has previously crashed boot in this codebase.

CREATE TABLE IF NOT EXISTS authz_version (
  id TINYINT NOT NULL PRIMARY KEY, version BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO authz_version (id, version) VALUES (1, 1)
  ON DUPLICATE KEY UPDATE version = version;

CREATE TABLE IF NOT EXISTS authz_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_uuid VARCHAR(36), action VARCHAR(64) NOT NULL,
  target_type VARCHAR(64) NOT NULL, target_id VARCHAR(128) NOT NULL,
  before_json JSON, after_json JSON,
  at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_authz_audit_at (at), INDEX idx_authz_audit_target (target_type, target_id)
) ENGINE=InnoDB;
