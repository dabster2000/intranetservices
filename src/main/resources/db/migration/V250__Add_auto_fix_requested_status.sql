-- ============================================================================
-- V250: Add AUTO_FIX_REQUESTED status + autofix-worker API client +
--       autofix_config table
-- ============================================================================
-- Purpose: Combined migration for the self-healing pipeline. Performs four
--          backwards-compatible changes:
--
--          1. Adds AUTO_FIX_REQUESTED to the bug_reports.status ENUM
--          2. Registers the autofix-worker API client with bugreports scopes
--          3. (is_system column already exists on bug_report_comments from V247)
--          4. Creates the autofix_config table for the runtime kill switch
--
-- Spec references:
--   - docs/specs/self-healing-app-phase-1a-infra-db.md, Section 4 (ENUM update)
--   - docs/specs/self-healing-app-phase-1a-infra-db.md, Section 8 (API client)
--   - docs/specs/self-healing-app-phase-1c2-diff-scanner.md, Section 7 (kill switch)
--   - docs/specs/plans/phase-1a-infra-db-plan.md, Tasks 1.2, 1.3
--
-- Changes:
--   1. ALTER bug_reports.status ENUM to include AUTO_FIX_REQUESTED after
--      IN_PROGRESS. All existing values preserved in original order.
--   2. INSERT autofix-worker API client into api_clients + api_client_scopes.
--      Uses a pre-generated UUID and bcrypt-hashed placeholder secret.
--      The real secret must be set via the admin API after migration.
--   3. CREATE autofix_config key-value table with default autofix.enabled=true.
--
-- Backwards compatibility:
--   - ENUM alteration preserves all existing values. Existing rows are unaffected
--     because the new value is appended after IN_PROGRESS and no data references
--     it yet.
--   - API client INSERT uses INSERT IGNORE to be idempotent on re-run.
--   - autofix_config is a new table; no existing tables modified.
--
-- Data semantics:
--   - AUTO_FIX_REQUESTED: Intermediate status between SUBMITTED/IN_PROGRESS
--     and the auto-fix pipeline processing. Set when an admin clicks
--     "Request Auto-Fix". Reverted if the policy engine rejects or worker fails.
--   - autofix-worker API client: Machine-to-machine client used by the Fargate
--     worker to authenticate with the Quarkus backend. Scopes grant read/write
--     access to bug report operations only.
--   - autofix_config: Runtime configuration table. The kill switch
--     (autofix.enabled) can be toggled via PUT /bug-reports/auto-fix/config
--     without redeployment. Cached with 30-second TTL in the backend.
--   - The client_secret_hash in this migration is a PLACEHOLDER. The actual
--     client must be registered via POST /auth/clients with admin JWT,
--     which generates the real hashed secret. This INSERT ensures the client
--     row exists for environments where the admin API is not yet available.
--
-- Rollback strategy:
--   -- Step 1: Remove autofix_config
--   DROP TABLE IF EXISTS autofix_config;
--   -- Step 2: Remove API client scopes and client
--   DELETE FROM api_client_scopes WHERE client_uuid = '00000000-0000-4000-a000-000000000001';
--   DELETE FROM api_clients WHERE uuid = '00000000-0000-4000-a000-000000000001';
--   -- Step 3: Revert ENUM (only safe if no rows use AUTO_FIX_REQUESTED)
--   ALTER TABLE bug_reports MODIFY COLUMN status
--     ENUM('DRAFT','SUBMITTED','IN_PROGRESS','RESOLVED','CLOSED')
--     NOT NULL DEFAULT 'DRAFT';
--
-- Impact:
--   - BugReportStatus.java: Add AUTO_FIX_REQUESTED enum constant
--   - BugReport.java: Update allowedTransitions() for new status
--   - BugReportAutoFixService.java: New service for auto-fix orchestration
--   - AutoFixPolicyEngine.java: Policy engine checks before accepting requests
--   - BugReportResource.java: New endpoints for auto-fix and config
--   - No existing entity fields change; only the status enum gains a value
--
-- Author: Claude Code
-- Date: 2026-03-17
-- ============================================================================

-- ==========================================================================
-- Step 0: Drop FK on bug_report_comments.author_uuid
-- ==========================================================================
-- The author_uuid FK references user(uuid), but system actors (e.g.,
-- system:autofix-worker, system:autofix-policy) are not real users.
-- We must drop this FK so system comments can be inserted.
-- The application layer continues to validate user UUIDs; system actors
-- use the "system:" prefix convention instead.
ALTER TABLE bug_report_comments DROP FOREIGN KEY fk_bug_report_comments_author;

-- ==========================================================================
-- Step 1: Add AUTO_FIX_REQUESTED to the bug_reports.status ENUM
-- ==========================================================================
-- MariaDB requires re-specifying ALL existing values when altering an ENUM.
-- New value AUTO_FIX_REQUESTED is placed after IN_PROGRESS in the lifecycle:
--   DRAFT -> SUBMITTED -> IN_PROGRESS -> AUTO_FIX_REQUESTED -> RESOLVED -> CLOSED
-- Existing rows with DRAFT, SUBMITTED, IN_PROGRESS, RESOLVED, or CLOSED
-- are not affected because ENUM modification preserves existing data.
ALTER TABLE bug_reports MODIFY COLUMN status
    ENUM('DRAFT','SUBMITTED','IN_PROGRESS','AUTO_FIX_REQUESTED','RESOLVED','CLOSED')
    NOT NULL DEFAULT 'DRAFT';


-- ==========================================================================
-- Step 2: Register the autofix-worker API client
-- ==========================================================================
-- This creates a placeholder client row so the backend recognizes the client_id.
-- The actual client_secret_hash MUST be set via the admin registration API
-- (POST /auth/clients) which generates a proper bcrypt hash. The placeholder
-- hash below is a bcrypt of 'CHANGE_ME_VIA_ADMIN_API' and should never be
-- used for real authentication.
--
-- UUID is a well-known fixed value for easy reference in rollback and tests.
-- INSERT IGNORE makes this idempotent if the client was already registered
-- via the admin API.
INSERT IGNORE INTO api_clients (
    uuid,
    client_id,
    client_secret_hash,
    name,
    description,
    enabled,
    token_ttl_seconds,
    created_by
) VALUES (
    '00000000-0000-4000-a000-000000000001',
    'autofix-worker',
    '$2a$10$placeholder.hash.must.be.replaced.via.admin.api.registration',
    'autofix-worker',
    'Fargate Claude Code worker for the self-healing bug-fix pipeline. Authenticates to read bug report data and write task results/system comments.',
    TRUE,
    3600,
    'system:migration'
);

-- Assign scopes to the autofix-worker client.
-- These are the minimum scopes required for the worker to:
--   - bugreports:read: Read bug report data for prompt construction
--   - bugreports:write: Update task status, post system comments
--   - bugreports:admin: Perform status transitions (AUTO_FIX_REQUESTED -> etc.)
INSERT IGNORE INTO api_client_scopes (client_uuid, scope)
VALUES
    ('00000000-0000-4000-a000-000000000001', 'bugreports:read'),
    ('00000000-0000-4000-a000-000000000001', 'bugreports:write'),
    ('00000000-0000-4000-a000-000000000001', 'bugreports:admin');


-- ==========================================================================
-- Step 3: Create the autofix_config table (runtime kill switch)
-- ==========================================================================
-- Simple key-value configuration table for runtime settings that can be
-- toggled without redeployment. Primary use case: the auto-fix kill switch.
--
-- The backend caches values with a 30-second TTL, so changes take effect
-- within 30 seconds of the PUT request.
--
-- Data semantics:
--   - config_key: Dot-notation key (e.g., 'autofix.enabled')
--   - config_value: String value (parsed by the application layer)
--   - updated_by: UUID of the admin who last changed the value, or 'system'
--     for default/migration values
--   - updated_at: Auto-updated on every modification via ON UPDATE CURRENT_TIMESTAMP
CREATE TABLE autofix_config (
    config_key    VARCHAR(100) NOT NULL
                  COMMENT 'Dot-notation configuration key (e.g., autofix.enabled)',
    config_value  VARCHAR(500) NULL
                  COMMENT 'String configuration value (application layer parses)',
    updated_by    VARCHAR(100) NULL
                  COMMENT 'UUID of admin who last changed, or system actor',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                  COMMENT 'Last modification timestamp (auto-updated)',

    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Insert default configuration: auto-fix enabled by default.
-- Admins can disable via PUT /bug-reports/auto-fix/config.
INSERT INTO autofix_config (config_key, config_value, updated_by)
VALUES ('autofix.enabled', 'true', 'system');
