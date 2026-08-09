-- ===================================================================
-- V475: signing_cases.archive_category — sender-chosen archival category
-- ===================================================================
-- Feature: Document-type classification (signing flows)
--
-- Purpose:
--   Template-based signing cases map their S3 archival category from the
--   template's TemplateCategory (employee-documents spec §6.5.1, V454's
--   template_uuid). Template-less cases (the "Upload Documents" wizard on
--   employee-management) had no category source and always archived as
--   OTHER. This column carries the sender's optional category choice
--   (EmployeeDocumentCategory enum name); at archival time an explicit
--   choice wins over the template mapping, and NULL preserves today's
--   behavior (template mapping, else OTHER).
--
-- Idempotency: ADD COLUMN IF NOT EXISTS; additive only (no drops, per
--   the two-step drop rule).
--
-- Author: Claude Code
-- ===================================================================

ALTER TABLE signing_cases
    ADD COLUMN IF NOT EXISTS archive_category VARCHAR(20) NULL;
