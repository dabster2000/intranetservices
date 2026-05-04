-- ===================================================================
-- V314: Create candidate_dossier_appendices table
-- ===================================================================
-- Feature: Recruitment Dossier
-- Domain:  recruitmentservice
--
-- Purpose:
--   Files uploaded to a dossier (e.g., job-offer letter PDFs, side
--   agreements, IP assignments). Persisted independently of revisions
--   so that the same appendix can appear in multiple revisions; the
--   appendices_snapshot JSON in candidate_dossier_revisions captures
--   which appendices were active at each Send.
--
-- File storage:
--   file_uuid points to the existing S3-backed file storage used by
--   the templates module (see template_documents.file_uuid). This is a
--   logical reference, not a SharePoint path.
--
-- Internal FK (recruitment-only):
--   dossier_uuid -> candidate_dossiers(uuid) ON DELETE RESTRICT
--
-- Path-traversal mitigation:
--   The application layer sanitizes original_filename via Path.getFileName()
--   and rejects '..', leading '/', and control chars before persisting
--   (spec §8.2 / backend AC #17). The schema stores the sanitized form.
--
-- Author: Claude Code
-- Date:   2026-05-04
-- Rollback: DROP TABLE candidate_dossier_appendices;
-- ===================================================================

START TRANSACTION;

CREATE TABLE candidate_dossier_appendices (
    -- Primary key
    uuid VARCHAR(36) PRIMARY KEY
        COMMENT 'Appendix identifier',

    -- Internal FK to dossier
    dossier_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK to candidate_dossiers.uuid',

    -- File metadata (S3-backed; mirrors template_documents pattern)
    file_uuid         VARCHAR(36)  NOT NULL
        COMMENT 'S3 storage key for the appendix file',
    original_filename VARCHAR(500) NOT NULL
        COMMENT 'Sanitized original filename (Path.getFileName + reject .., /, ctrl chars)',
    content_type      VARCHAR(100) NULL
        COMMENT 'MIME type as detected at upload time (e.g., application/pdf)',
    file_size_bytes   BIGINT       NULL
        COMMENT 'File size in bytes; informational',

    -- Display ordering within the dossier
    display_order INT NOT NULL DEFAULT 1
        COMMENT 'Order within the dossier appendix list (1-based, ascending)',

    -- Audit
    uploaded_by_useruuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK to users.uuid (NO DB FK constraint); the user who uploaded the appendix',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP                                  NOT NULL
        COMMENT 'Upload timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP      NOT NULL
        COMMENT 'Last modification timestamp (e.g., display_order changes)',

    -- Internal FK: appendix -> dossier (RESTRICT per spec)
    CONSTRAINT fk_cda_dossier
        FOREIGN KEY (dossier_uuid)
        REFERENCES candidate_dossiers(uuid)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    -- Filename non-empty
    CONSTRAINT chk_cda_filename_not_empty
        CHECK (CHAR_LENGTH(TRIM(original_filename)) > 0),

    -- display_order positive
    CONSTRAINT chk_cda_positive_display_order
        CHECK (display_order > 0)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Recruitment Dossier: file appendices attached to a dossier';

-- Index per AC #4: list appendices for a dossier in display order
CREATE INDEX idx_cda_dossier_display_order ON candidate_dossier_appendices(dossier_uuid, display_order);

COMMIT;

-- ===================================================================
-- Migration Notes
-- ===================================================================
-- - Appendices are mutable: display_order can be reordered, files
--   replaced (via DELETE + POST), and the row's updated_at bumps.
-- - file_uuid has no DB FK to a files table because S3 file refs in
--   this codebase are tracked by UUID strings without a central
--   "files" table (mirrors template_documents.file_uuid).
-- - When a dossier is CLOSED, the application disables uploads;
--   schema-level enforcement is intentionally absent to keep the
--   table simple.
-- - Rollback: DROP TABLE candidate_dossier_appendices;
-- ===================================================================
