-- ============================================================================
-- V246: Create API client credential tables
-- ============================================================================
-- Purpose: Introduces a client credentials system for machine-to-machine
--          authentication. Three tables: client registry, scope assignments,
--          and an immutable audit log. Replaces the single static JWT with
--          per-client short-lived tokens while maintaining full backward
--          compatibility with existing @RolesAllowed endpoints.
--
-- Spec reference: docs/specs/api-auth-spec.md, Sections 3.1–3.3
--
-- Changes:
--   1. Creates `api_clients` table (client registry, soft-delete pattern)
--   2. Creates `api_client_scopes` table (scope/role assignments per client)
--   3. Creates `api_client_audit_log` table (immutable event trail)
--   4. Adds indexes for common query patterns
--
-- Backwards compatibility:
--   - No existing tables are modified
--   - No existing data is affected
--   - New tables only; purely additive migration
--
-- Rollback strategy:
--   DROP TABLE IF EXISTS api_client_audit_log;
--   DROP TABLE IF EXISTS api_client_scopes;
--   DROP TABLE IF EXISTS api_clients;
--
-- Impact:
--   - New Quarkus entities needed: ApiClient.java, ApiClientScope.java,
--     ApiClientAuditLog.java
--   - New repository: ApiClientRepository.java
--   - New REST resources: TokenResource.java, ClientManagementResource.java
--
-- Author: Claude Code
-- Date: 2026-03-13
-- ============================================================================

-- Step 1: Create the api_clients table (client registry)
-- Uses CHAR(36) UUID primary key consistent with existing tables.
-- Soft-delete via deleted_at preserves FK integrity with audit log.
CREATE TABLE api_clients (
    uuid                CHAR(36)     NOT NULL,
    client_id           VARCHAR(100) NOT NULL,
    client_secret_hash  VARCHAR(255) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         TEXT         NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    token_ttl_seconds   INT          NOT NULL DEFAULT 3600,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by          VARCHAR(100) NOT NULL,
    deleted_at          TIMESTAMP    NULL     DEFAULT NULL,
    PRIMARY KEY (uuid),
    CONSTRAINT uq_api_clients_client_id UNIQUE (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Step 2: Create the api_client_scopes table (scope assignments)
-- Stores both new-style scopes (e.g. invoices:read) and legacy roles
-- (e.g. SYSTEM, PARTNER) during the transition period (Phases 1-2).
CREATE TABLE api_client_scopes (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    client_uuid  CHAR(36)     NOT NULL,
    scope        VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_api_client_scopes_client_scope UNIQUE (client_uuid, scope),
    CONSTRAINT fk_api_client_scopes_api_clients
        FOREIGN KEY (client_uuid) REFERENCES api_clients (uuid)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Step 3: Create the api_client_audit_log table (immutable event trail)
-- Append-only table. Application layer must never UPDATE or DELETE rows.
-- Event types: CLIENT_CREATED, CLIENT_UPDATED, CLIENT_DISABLED,
--   CLIENT_ENABLED, CLIENT_DELETED, SECRET_ROTATED, TOKEN_ISSUED,
--   TOKEN_DENIED, TOKEN_REVOKED
CREATE TABLE api_client_audit_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    client_uuid  CHAR(36)     NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    ip_address   VARCHAR(45)  NULL,
    details      TEXT         NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_api_client_audit_log_api_clients
        FOREIGN KEY (client_uuid) REFERENCES api_clients (uuid)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Step 4: Add index for efficient audit queries (by client + time range)
CREATE INDEX idx_api_client_audit_log_client_created
    ON api_client_audit_log (client_uuid, created_at);
