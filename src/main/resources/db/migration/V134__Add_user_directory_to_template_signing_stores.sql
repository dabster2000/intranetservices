-- ===================================================================
-- Migration: V134__Add_user_directory_to_template_signing_stores.sql
-- Description: Add user_directory flag to enable user-specific subdirectories
--              for signed document uploads
-- Author: Claude Code
-- Date: 2025-12-09
-- ===================================================================
--
-- Purpose:
-- Enables signing stores to automatically organize uploaded signed documents
-- into user-specific subdirectories based on the User.username field.
--
-- Business Logic:
--   - When user_directory = TRUE, append username as subdirectory
--   - Example: folder_path="Contracts/2025" + user_directory=TRUE + username="hans.lassen"
--     → Upload to "Contracts/2025/hans.lassen/"
--   - Default FALSE preserves existing behavior (no subdirectory)
--
-- Integration Points:
--   - NextSignStatusSyncBatchlet reads this flag during upload
--   - Uses SigningCase.userUuid to lookup User.username
--   - Constructs path: store.folderPath + "/" + user.username (if flag enabled)
--
-- ===================================================================

ALTER TABLE template_signing_stores
    ADD COLUMN user_directory BOOLEAN DEFAULT FALSE NOT NULL
        COMMENT 'When true, append username as subdirectory when uploading signed documents';

-- Add index for queries filtering by this flag
CREATE INDEX idx_template_signing_stores_user_directory
    ON template_signing_stores(user_directory);

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- Backward Compatibility:
-- - Existing records default to FALSE (no subdirectory)
-- - Existing uploads continue working without changes
-- - UI must be updated to expose this configuration
--
-- Example Use Cases:
-- 1. Employee contracts: Each employee gets their own subfolder
-- 2. Client agreements: Organize by client relationship owner
-- 3. Shared templates: Keep central folder (user_directory=FALSE)
--
-- ===================================================================
