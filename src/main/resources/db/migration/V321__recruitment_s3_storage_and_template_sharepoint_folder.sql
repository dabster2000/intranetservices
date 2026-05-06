-- V321: Recruitment S3-first document storage + per-template SharePoint folder.
-- Additive; no data backfill. Existing in-flight candidates keep current behavior.
-- Author:    Recruitment improvements (S3-first storage)
-- Date:      2026-05-06
-- Rollback:  ALTER TABLE document_templates DROP COLUMN sharepoint_folder;
--            ALTER TABLE candidate_dossier_revisions DROP COLUMN generated_pdfs_snapshot, DROP COLUMN s3_retention_until;
--            ALTER TABLE candidate_dossier_appendices DROP COLUMN s3_retention_until;

ALTER TABLE document_templates
    ADD COLUMN sharepoint_folder VARCHAR(500) NULL
        COMMENT 'Base SharePoint folder for promoted hires. Username appended at promote time.';

ALTER TABLE candidate_dossier_revisions
    ADD COLUMN generated_pdfs_snapshot JSON NULL
        COMMENT 'Array of {filename, fileUuid} for S3-stored generated PDFs. Immutable after creation; enforced at the entity layer (no setter).',
    ADD COLUMN s3_retention_until DATETIME NULL
        COMMENT 'When S3-stored PDFs may be reaped after SharePoint copy succeeds. NULL = never had S3 storage or already reaped.';

ALTER TABLE candidate_dossier_appendices
    ADD COLUMN s3_retention_until DATETIME NULL
        COMMENT 'When this S3-stored appendix may be reaped after the parent candidate is promoted. NULL = never had S3 storage or already reaped.';
