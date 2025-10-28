-- ======================================================================================
-- Flyway Repair Script for V108 Migration
-- ======================================================================================
-- Purpose: Mark the failed V108 migration as successful after replacing the migration file
--
-- Background:
-- V108 originally tried to create database triggers but failed due to insufficient
-- privileges (SUPER required for triggers with binary logging). The migration file
-- has been replaced with a no-op version that documents the decision to use
-- application-layer audit logging instead.
--
-- This script repairs the Flyway schema history to mark V108 as successful.
-- ======================================================================================

-- Step 1: Check current status of V108
SELECT
    installed_rank,
    version,
    description,
    type,
    script,
    checksum,
    installed_on,
    execution_time,
    success
FROM flyway_schema_history
WHERE version = '108';

-- Step 2: Mark V108 as successful
-- This allows Flyway to continue with migrations and application startup
UPDATE flyway_schema_history
SET success = 1,
    execution_time = 0
WHERE version = '108';

-- Step 3: Verify the repair
SELECT
    installed_rank,
    version,
    description,
    type,
    script,
    checksum,
    installed_on,
    execution_time,
    success
FROM flyway_schema_history
WHERE version = '108';

-- Step 4: Check all migration status
SELECT
    version,
    description,
    success,
    installed_on
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;

-- ======================================================================================
-- IMPORTANT NOTES:
-- ======================================================================================
--
-- After running this script:
-- 1. The V108 migration file must be the updated version (no-op with SELECT statement)
-- 2. Restart the Quarkus application - it should now start successfully
-- 3. Future migrations will continue normally
-- 4. Audit logging should be implemented via JPA EntityListeners as documented in V108
--
-- The checksum in flyway_schema_history will be updated automatically on next startup
-- to match the new V108 migration file content.
-- ======================================================================================
