-- V325: Drop document_templates.sharepoint_folder.
-- Author:    SharePoint location unification
-- Date:      2026-05-07
-- Reason:    Promote-time SharePoint destination is now resolved from the
--            (user.company, EMPLOYEE) SharePointLocation registry — same
--            source the e-signing flow uses. The per-template folder string
--            is redundant.
-- Rollback:  ALTER TABLE document_templates
--                ADD COLUMN sharepoint_folder VARCHAR(500) NULL
--                    COMMENT 'Base SharePoint folder for promoted hires. Username appended at promote time.';

ALTER TABLE document_templates DROP COLUMN sharepoint_folder;
