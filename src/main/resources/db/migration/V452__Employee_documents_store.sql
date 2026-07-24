-- ===================================================================
-- V452: Employee Documents — S3-only store (Phase 1a foundation)
-- ===================================================================
-- Feature: Employee Documents S3-only storage
--          (spec docs/superpowers/specs/2026-07-24-employee-documents-s3-only-design.md §6.2)
-- Domain:  documentservice (new employee_documents store + audit trail
--          + settings seeds + settings-tab registry row)
--
-- Purpose:
--   1. employee_documents — metadata for every employee document; bytes
--      live in the dedicated S3 bucket trustworks-employee-documents-{env}
--      under keys users/{userUuid}/{docUuid}-{slug}. Replaces both the
--      weak `files` table (for user-linked documents) and SharePoint as
--      the post-hire document store.
--   2. employee_document_audit — GDPR art. 30 access/processing trail
--      (upload/download/update/delete/erase/DSAR/retention actions).
--   3. Seed the employee_documents.* runtime toggles + preferences (all
--      toggles 'false' — the feature ships dark and is armed from the
--      Settings → Employee Documents tab, spec §6.11).
--   4. Register the "Employee Documents" settings tab in page_registry
--      (ADMIN-only, display_order 160 — after settings-recruitment-ai
--      at 150).
--
-- Design notes:
--   * No FK to `user` on user_uuid — matches the sibling stores
--     (files.relateduuid, signing_cases.user_uuid has FK; but the
--     retention job must be able to erase rows for users regardless of
--     user-table state, and migration rows may precede conversion).
--     Indexed instead.
--   * uq_ed_signing makes signing archival idempotent per
--     (signing_case_key, document_index) — a retried batchlet pass
--     simply skips documents that already have a row.
--   * detail column on the audit table is scrubbed (not deleted) on
--     erasure: the fact of processing is retained, the content PII is
--     not (spec §6.10).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   CREATE TABLE IF NOT EXISTS, INSERT IGNORE for settings seeds
--   (never overwrite admin edits), page_registry seed is
--   INSERT ... ON DUPLICATE KEY UPDATE (registry convention V430/V440).
--
-- Collation: utf8mb4_general_ci (schema default); UUIDs VARCHAR(36).
--
-- Author: Claude Code
-- Date:   2026-07-24
-- Rollback: feature is dark (all toggles false) — inert without the
--   Phase-1 images. Full removal:
--     DROP TABLE IF EXISTS employee_document_audit;
--     DROP TABLE IF EXISTS employee_documents;
--     DELETE FROM app_settings WHERE category = 'employee_documents';
--     DELETE FROM page_registry WHERE page_key = 'settings-employee-documents';
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. The store (spec §6.2 V45a)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS employee_documents (
    uuid              VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_uuid         VARCHAR(36)  NOT NULL,
    s3_key            VARCHAR(512) NOT NULL,
    category          ENUM('CONTRACT','ADDENDUM','SALARY','IDENTITY','DECLARATION',
                           'VACATION','SICKNESS','TERMINATION','OTHER')
                      NOT NULL DEFAULT 'OTHER',
    label             VARCHAR(255) NULL COMMENT 'Free text; migration puts relative folder path here',
    original_filename VARCHAR(500) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    file_size_bytes   BIGINT       NOT NULL,
    sha256            CHAR(64)     NULL COMMENT 'Computed on byte-level writes; null for server-side copies until backfilled',
    source            ENUM('SIGNING','PROMOTION','ONBOARDING','MANUAL_HR','MANUAL_SELF','MIGRATION')
                      NOT NULL,
    signing_case_key  VARCHAR(255) NULL COMMENT 'Linkage to signing_cases.case_key',
    document_index    INT          NULL COMMENT 'Position within a multi-doc signing case (idempotency)',
    hr_only           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Hidden from employee self-view',
    archived          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Replaces SharePoint Arkiv folders',
    needs_review      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Self-uploads pending HR categorization',
    uploaded_by       VARCHAR(36)  NULL COMMENT 'Actor user uuid (null for system writers)',
    migrated_from     VARCHAR(1024) NULL COMMENT 'Provenance: SharePoint webUrl / files:{uuid}',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NULL ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_ed_signing (signing_case_key, document_index),
    KEY idx_ed_user (user_uuid),
    KEY idx_ed_user_cat (user_uuid, category),
    KEY idx_ed_needs_review (needs_review)
);

-- -------------------------------------------------------------------
-- 2. The audit trail (spec §6.2 V45b — GDPR art. 30)
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS employee_document_audit (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_uuid VARCHAR(36) NULL COMMENT 'Null for user-level actions (ERASE_ALL, DSAR_EXPORT, run summaries)',
    user_uuid     VARCHAR(36) NOT NULL COMMENT 'Whose file was touched',
    actor_uuid    VARCHAR(36) NULL COMMENT 'Who did it (null = system/batch)',
    action        ENUM('UPLOAD','DOWNLOAD','UPDATE','ARCHIVE','DELETE',
                       'ERASE_ALL','DSAR_EXPORT','RETENTION_DELETE','MIGRATE') NOT NULL,
    detail        VARCHAR(1024) NULL COMMENT 'Filename/category at time of action; scrubbed on erasure',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_eda_user (user_uuid),
    KEY idx_eda_doc (document_uuid),
    KEY idx_eda_created (created_at)
);

-- -------------------------------------------------------------------
-- 3. Runtime toggles + policy preferences (spec §6.11 — all toggles
--    seeded OFF; numerics at their documented defaults).
-- -------------------------------------------------------------------
INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('employee_documents.ui.hr-tab.enabled',           'false', 'employee_documents'),
    ('employee_documents.ui.self-service.enabled',     'false', 'employee_documents'),
    ('employee_documents.writers.signing.enabled',     'false', 'employee_documents'),
    ('employee_documents.writers.promotion.enabled',   'false', 'employee_documents'),
    ('employee_documents.writers.onboarding.enabled',  'false', 'employee_documents'),
    ('employee_documents.retention.enabled',           'false', 'employee_documents'),
    ('employee_documents.review.slack-notify.enabled', 'false', 'employee_documents'),
    ('employee_documents.retention.years',             '5',     'employee_documents'),
    ('employee_documents.retention.nightly-user-cap',  '10',    'employee_documents'),
    ('employee_documents.upload.max-size-mb',          '25',    'employee_documents');

-- -------------------------------------------------------------------
-- 4. Settings-tab registration (spec §6.11 — ADMIN-only tab).
-- -------------------------------------------------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-employee-documents', 'Employee Documents', 1, '/settings', 'ADMIN', 160, 'SETTINGS', 'FileText', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label     = VALUES(page_label),
    is_visible     = VALUES(is_visible),
    react_route    = VALUES(react_route),
    required_roles = VALUES(required_roles),
    display_order  = VALUES(display_order),
    section        = VALUES(section),
    icon_name      = VALUES(icon_name),
    is_external    = VALUES(is_external),
    external_url   = VALUES(external_url);
