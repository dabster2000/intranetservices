-- Separate ordinary employee-signing templates from recruitment offer/contract
-- templates.  The previous schema had no purpose marker, so every generic
-- /templates read exposed both populations to the same caller.
--
-- Production inventory on 2026-08-23 (read-only): 14 active EMPLOYMENT rows;
-- 9 contract/partner templates and 5 ordinary employee-lifecycle templates
-- (three salary-adjustment and two termination-declaration templates). Nine
-- candidate dossiers referenced three distinct contract templates.
--
-- Fail closed: unknown and future SQL-created rows are recruitment-only until
-- an HR/ADMIN explicitly classifies them. Only the five verified
-- verified lifecycle templates retain employee-signing use.
ALTER TABLE document_templates
    ADD COLUMN template_usage VARCHAR(32) NOT NULL
        DEFAULT 'RECRUITMENT_DOSSIER' AFTER category;

UPDATE document_templates
SET template_usage = 'EMPLOYEE_SIGNING'
WHERE LOWER(name) LIKE '%lønregulering%'
   OR LOWER(name) LIKE '%fratrædelseserklæring%';

-- A persisted dossier is authoritative even if a legacy name happened to
-- match an employee-lifecycle pattern.
UPDATE document_templates t
SET template_usage = 'RECRUITMENT_DOSSIER'
WHERE EXISTS (
    SELECT 1
    FROM candidate_dossiers d
    WHERE d.template_uuid = t.uuid
);

ALTER TABLE document_templates
    ADD CONSTRAINT chk_document_templates_usage
        CHECK (template_usage IN ('EMPLOYEE_SIGNING', 'RECRUITMENT_DOSSIER'));
