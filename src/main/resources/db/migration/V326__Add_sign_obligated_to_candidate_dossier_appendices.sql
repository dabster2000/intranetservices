-- ===================================================================
-- V326: Add sign_obligated to candidate_dossier_appendices
-- ===================================================================
-- Feature: Recruitment Dossier — per-appendix Sign vs Attach toggle
-- Domain:  recruitmentservice
--
-- Purpose:
--   Each dossier appendix now carries a boolean flag controlling whether
--   the recipient must sign that appendix in the NextSign envelope or
--   whether it ships as an attachment-only document. Mirrors the
--   signObligated semantics already used by the employee-management
--   templates/documents wizard (UploadedDocument, DocumentInfo).
--
-- Default & backfill:
--   New rows default to TRUE (signature required). Existing rows are
--   backfilled to TRUE by the DEFAULT clause — matches the wizard's
--   own default and means in-flight dossiers continue to require
--   signature on every previously uploaded appendix.
--
-- Author: Claude Code
-- Date:   2026-05-07
-- Rollback: ALTER TABLE candidate_dossier_appendices DROP COLUMN sign_obligated;
-- ===================================================================

ALTER TABLE candidate_dossier_appendices
    ADD COLUMN sign_obligated BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'TRUE = signature required on this appendix; FALSE = attachment only',
    ALGORITHM=INPLACE,
    LOCK=NONE;
