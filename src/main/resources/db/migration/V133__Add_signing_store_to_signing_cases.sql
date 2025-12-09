-- ===================================================================
-- Migration: V133__Add_signing_store_to_signing_cases.sql
-- Description: Add columns to signing_cases for SharePoint auto-upload tracking
-- Author: Claude Code
-- Date: 2025-12-08
-- ===================================================================
--
-- Purpose:
-- Extends signing_cases table to track SharePoint upload status and errors
-- when auto-uploading signed documents to configured signing stores.
--
-- Business Logic:
--   - signing_store_uuid references the store configuration at time of case creation
--   - sharepoint_upload_status tracks the upload workflow state
--   - sharepoint_upload_error stores detailed error messages for troubleshooting
--   - sharepoint_file_url provides direct link to uploaded document
--
-- Upload Status Values:
--   - NULL: No auto-upload configured (signing_store_uuid is null)
--   - PENDING: Upload queued, awaiting document completion
--   - UPLOADED: Successfully uploaded to SharePoint
--   - FAILED: Upload failed (see sharepoint_upload_error for details)
--
-- Related Tables:
--   - template_signing_stores (V132): Source of signing store configuration
--   - signing_cases (V130, V131): Table being extended
--
-- ===================================================================

-- Add SharePoint upload tracking columns
ALTER TABLE signing_cases

    -- Reference to signing store configuration (soft reference, not FK)
    -- We don't use FK because:
    -- 1. Store might be deleted after case creation
    -- 2. We want to preserve upload config even if store is modified
    ADD COLUMN signing_store_uuid VARCHAR(36) NULL
        COMMENT 'Reference to signing store for auto-upload (copied at case creation)'
        AFTER status_fetch_error,

    -- Upload workflow status
    ADD COLUMN sharepoint_upload_status VARCHAR(50) NULL
        COMMENT 'Upload status: PENDING, UPLOADED, FAILED (NULL = no auto-upload)'
        AFTER signing_store_uuid,

    -- Error tracking for failed uploads
    ADD COLUMN sharepoint_upload_error TEXT NULL
        COMMENT 'Detailed error message if upload failed'
        AFTER sharepoint_upload_status,

    -- Direct link to uploaded file
    ADD COLUMN sharepoint_file_url VARCHAR(1000) NULL
        COMMENT 'SharePoint URL of successfully uploaded file'
        AFTER sharepoint_upload_error;

-- Index for finding cases pending upload (batch job optimization)
CREATE INDEX idx_sc_upload_status ON signing_cases(sharepoint_upload_status);

-- Index for finding cases by signing store (admin reporting)
CREATE INDEX idx_sc_signing_store ON signing_cases(signing_store_uuid);

-- ===================================================================
-- Migration Notes
-- ===================================================================
--
-- Auto-Upload Workflow:
-- 1. Case created with signing_store_uuid from template config
-- 2. sharepoint_upload_status set to 'PENDING'
-- 3. Document signed and completed in NextSign
-- 4. Batch job detects completion, triggers SharePoint upload
-- 5. On success: status = 'UPLOADED', sharepoint_file_url populated
-- 6. On failure: status = 'FAILED', sharepoint_upload_error populated
-- 7. Failed uploads can be retried manually from admin UI
--
-- Why No Foreign Key to template_signing_stores?
-- - Signing store config should be "frozen" at case creation time
-- - Admin might modify or delete store after cases are created
-- - Upload should still work with original config captured elsewhere
-- - For full config preservation, consider storing JSON snapshot
--
-- Future Enhancements:
-- - Add sharepoint_upload_attempts counter for retry tracking
-- - Add sharepoint_uploaded_at timestamp
-- - Store signing store config snapshot as JSON for audit
--
-- ===================================================================
