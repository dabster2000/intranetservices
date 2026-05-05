-- V315 — align recruitment table collations with the rest of the schema.
--
-- V311-V314 created the four recruitment tables with COLLATE=utf8mb4_unicode_ci.
-- Legacy tables on this database (signing_cases, users, companies,
-- document_templates, ...) use utf8mb4_general_ci, so any JOIN that compares a
-- VARCHAR column from a recruitment table against a VARCHAR column on a legacy
-- table fails with:
--
--   ERROR 1267-HY000: Illegal mix of collations
--   (utf8mb4_unicode_ci,IMPLICIT) and (utf8mb4_general_ci,IMPLICIT)
--   for operation '='
--
-- This surfaced at runtime in RecruitmentSignatureCompletionListener, whose
-- batchlet joins candidate_dossier_revisions.signing_case_key against
-- signing_cases.case_key. Aligning the entire recruitment schema here is
-- safer than scattering COLLATE clauses across query sites and future-proofs
-- other JOINs (target_company_uuid, converted_user_uuid, etc.).
--
-- MariaDB rejects CONVERT TO CHARACTER SET on FK-referenced columns
-- (error 1833). We drop the three FKs, convert all four tables, then re-add
-- the FKs with the original ON DELETE RESTRICT / ON UPDATE CASCADE rules from
-- V312-V314.

ALTER TABLE candidate_dossier_appendices DROP FOREIGN KEY fk_cda_dossier;
ALTER TABLE candidate_dossier_revisions  DROP FOREIGN KEY fk_cdr_dossier;
ALTER TABLE candidate_dossiers           DROP FOREIGN KEY fk_cd_candidate;

ALTER TABLE recruitment_candidates       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE candidate_dossiers           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE candidate_dossier_revisions  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE candidate_dossier_appendices CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

ALTER TABLE candidate_dossiers ADD CONSTRAINT fk_cd_candidate
    FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates(uuid)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE candidate_dossier_revisions ADD CONSTRAINT fk_cdr_dossier
    FOREIGN KEY (dossier_uuid) REFERENCES candidate_dossiers(uuid)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE candidate_dossier_appendices ADD CONSTRAINT fk_cda_dossier
    FOREIGN KEY (dossier_uuid) REFERENCES candidate_dossiers(uuid)
    ON DELETE RESTRICT ON UPDATE CASCADE;
